package com.folium.reader.reader

import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.pdf.ViewportRenderRequest
import com.folium.reader.core.pdf.ViewportRenderer
import com.folium.reader.core.pdf.ViewportScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers [ReaderPresenter] against a real [ViewportScheduler] and a real request coordinator, with
 * only the renderer and the two scheduling seams — outcome delivery and retry timing — replaced by
 * deterministic doubles. Delivery is queued rather than dispatched, so every assertion runs against
 * a settled state rather than a sampled one.
 */
class ReaderPresenterTest {

    private companion object {
        /**
         * Renderer calls a single presenter-visible failure costs: the first attempt plus the
         * resubmissions the request coordinator makes on its own before forwarding anything.
         */
        const val COORDINATOR_ABSORBED_ATTEMPTS = 4
    }

    private class TestPage(val pageIndex: Int, val spec: RenderSpec)

    private val viewport = ReaderViewport(600, 900)

    private val constructed = AtomicInteger()
    private val released = ConcurrentLinkedQueue<TestPage>()
    private val deliveries = ConcurrentLinkedQueue<() -> Unit>()
    private val retries = ConcurrentLinkedQueue<Pair<Long, () -> Unit>>()
    private var delivered = CountDownLatch(0)

    /**
     * How many further render attempts for a page must fail, and with what. The request coordinator
     * absorbs a retryable rejection by resubmitting on its own before it ever forwards one, so a
     * failure only reaches the presenter if it outlasts that: [COORDINATOR_ABSORBED_ATTEMPTS] is
     * the number of renderer calls a single presenter-visible failure therefore costs.
     */
    private val failuresByPage = mutableMapOf<Int, Pair<Int, PdfFailure>>()
    private val renderGate = mutableMapOf<Int, CountDownLatch>()

    private val presenter = presenter(pageCount = 12) { renderPage(it) }

    @After fun tearDown() {
        renderGate.values.forEach { it.countDown() }
        presenter.close()
        presenter.shutdown()
        drain()
    }

    private fun presenter(
        pageCount: Int,
        render: (ViewportRenderRequest) -> RenderCandidate<TestPage>
    ) = ReaderPresenter(
        pageCount = pageCount,
        releaseValue = { released += it },
        pageAspect = { 0.5f },
        scheduleRetry = { delayMillis, action -> retries += delayMillis to action },
        deliverToPresenter = { action -> deliveries += action; delivered.countDown() },
        onChanged = {}
    ) { onOutcome -> ViewportScheduler(2, { request, _ -> render(request) }, onOutcome = onOutcome) }

    private fun renderPage(request: ViewportRenderRequest): RenderCandidate<TestPage> {
        renderGate[request.pageIndex]?.await(60, TimeUnit.SECONDS)

        failuresByPage[request.pageIndex]?.let { (remaining, failure) ->
            if (remaining <= 1) failuresByPage.remove(request.pageIndex)
            else failuresByPage[request.pageIndex] = remaining - 1 to failure
            throw PdfException(failure)
        }

        constructed.incrementAndGet()
        return RenderCandidate(TestPage(request.pageIndex, request.spec)) { released += it }
    }

    /**
     * Renders arrive on scheduler worker threads and are queued rather than applied, so a test must
     * wait for the queue to fill before draining it — otherwise it would assert against however
     * much happened to have landed by then.
     */
    private fun expect(count: Int, action: () -> Unit) {
        delivered = CountDownLatch(count)
        action()
        assertTrue("timed out waiting for $count outcome(s)", delivered.await(60, TimeUnit.SECONDS))
    }

    private fun drain() {
        while (true) (deliveries.poll() ?: return).invoke()
    }

    /** Drains until nothing further has been delivered for a full quiet period. */
    private fun settle() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        var quietRounds = 0
        while (quietRounds < 5 && System.nanoTime() < deadline) {
            if (deliveries.isEmpty()) quietRounds++ else quietRounds = 0
            drain()
            Thread.sleep(50)
        }
        drain()
    }

    private fun pages(): Map<Int, TestPage> = presenter.uiState.pages

    /** Every value ever rendered is either on screen exactly once, or released exactly once. */
    private fun assertNothingLeakedOrDoubleReleased(shown: Map<Int, TestPage>) {
        val releasedValues = released.toList()
        assertEquals("a value was released twice", releasedValues.size, releasedValues.distinct().size)
        assertTrue("a shown value was also released", releasedValues.none { value -> shown.values.any { it === value } })
        assertEquals(constructed.get(), shown.size + releasedValues.size)
    }

    @Test fun measuringTheViewportRendersTheOpeningWindowAndNothingElse() {
        expect(4) { presenter.setViewport(viewport) }
        drain()

        assertEquals(setOf(0, 1, 2, 3), pages().keys)
        assertEquals(0, presenter.uiState.state.currentPage)
        assertTrue(pages().all { (index, page) -> page.pageIndex == index })
        assertEquals(emptySet<Int>(), presenter.uiState.failedPages)
        assertNothingLeakedOrDoubleReleased(pages())
    }

    @Test fun anUnmeasuredViewportRequestsNothingAtAll() {
        presenter.setViewport(null)
        drain()

        assertEquals(emptyMap<Int, TestPage>(), pages())
        assertEquals(0, constructed.get())
    }

    @Test fun everyPageIsRequestedAtTheSizeItWillBeDrawnAt() {
        expect(4) { presenter.setViewport(viewport) }
        drain()

        val expected = ReaderGeometry.specForPage(viewport, presenter.uiState.state.zoom) { 0.5f }
        pages().forEach { (index, page) -> assertEquals(expected(index), page.spec) }
    }

    @Test fun navigatingReleasesThePagesThatLeftTheWindowAndKeepsNoneOfThemOnScreen() {
        expect(4) { presenter.setViewport(viewport) }
        drain()

        expect(7) { presenter.dispatch(GestureIntent.FlingToPage(6)) }
        drain()

        assertEquals(setOf(3, 4, 5, 6, 7, 8, 9), pages().keys)
        assertTrue(released.map { it.pageIndex }.containsAll(listOf(0, 1, 2)))
        assertNothingLeakedOrDoubleReleased(pages())
    }

    @Test fun aPageRerenderedAtANewZoomReplacesItsPredecessorAndReleasesItExactlyOnce() {
        expect(4) { presenter.setViewport(viewport) }
        drain()
        val before = pages().getValue(0)

        expect(4) { presenter.dispatch(GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f))) }
        drain()

        val after = pages().getValue(0)
        assertNotEquals(before.spec, after.spec)
        assertEquals(listOf(before), released.filter { it === before })
        assertNothingLeakedOrDoubleReleased(pages())
    }

    /**
     * The page a value is shown under is decided by the request it came from, never by whatever
     * page happens to be current when it lands — this is what stops a superseded render from
     * appearing under a page it was not rendered for.
     */
    @Test fun aRenderThatOutlivesItsGenerationIsReleasedInsteadOfBeingShown() {
        renderGate[0] = CountDownLatch(1)

        expect(3) { presenter.setViewport(viewport) }
        drain()
        assertEquals(setOf(1, 2, 3), pages().keys)

        expect(7) {
            presenter.dispatch(GestureIntent.FlingToPage(6))
            renderGate.getValue(0).countDown()
        }
        settle()

        assertEquals(setOf(3, 4, 5, 6, 7, 8, 9), pages().keys)
        assertTrue("a superseded page must never be shown", pages().none { it.value.pageIndex == 0 })
        assertNothingLeakedOrDoubleReleased(pages())
    }

    @Test fun aRetryableResourceFailureIsRedrivenRatherThanShownAsAFailedPage() {
        failuresByPage[2] = COORDINATOR_ABSORBED_ATTEMPTS to PdfFailure.Resource(retryable = true)

        expect(4) { presenter.setViewport(viewport) }
        drain()

        assertEquals(emptySet<Int>(), presenter.uiState.failedPages)
        assertEquals(setOf(0, 1, 3), pages().keys)
        assertEquals(1, retries.size)

        expect(4) { retries.poll()!!.second.invoke() }
        drain()

        assertEquals(setOf(0, 1, 2, 3), pages().keys)
        assertEquals(emptySet<Int>(), presenter.uiState.failedPages)
        assertNothingLeakedOrDoubleReleased(pages())
    }

    @Test fun aRetryableFailureThatNeverClearsStopsRedrivingAndIsReportedOnce() {
        val attempts = AtomicInteger()
        val alwaysFails = presenter(pageCount = 1) {
            attempts.incrementAndGet()
            throw PdfException(PdfFailure.Resource(retryable = true))
        }

        expect(1) { alwaysFails.setViewport(viewport) }
        drain()
        while (retries.isNotEmpty()) {
            expect(1) { retries.poll()!!.second.invoke() }
            drain()
        }

        assertEquals(setOf(0), alwaysFails.uiState.failedPages)
        assertEquals(
            (MAX_PAGE_RETRY_ATTEMPTS + 1) * COORDINATOR_ABSORBED_ATTEMPTS,
            attempts.get()
        )

        alwaysFails.close()
        alwaysFails.shutdown()
    }

    @Test fun aTerminalFailureIsReportedForThatPageAloneAndClearsOnceItRenders() {
        failuresByPage[1] = 1 to PdfFailure.Corrupt

        expect(4) { presenter.setViewport(viewport) }
        drain()

        assertEquals(setOf(1), presenter.uiState.failedPages)
        assertEquals(setOf(0, 2, 3), pages().keys)
        assertEquals(0, retries.size)

        expect(7) { presenter.dispatch(GestureIntent.FlingToPage(4)) }
        drain()

        assertEquals(emptySet<Int>(), presenter.uiState.failedPages)
    }

    @Test fun togglingChromeChangesNothingThatWasRendered() {
        expect(4) { presenter.setViewport(viewport) }
        drain()
        val rendersBefore = constructed.get()
        val shownBefore = pages()

        presenter.dispatch(GestureIntent.ToggleChrome)
        drain()

        assertEquals(false, presenter.uiState.state.chromeVisible)
        assertEquals(rendersBefore, constructed.get())
        assertEquals(shownBefore, pages())
    }

    @Test fun closingReleasesEveryPageStillOnScreenExactlyOnce() {
        expect(4) { presenter.setViewport(viewport) }
        drain()
        val shown = pages().values.toList()

        presenter.close()

        assertEquals(emptyMap<Int, TestPage>(), pages())
        assertEquals(shown.size, released.count { value -> shown.any { it === value } })
        assertNothingLeakedOrDoubleReleased(emptyMap())
    }

    @Test fun aRenderDeliveredAfterCloseIsReleasedRatherThanShown() {
        renderGate[0] = CountDownLatch(1)

        expect(4) {
            presenter.setViewport(viewport)
            renderGate.getValue(0).countDown()
        }
        presenter.close()
        drain()

        assertEquals(emptyMap<Int, TestPage>(), pages())
        assertNothingLeakedOrDoubleReleased(emptyMap())
    }

    /**
     * Cancelling an in-flight render surfaces as a *retryable* refusal, because that is how the
     * engine reports having been asked to stop. Shutting down cancels everything at once, so unless
     * closing first gives up the requests it still owns, every one of those refusals is answered
     * with a resubmission into a scheduler that has already closed — which throws on a worker
     * thread, where nothing is left to catch it.
     */
    @Test fun closingGivesUpOutstandingRequestsSoShuttingDownNeverResubmitsIntoAClosedScheduler() {
        val workerFailures = ConcurrentLinkedQueue<Throwable>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> workerFailures += error }

        try {
            renderGate[0] = CountDownLatch(1)
            renderGate[1] = CountDownLatch(1)
            failuresByPage[0] = Int.MAX_VALUE to PdfFailure.Resource(retryable = true)
            failuresByPage[1] = Int.MAX_VALUE to PdfFailure.Resource(retryable = true)

            presenter.setViewport(viewport)
            presenter.close()

            renderGate.values.forEach { it.countDown() }
            presenter.shutdown()
            drain()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }

        assertEquals(emptyList<Throwable>(), workerFailures.toList())
        assertEquals(emptyMap<Int, TestPage>(), pages())
    }

    @Test fun rapidNavigationNeverShowsAValueUnderAPageItWasNotRenderedFor() {
        presenter.setViewport(viewport)
        repeat(9) { presenter.dispatch(GestureIntent.PageForward) }
        settle()

        assertEquals(9, presenter.uiState.state.currentPage)
        pages().forEach { (index, page) -> assertEquals(index, page.pageIndex) }
        assertEquals(setOf(6, 7, 8, 9, 10, 11), pages().keys)
        assertNothingLeakedOrDoubleReleased(pages())
    }
}
