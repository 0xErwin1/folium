package com.folium.reader.reader

import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.CachedPage
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.HorizontalViewportReducer
import com.folium.reader.core.pdf.MIN_ZOOM_SCALE
import com.folium.reader.core.pdf.PageCacheKey
import com.folium.reader.core.pdf.PageFitMode
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.RenderPriority
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
    private val baseRenderGate = mutableMapOf<Int, CountDownLatch>()

    private val presenter = presenter(pageCount = 12) { renderPage(it) }

    @After fun tearDown() {
        renderGate.values.forEach { it.countDown() }
        baseRenderGate.values.forEach { it.countDown() }
        presenter.close()
        presenter.shutdown()
        drain()
    }

    /**
     * The base tier is driven by its own, independent renderer double rather than [renderPage]:
     * production wires both tiers through the same [com.folium.reader.reader.PdfPageRenderer], but
     * a test double that shared [renderGate]/[failuresByPage] between two schedulers' worker threads
     * would race on those plain (non-thread-safe) maps, and would also make the detail-tier failure
     * and gating scenarios below implicitly exercise the base tier too, muddying what each assertion
     * is actually about. The base tier's own delivery-count contribution is exercised directly by
     * the dedicated base-tier tests further down.
     */
    private fun presenter(
        pageCount: Int,
        gutterPx: Int = 0,
        render: (ViewportRenderRequest) -> RenderCandidate<TestPage>
    ) = ReaderPresenter(
        pageCount = pageCount,
        cacheBudgetBytes = ROOM_FOR_EVERYTHING,
        releaseValue = { released += it },
        pageAspect = { 0.5f },
        gutterPx = gutterPx,
        scheduleRetry = { delayMillis, action -> retries += delayMillis to action },
        deliverToPresenter = { action -> deliveries += action; delivered.countDown() },
        onChanged = {},
        baseSchedulerFactory = { onOutcome -> ViewportScheduler(1, { request, _ -> renderBasePage(request) }, onOutcome = onOutcome) }
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
     * Always succeeds, and never fails: see the [presenter] factory's own doc for why it is
     * separate from [renderPage]. [baseRenderGate] lets a test hold a specific page's base render
     * open exactly like [renderGate] does for the detail tier, without sharing state with it.
     */
    private fun renderBasePage(request: ViewportRenderRequest): RenderCandidate<TestPage> {
        baseRenderGate[request.pageIndex]?.await(60, TimeUnit.SECONDS)
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
    private fun basePages(): Map<Int, TestPage> = presenter.uiState.basePages
    private fun carried(): CarriedPreview<TestPage>? = presenter.uiState.carriedPreview

    /**
     * Every value ever rendered — by either tier — is either on screen exactly once (as a detail
     * page, a base page, or both, since the two tiers construct independent [TestPage] values), or
     * released exactly once. [shown] is the detail tier's own on-screen map, passed explicitly by
     * each call site exactly as before; the base tier's is always read fresh from [presenter].
     */
    private fun assertNothingLeakedOrDoubleReleased(shown: Map<Int, TestPage>) {
        val shownBase = basePages()
        val held = carried()
        val releasedValues = released.toList()
        assertEquals("a value was released twice", releasedValues.size, releasedValues.distinct().size)
        assertTrue("a shown value was also released", releasedValues.none { value -> shown.values.any { it === value } })
        assertTrue("a shown base value was also released", releasedValues.none { value -> shownBase.values.any { it === value } })
        assertTrue("the carried preview was also released", releasedValues.none { it === held?.value })
        assertEquals(constructed.get(), shown.size + shownBase.size + (if (held == null) 0 else 1) + releasedValues.size)
    }

    /**
     * The window leaving a page used to take that page's preview with it, so a drag that outran the
     * renderer showed a blank sheet between one page and the next. The freshest preview is kept
     * instead: it is a page the reader was just looking at, which is worth more than nothing.
     */
    @Test fun `the freshest preview the window leaves behind is kept rather than released`() {
        expect(8) { presenter.setViewport(viewport) }
        drain()
        val freshest = basePages().getValue(3)
        val older = basePages().getValue(0)

        presenter.dispatch(GestureIntent.FlingToPage(8))
        settle()

        assertEquals(3, carried()?.pageIndex ?: -1)
        assertTrue("the freshest preview was released", released.none { it === freshest })
        assertTrue("an older preview was kept as well", released.any { it === older })
        assertNothingLeakedOrDoubleReleased(pages())
    }

    /** One is kept, not a window's worth: this is a stand-in for one page, not a second cache. */
    @Test fun `only one preview is ever carried`() {
        expect(8) { presenter.setViewport(viewport) }
        drain()
        presenter.dispatch(GestureIntent.FlingToPage(8))
        settle()
        val first = requireNotNull(carried())

        presenter.dispatch(GestureIntent.FlingToPage(0))
        settle()

        val second = requireNotNull(carried())
        assertTrue("the same preview was carried twice", first.value !== second.value)
        assertTrue("the preview it replaced was not released", released.any { it === first.value })
        assertNothingLeakedOrDoubleReleased(pages())
    }

    @Test fun `detachCarriedPreview hands over the release obligation and close does not double-release it`() {
        expect(8) { presenter.setViewport(viewport) }
        drain()
        presenter.dispatch(GestureIntent.FlingToPage(8))
        settle()
        val held = requireNotNull(carried())

        val detached = presenter.detachCarriedPreview()

        assertTrue("detachCarriedPreview returned a different preview", detached === held)
        assertTrue("close must not release a preview its caller already took", released.none { it === held.value })

        val secondDetach = presenter.detachCarriedPreview()
        assertEquals(null, secondDetach)

        presenter.close()
        assertTrue("close must not release a preview its caller already took", released.none { it === held.value })
        released += held.value
        assertNothingLeakedOrDoubleReleased(pages())
    }

    @Test fun measuringTheViewportRendersTheOpeningWindowAndNothingElse() {
        expect(8) { presenter.setViewport(viewport) }
        drain()

        assertEquals(setOf(0, 1, 2, 3), pages().keys)
        assertEquals(setOf(0, 1, 2, 3), basePages().keys)
        assertEquals(0, presenter.uiState.state.currentPage)
        assertTrue(pages().all { (index, page) -> page.pageIndex == index })
        assertTrue(basePages().all { (index, page) -> page.pageIndex == index })
        assertEquals(emptySet<Int>(), presenter.uiState.failedPages)
        assertNothingLeakedOrDoubleReleased(pages())
    }

    @Test fun anUnmeasuredViewportRequestsNothingAtAll() {
        presenter.setViewport(null)
        drain()

        assertEquals(emptyMap<Int, TestPage>(), pages())
        assertEquals(0, constructed.get())
    }

    /**
     * A 600x900 viewport fits a 0.5-aspect page's width at 1200px tall, so three quarters of it are
     * on screen. Unless the presenter measures that and says so, panning would treat the page as
     * fully visible and the bottom quarter of every page would be unreachable.
     */
    @Test fun aPageTallerThanTheViewportIsMeasuredSoItsWholeHeightStaysReachable() {
        expect(8) { presenter.setViewport(viewport) }
        drain()

        assertEquals(0.75f, presenter.uiState.state.visibleHeightFraction, 0.0001f)
        assertEquals(0.375f, presenter.uiState.state.zoom.center.y, 0.0001f)

        presenter.dispatch(GestureIntent.PanBy(0f, -1f))
        settle()

        assertEquals(0.625f, presenter.uiState.state.zoom.center.y, 0.0001f)
        assertEquals(MIN_ZOOM_SCALE, presenter.uiState.state.zoom.scale, 0f)
    }

    @Test fun fittingTheWholePageInsteadPutsAllOfItBackOnScreen() {
        expect(8) { presenter.setViewport(viewport) }
        drain()

        presenter.dispatch(GestureIntent.SetFitMode(PageFitMode.PAGE))
        settle()

        assertEquals(PageFitMode.PAGE, presenter.uiState.state.fitMode)
        assertEquals(1f, presenter.uiState.state.visibleHeightFraction, 0.0001f)
        assertEquals(PageSpacePoint(0.5f, 0.5f), presenter.uiState.state.zoom.center)
    }

    @Test fun everyPageIsRequestedAtTheSizeItsPriorityInTheWindowImplies() {
        expect(8) { presenter.setViewport(viewport) }
        drain()

        val state = presenter.uiState.state
        val priorityByPage = mapOf(
            0 to RenderPriority.VISIBLE,
            1 to RenderPriority.NEAR,
            2 to RenderPriority.PREFETCH,
            3 to RenderPriority.PREFETCH
        )
        val policy = ReaderTierPolicy.forBudget(ROOM_FOR_EVERYTHING, viewport)
        val expected = ReaderGeometry.specForPage(viewport, state.zoom, state.fitMode, { priorityByPage.getValue(it) }, policy) { 0.5f }
        pages().forEach { (index, page) -> assertEquals(expected(index), page.spec) }
    }

    @Test fun navigatingReleasesThePagesThatLeftTheWindowAndKeepsNoneOfThemOnScreen() {
        expect(8) { presenter.setViewport(viewport) }
        drain()

        expect(14) { presenter.dispatch(GestureIntent.FlingToPage(6)) }
        drain()

        assertEquals(setOf(3, 4, 5, 6, 7, 8, 9), pages().keys)
        assertEquals(setOf(3, 4, 5, 6, 7, 8, 9), basePages().keys)
        assertTrue(released.map { it.pageIndex }.containsAll(listOf(0, 1, 2)))
        assertNothingLeakedOrDoubleReleased(pages())
    }

    @Test fun aPageRerenderedAtANewZoomReplacesItsPredecessorAndReleasesItExactlyOnce() {
        expect(8) { presenter.setViewport(viewport) }
        drain()
        val before = pages().getValue(0)

        expect(8) { presenter.dispatch(GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f))) }
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

        expect(7) { presenter.setViewport(viewport) }
        drain()
        assertEquals(setOf(1, 2, 3), pages().keys)

        expect(14) {
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

        expect(8) { presenter.setViewport(viewport) }
        drain()

        assertEquals(emptySet<Int>(), presenter.uiState.failedPages)
        assertEquals(setOf(0, 1, 3), pages().keys)
        assertEquals(1, retries.size)

        expect(8) { retries.poll()!!.second.invoke() }
        drain()

        assertEquals(setOf(0, 1, 2, 3), pages().keys)
        assertEquals(emptySet<Int>(), presenter.uiState.failedPages)
        assertNothingLeakedOrDoubleReleased(pages())
    }

    /**
     * F3: exhausting the short, clock-free retry budget must report the page as failed, but must
     * never leave it that way forever — see [ReaderPresenter.fail]'s own doc. This asserts both
     * halves in one test: first that the short budget really is bounded (the page is reported,
     * exactly once, after exactly the same number of renderer calls as before this change), then
     * that giving up schedules exactly one longer-delay re-drive rather than nothing at all, and
     * that invoking it clears the failure the moment the underlying condition (here, simply the
     * renderer no longer throwing) has passed.
     */
    @Test fun aRetryableFailureThatOutlastsTheShortRetryBudgetIsReportedThenRecoveredByTheDelayedRedrive() {
        val attempts = AtomicInteger()
        val shortBudgetFailures = (MAX_PAGE_RETRY_ATTEMPTS + 1) * COORDINATOR_ABSORBED_ATTEMPTS
        val alwaysFails = presenter(pageCount = 1) {
            val attempt = attempts.incrementAndGet()
            if (attempt <= shortBudgetFailures) throw PdfException(PdfFailure.Resource(retryable = true))
            RenderCandidate(TestPage(it.pageIndex, it.spec)) { released += it }
        }

        expect(2) { alwaysFails.setViewport(viewport) }
        drain()
        repeat(MAX_PAGE_RETRY_ATTEMPTS) {
            expect(2) { retries.poll()!!.second.invoke() }
            drain()
        }

        assertEquals(setOf(0), alwaysFails.uiState.failedPages)
        assertEquals(shortBudgetFailures, attempts.get())

        val recoveryRedrive = retries.poll()
        assertEquals(
            "the short retry budget must hand off to exactly one longer-delay re-drive, not nothing",
            RECOVERY_REDRIVE_DELAY_MILLIS,
            recoveryRedrive?.first
        )

        expect(2) { recoveryRedrive!!.second.invoke() }
        drain()

        assertEquals(emptySet<Int>(), alwaysFails.uiState.failedPages)
        assertEquals(setOf(0), alwaysFails.uiState.pages.keys)

        alwaysFails.close()
        alwaysFails.shutdown()
    }

    /**
     * F3's other way out: a page that failed under one wanted window is worth a fresh attempt the
     * moment the window changes for any reason, without waiting for [RECOVERY_REDRIVE_DELAY_MILLIS]
     * at all.
     */
    @Test fun aFailedPageIsRevivedAssoonAsTheWantedWindowChangesAgain() {
        failuresByPage[0] = COORDINATOR_ABSORBED_ATTEMPTS * (MAX_PAGE_RETRY_ATTEMPTS + 1) to PdfFailure.Resource(retryable = true)

        // The default presenter's window is {0, 1, 2, 3}: every requestWindow() call resubmits the
        // whole window, both tiers, since a page is cleared from the coordinator's own bookkeeping
        // the moment it resolves — successfully or not — not only while it stays outstanding.
        expect(8) { presenter.setViewport(viewport) }
        drain()
        repeat(MAX_PAGE_RETRY_ATTEMPTS) {
            expect(8) { retries.poll()!!.second.invoke() }
            drain()
        }
        assertEquals(setOf(0), presenter.uiState.failedPages)

        // A zoom rolls the generation, so every wanted page (both tiers) is resubmitted fresh, not
        // only the one that had failed — see HorizontalViewportRequestCoordinator.applyState's own
        // handling of a generation change.
        expect(8) { presenter.dispatch(GestureIntent.ZoomBy(1.5f, PageSpacePoint(0.5f, 0.5f))) }
        drain()

        assertEquals(emptySet<Int>(), presenter.uiState.failedPages)
        assertTrue(0 in pages().keys)
    }

    @Test fun aTerminalFailureIsReportedForThatPageAloneAndClearsOnceItRenders() {
        failuresByPage[1] = 1 to PdfFailure.Corrupt

        expect(8) { presenter.setViewport(viewport) }
        drain()

        assertEquals(setOf(1), presenter.uiState.failedPages)
        assertEquals(setOf(0, 2, 3), pages().keys)
        assertEquals(0, retries.size)

        expect(14) { presenter.dispatch(GestureIntent.FlingToPage(4)) }
        drain()

        assertEquals(emptySet<Int>(), presenter.uiState.failedPages)
    }

    @Test fun togglingChromeChangesNothingThatWasRendered() {
        expect(8) { presenter.setViewport(viewport) }
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
        expect(8) { presenter.setViewport(viewport) }
        drain()
        val shown = pages().values.toList()
        val shownBase = basePages().values.toList()

        presenter.close()

        assertEquals(emptyMap<Int, TestPage>(), pages())
        assertEquals(emptyMap<Int, TestPage>(), basePages())
        assertEquals(shown.size, released.count { value -> shown.any { it === value } })
        assertEquals(shownBase.size, released.count { value -> shownBase.any { it === value } })
        assertNothingLeakedOrDoubleReleased(emptyMap())
    }

    @Test fun aRenderDeliveredAfterCloseIsReleasedRatherThanShown() {
        renderGate[0] = CountDownLatch(1)

        expect(8) {
            presenter.setViewport(viewport)
            renderGate.getValue(0).countDown()
        }
        presenter.close()
        drain()

        assertEquals(emptyMap<Int, TestPage>(), pages())
        assertEquals(emptyMap<Int, TestPage>(), basePages())
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

    /** Requirement 1/6: the base tier is requested for exactly the same window as the detail tier. */
    @Test fun theBaseTierIsRequestedForExactlyTheSameWindowAsTheDetailTier() {
        expect(8) { presenter.setViewport(viewport) }
        drain()

        assertEquals(pages().keys, basePages().keys)
    }

    /** Requirement 5: a base value is released, exactly once, when its page leaves the window. */
    @Test fun leavingTheWindowReleasesTheBaseTierExactlyLikeTheDetailTier() {
        expect(8) { presenter.setViewport(viewport) }
        drain()
        val leavingBase = basePages().getValue(0)

        expect(14) { presenter.dispatch(GestureIntent.FlingToPage(6)) }
        drain()

        assertTrue(0 !in basePages().keys)
        assertEquals(listOf(leavingBase), released.filter { it === leavingBase })
        assertNothingLeakedOrDoubleReleased(pages())
    }

    /** Requirement 5: closing releases every base value still held, exactly once, none shown after. */
    @Test fun closingReleasesEveryBaseTierValueStillHeldExactlyOnce() {
        expect(8) { presenter.setViewport(viewport) }
        drain()
        val shownBase = basePages().values.toList()
        assertTrue(shownBase.isNotEmpty())

        presenter.close()

        assertEquals(emptyMap<Int, TestPage>(), basePages())
        assertEquals(shownBase.size, released.count { value -> shownBase.any { it === value } })
        assertNothingLeakedOrDoubleReleased(emptyMap())
    }

    /**
     * Requirement 6: a base render that arrives for a page the presenter no longer wants — because
     * it fell out of the window while the render was in flight — must never be shown, exactly like a
     * superseded detail render.
     */
    @Test fun aBaseRenderForAPageThatLeftTheWindowIsReleasedInsteadOfShown() {
        baseRenderGate[0] = CountDownLatch(1)

        // The base scheduler's single worker is dispatched to page 0 first (current page, highest
        // priority) and gated there, so pages 1-3's base requests stay queued behind it and nothing
        // base-tier is delivered yet — only the detail tier, which is unaffected by this gate.
        expect(4) { presenter.setViewport(viewport) }
        drain()
        assertEquals(setOf(0, 1, 2, 3), pages().keys)
        assertEquals(emptySet<Int>(), basePages().keys)

        expect(14) {
            presenter.dispatch(GestureIntent.FlingToPage(6))
            baseRenderGate.getValue(0).countDown()
        }
        settle()

        assertTrue("a superseded base render must never be shown", 0 !in basePages().keys)
        assertEquals(setOf(3, 4, 5, 6, 7, 8, 9), basePages().keys)
        assertNothingLeakedOrDoubleReleased(pages())
    }

    /** Requirement 4: bounded window means a bounded number of base values, even deep in a long book. */
    @Test fun theBaseTierNeverHoldsMoreThanTheCurrentWindowAcrossManyPageTurns() {
        val long = presenter(pageCount = 500) { renderBasePage(it) }
        long.setViewport(viewport)
        repeat(400) { long.dispatch(GestureIntent.PageForward) }
        settle()

        assertTrue("base window must stay bounded, was ${long.uiState.basePages.size}", long.uiState.basePages.size <= 7)

        long.close()
        long.shutdown()
    }

    /**
     * Both of a fitted spread's pages are the current page as far as rendering is concerned: they
     * are requested at [RenderPriority.VISIBLE], and against the slot viewport [ReaderGeometry.slotViewport]
     * derives from the measured page area and the configured gutter, not the whole page area.
     */
    @Test fun enteringASpreadRequestsBothVisiblePagesSizedToTheSlotViewport() {
        val gutterPx = 40
        val spreadPresenter = presenter(pageCount = 12, gutterPx = gutterPx) { renderPage(it) }
        expect(8) { spreadPresenter.setViewport(viewport) }
        drain()

        spreadPresenter.dispatch(GestureIntent.SetPagesPerView(2))
        settle()

        assertEquals(2, spreadPresenter.uiState.state.pagesPerView)
        assertTrue(setOf(0, 1).all { it in spreadPresenter.uiState.pages.keys })

        val slotViewport = ReaderGeometry.slotViewport(viewport, pagesPerView = 2, gutterPx = gutterPx)
        val state = spreadPresenter.uiState.state
        val policy = ReaderTierPolicy.forBudget(ROOM_FOR_EVERYTHING, slotViewport)
        val expected = ReaderGeometry.specForPage(slotViewport, state.zoom, state.fitMode, { RenderPriority.VISIBLE }, policy) { 0.5f }

        assertEquals(expected(0), spreadPresenter.uiState.pages.getValue(0).spec)
        assertEquals(expected(1), spreadPresenter.uiState.pages.getValue(1).spec)

        spreadPresenter.close()
        spreadPresenter.shutdown()
        drain()
    }

    /**
     * D2: zooming into one of a spread's two pages leaves that page requested exactly like ordinary
     * single-page mode — full page-area geometry, not the narrower slot the spread was just fitted
     * to — which is what "the existing render/geometry pipeline for a zoomed single page must not
     * change" means at the request level.
     */
    @Test fun zoomingIntoASpreadPageRequestsItAgainstTheWholePageAreaRatherThanTheSlot() {
        val gutterPx = 40
        val spreadPresenter = presenter(pageCount = 12, gutterPx = gutterPx) { renderPage(it) }
        spreadPresenter.setViewport(viewport)
        spreadPresenter.dispatch(GestureIntent.SetPagesPerView(2))
        settle()

        spreadPresenter.dispatch(GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f), focusPage = 1))
        settle()

        assertEquals(1, spreadPresenter.uiState.state.currentPage)
        assertEquals(1, HorizontalViewportReducer.effectivePagesPerView(spreadPresenter.uiState.state))

        val state = spreadPresenter.uiState.state
        val policy = ReaderTierPolicy.forBudget(ROOM_FOR_EVERYTHING, viewport)
        val expected = ReaderGeometry.specForPage(viewport, state.zoom, state.fitMode, { RenderPriority.VISIBLE }, policy) { 0.5f }

        assertEquals(expected(1), spreadPresenter.uiState.pages.getValue(1).spec)

        spreadPresenter.close()
        spreadPresenter.shutdown()
        drain()
    }

    private class LeakSweepPage(val pageIndex: Int)

    /** Mirrors [BorrowedPage]: the only handle a consumer holds a cached value through. */
    private class LeakSweepBorrow(private val borrow: CachedPage<LeakSweepPage>) {
        fun release() = borrow.release()
    }

    /**
     * Mirrors [PdfPageRenderer]'s own acquire-or-rasterize-then-acquire shape against a real
     * [ByteBoundedPageCache], so both tiers exercise the exact borrow protocol production uses,
     * not a simplified stand-in for it.
     */
    private fun leakSweepRender(
        cache: ByteBoundedPageCache<LeakSweepPage>,
        documentId: String,
        request: ViewportRenderRequest
    ): RenderCandidate<LeakSweepBorrow> {
        val key = PageCacheKey(documentId, request.pageIndex, 0L, request.spec)
        cache.acquire(key)?.let { return RenderCandidate(LeakSweepBorrow(it)) { it.release() } }

        cache.put(key, RenderCandidate(LeakSweepPage(request.pageIndex)) {}, sizeBytes = 1_024L)
        val borrow = cache.acquire(key) ?: throw PdfException(PdfFailure.Resource(retryable = true))
        return RenderCandidate(LeakSweepBorrow(borrow)) { borrow.release() }
    }

    /**
     * Requirement 5, the batch's own stated main risk: every base-tier `acquire` from the shared
     * cache is paired with a `release` on every path — window departure, generation roll, session
     * teardown — across many full open/navigate/zoom/close cycles against a real
     * [ByteBoundedPageCache], exactly like [PdfPageRenderer] and [ReaderSession] wire it in
     * production. [ByteBoundedPageCache.pinnedAwaitingReleaseCount] is the cache's own documented
     * leak detector: a value that has not returned to zero once every session in the sweep has
     * closed and shut down means a borrow was acquired and never released.
     */
    @Test fun manyOpenNavigateZoomCloseCyclesLeaveNoBorrowOutstandingInTheSharedCache() {
        val cache = ByteBoundedPageCache<LeakSweepPage>(4L * 1024 * 1024)

        repeat(40) { cycle ->
            val documentId = "doc-$cycle"
            val session = ReaderPresenter(
                pageCount = 20,
                releaseValue = LeakSweepBorrow::release,
                cacheBudgetBytes = ROOM_FOR_EVERYTHING,
                pageAspect = { 0.5f },
                scheduleRetry = { _, action -> action() },
                deliverToPresenter = { action -> deliveries += action; delivered.countDown() },
                onChanged = {},
                baseSchedulerFactory = { onOutcome ->
                    ViewportScheduler(1, { request, _ -> leakSweepRender(cache, documentId, request) }, onOutcome = onOutcome)
                }
            ) { onOutcome -> ViewportScheduler(2, { request, _ -> leakSweepRender(cache, documentId, request) }, onOutcome = onOutcome) }

            // Every dispatch is fired back-to-back rather than settled individually: the coordinator's
            // own token/generation ownership is what has to stay correct under overlapping in-flight
            // requests, exactly as it would under fast real-world navigation, so waiting between steps
            // would test a less realistic — and unnecessarily slow — interleaving.
            session.setViewport(viewport)
            repeat(6) { session.dispatch(GestureIntent.PageForward) }
            session.dispatch(GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f)))
            session.dispatch(GestureIntent.ResetZoom)
            settle()

            session.close()
            session.shutdown()
            drain()
            cache.invalidateDocument(documentId)
        }

        // ByteBoundedPageCache.pinnedAwaitingReleaseCount() is internal to :reader-core and not
        // visible from this module's tests; totalBytesTracked() is this cache's own public leak
        // signal instead — see its class doc: a leaked borrow's bytes stay counted forever, so a
        // return to exactly zero here is the same guarantee from this side of the module boundary.
        assertEquals(0L, cache.totalBytesTracked())
    }

    /**
     * The same sweep as [manyOpenNavigateZoomCloseCyclesLeaveNoBorrowOutstandingInTheSharedCache],
     * but every cycle also enters and leaves a spread: turning [GestureIntent.SetPagesPerView] on
     * doubles the pages the wanted window can hold at once, and zooming into one of them (D2) forces
     * a page originally requested against the slot viewport to be requested again against the whole
     * page area — both are new ways for a borrow to be requested, superseded or left the window
     * without its release ever running, so this sweep is what proves neither leaks one.
     */
    @Test fun manySpreadEnterExitAndZoomCyclesLeaveNoBorrowOutstandingInTheSharedCache() {
        val cache = ByteBoundedPageCache<LeakSweepPage>(4L * 1024 * 1024)

        repeat(40) { cycle ->
            val documentId = "spread-doc-$cycle"
            val session = ReaderPresenter(
                pageCount = 20,
                releaseValue = LeakSweepBorrow::release,
                cacheBudgetBytes = ROOM_FOR_EVERYTHING,
                pageAspect = { 0.5f },
                gutterPx = 40,
                scheduleRetry = { _, action -> action() },
                deliverToPresenter = { action -> deliveries += action; delivered.countDown() },
                onChanged = {},
                baseSchedulerFactory = { onOutcome ->
                    ViewportScheduler(1, { request, _ -> leakSweepRender(cache, documentId, request) }, onOutcome = onOutcome)
                }
            ) { onOutcome -> ViewportScheduler(2, { request, _ -> leakSweepRender(cache, documentId, request) }, onOutcome = onOutcome) }

            session.setViewport(viewport)
            session.dispatch(GestureIntent.SetPagesPerView(2))
            repeat(3) { session.dispatch(GestureIntent.PageForward) }
            session.dispatch(GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f), focusPage = session.uiState.state.currentPage + 1))
            session.dispatch(GestureIntent.ResetZoom)
            session.dispatch(GestureIntent.PageForward)
            session.dispatch(GestureIntent.SetPagesPerView(1))
            settle()

            session.close()
            session.shutdown()
            drain()
            cache.invalidateDocument(documentId)
        }

        assertEquals(0L, cache.totalBytesTracked())
    }
}

/** These tests are about what the presenter requests, not about what a tight device can afford. */
private const val ROOM_FOR_EVERYTHING = 96L * 1024 * 1024
