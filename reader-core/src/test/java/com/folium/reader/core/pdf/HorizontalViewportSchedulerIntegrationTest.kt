package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers [HorizontalViewportRequestCoordinator] wired against a real [ViewportScheduler]: page
 * selection driving real submissions, requested-page ownership across a moved window (generation
 * rollover), and the retryable-resubmission contract from `folium/scheduler-retryable-contract`.
 */
class HorizontalViewportSchedulerIntegrationTest {

    @Test fun applyingStateSubmitsExactlyTheSelectedPageWindowAtItsMappedPriority() {
        val submitted = CopyOnWriteArrayList<Pair<Int, RenderPriority>>()
        val allDone = CountDownLatch(4)

        val renderer = ViewportRenderer<String> { request, _ ->
            submitted.add(request.pageIndex to request.priority)
            RenderCandidate("page-${request.pageIndex}") {}
        }
        lateinit var coordinator: HorizontalViewportRequestCoordinator<String>
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 4, renderer = renderer) { outcome ->
            coordinator.onSchedulerOutcome(outcome)
            allDone.countDown()
        }
        coordinator = HorizontalViewportRequestCoordinator(scheduler, releaseValue = {}) { }

        try {
            val state = HorizontalViewportState.initial(pageCount = 10)
            coordinator.applyState(state) { spec() }
            assertTrue(allDone.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertEquals(
            setOf(0 to RenderPriority.VISIBLE, 1 to RenderPriority.NEAR, 2 to RenderPriority.PREFETCH, 3 to RenderPriority.PREFETCH),
            submitted.toSet()
        )
    }

    @Test fun movingTheWindowOwnsOnlyTheCurrentGenerationAndDropsEveryOutcomeFromTheAbandonedOne() {
        val delivered = CopyOnWriteArrayList<PageRenderOutcome<String>>()
        val page0Started = CountDownLatch(1)
        val releasePage0 = CountDownLatch(1)
        val newWindowSettled = CountDownLatch(4) // pages 6,7,8,9 of the moved window

        val renderer = ViewportRenderer<String> { request, _ ->
            if (request.pageIndex == 0) {
                page0Started.countDown()
                releasePage0.await(5, TimeUnit.SECONDS)
            }
            RenderCandidate("page-${request.pageIndex}") {}
        }
        lateinit var coordinator: HorizontalViewportRequestCoordinator<String>
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
            coordinator.onSchedulerOutcome(outcome)
        }
        coordinator = HorizontalViewportRequestCoordinator(scheduler, releaseValue = {}) { outcome ->
            delivered.add(outcome)
            newWindowSettled.countDown()
        }

        try {
            // Page 0 occupies the scheduler's only worker slot; pages 1-3 stay queued, never started.
            val nearStart = HorizontalViewportState.initial(pageCount = 10)
            coordinator.applyState(nearStart) { spec() }
            assertTrue(page0Started.await(5, TimeUnit.SECONDS))

            // Fling far away: the abandoned generation's still-queued pages (1-3) and, once it
            // finally reports, the in-flight page (0) must never reach the consumer.
            val flungFar = HorizontalViewportReducer.reduce(nearStart, GestureIntent.FlingToPage(9))
            coordinator.applyState(flungFar) { spec() }

            releasePage0.countDown()
            assertTrue(newWindowSettled.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        val deliveredPages = delivered.mapNotNull { outcome ->
            when (outcome) {
                is PageRenderOutcome.Rendered -> outcome.pageIndex
                is PageRenderOutcome.Failed -> outcome.pageIndex
            }
        }
        assertEquals(setOf(6, 7, 8, 9), deliveredPages.toSet())
        assertTrue("no outcome for the abandoned window (pages 0-3) may ever reach the consumer: $delivered", deliveredPages.none { it in 0..3 })
    }

    @Test fun aRetryableResourceRejectionIsResubmittedRatherThanSurfacedAsAPageError() {
        val attempts = AtomicInteger(0)
        val delivered = CopyOnWriteArrayList<PageRenderOutcome<String>>()
        val settled = CountDownLatch(1)

        val renderer = ViewportRenderer<String> { request, _ ->
            if (attempts.getAndIncrement() == 0) throw PdfException(PdfFailure.Resource(retryable = true))
            RenderCandidate("page-${request.pageIndex}") {}
        }
        lateinit var coordinator: HorizontalViewportRequestCoordinator<String>
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
            coordinator.onSchedulerOutcome(outcome)
        }
        coordinator = HorizontalViewportRequestCoordinator(scheduler, releaseValue = {}) { outcome ->
            delivered.add(outcome)
            if (outcome is PageRenderOutcome.Rendered) settled.countDown()
        }

        try {
            val state = HorizontalViewportState.initial(pageCount = 1)
            coordinator.applyState(state) { spec() }
            assertTrue(settled.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertEquals(2, attempts.get())
        assertEquals(listOf(PageRenderOutcome.Rendered(0, "page-0")), delivered)
    }

    /**
     * Mirrors `folium/scheduler-retryable-contract`: one transient thread-start failure can settle
     * the entire remaining backlog as retryable. The coordinator must resubmit every one of them
     * rather than forwarding any of them as a page error.
     */
    @Test fun aBacklogWideTransientFailureIsFullyResubmittedAndNeverSurfacesAsAPageError() {
        val delivered = CopyOnWriteArrayList<PageRenderOutcome<String>>()
        val allRendered = CountDownLatch(7)
        val hookFired = AtomicBoolean(false)

        val renderer = ViewportRenderer<String> { request, _ -> RenderCandidate("page-${request.pageIndex}") {} }
        lateinit var coordinator: HorizontalViewportRequestCoordinator<String>
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 2, renderer = renderer) { outcome ->
            coordinator.onSchedulerOutcome(outcome)
        }
        coordinator = HorizontalViewportRequestCoordinator(scheduler, releaseValue = {}) { outcome ->
            delivered.add(outcome)
            if (outcome is PageRenderOutcome.Rendered) allRendered.countDown()
        }
        scheduler.threadStartHookForTests = { thread ->
            if (hookFired.compareAndSet(false, true)) {
                throw OutOfMemoryError("simulated native thread-creation failure")
            }
            thread.start()
        }

        try {
            // pageCount=7, currentPage=3 selects the full window 0..6 with no clamping loss.
            val state = HorizontalViewportState.initial(pageCount = 7).copy(currentPage = 3)
            coordinator.applyState(state) { spec() }
            assertTrue(allRendered.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertTrue(delivered.none { it is PageRenderOutcome.Failed })
        assertEquals((0..6).toSet(), delivered.filterIsInstance<PageRenderOutcome.Rendered<String>>().map { it.pageIndex }.toSet())
    }

    /**
     * `aBacklogWideTransientFailureIsFullyResubmittedAndNeverSurfacesAsAPageError` only exercises a
     * single, one-shot backlog failure (its fake fails exactly once by construction). This is the
     * sustained-failure companion: every start attempt fails, so the coordinator must exhaust its
     * bounded resubmission budget and surface a terminal, retryable [PageRenderOutcome.Failed]
     * rather than hanging, spinning unbounded, or crashing with a `StackOverflowError`. It then
     * proves the [PageRenderOutcome.Failed] KDoc's documented re-drive mechanism actually works: a
     * further [HorizontalViewportRequestCoordinator.applyState] call for the same still-wanted page,
     * once the transient condition clears, must produce a fresh attempt budget and a real render —
     * not permanent silence (this is what closes C-3: `outstanding` must not have been repopulated
     * with a dead handle while the exhausted retry chain was unwinding).
     */
    @Test fun sustainedTransientFailureExhaustsRetriesAndSurfacesATerminalRetryableFailureThenThePageIsReRequestable() {
        val delivered = CopyOnWriteArrayList<PageRenderOutcome<String>>()
        val failed = CountDownLatch(1)
        val rendered = CountDownLatch(1)
        val startAttempts = AtomicInteger(0)
        val stillFailing = AtomicBoolean(true)

        val renderer = ViewportRenderer<String> { request, _ -> RenderCandidate("page-${request.pageIndex}") {} }
        lateinit var coordinator: HorizontalViewportRequestCoordinator<String>
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
            coordinator.onSchedulerOutcome(outcome)
        }
        coordinator = HorizontalViewportRequestCoordinator(scheduler, releaseValue = {}) { outcome ->
            delivered.add(outcome)
            when (outcome) {
                is PageRenderOutcome.Failed -> failed.countDown()
                is PageRenderOutcome.Rendered -> rendered.countDown()
            }
        }
        scheduler.threadStartHookForTests = { thread ->
            startAttempts.incrementAndGet()
            if (stillFailing.get()) throw OutOfMemoryError("simulated sustained native thread-creation failure") else thread.start()
        }

        try {
            val state = HorizontalViewportState.initial(pageCount = 1)
            coordinator.applyState(state) { spec() }
            assertTrue(failed.await(5, TimeUnit.SECONDS))

            stillFailing.set(false)
            coordinator.applyState(state) { spec() }
            assertTrue(rendered.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertEquals(
            listOf<PageRenderOutcome<String>>(
                PageRenderOutcome.Failed(0, PdfFailure.Resource(retryable = true)),
                PageRenderOutcome.Rendered(0, "page-0")
            ),
            delivered
        )
        assertEquals("4 exhausted attempts, then exactly 1 fresh attempt on re-drive", 5, startAttempts.get())
    }

    /**
     * Reproduces C-4's scenario: while a page's retry chain is unwinding synchronously (see
     * `sustainedTransientFailureExhaustsRetriesAndSurfacesATerminalRetryableFailureThenThePageIsReRequestable`'s
     * doc on the retry chain's shape), a stale [RejectionReason.STALE_GENERATION] outcome for the
     * *same page*, belonging to an already-superseded request, is delivered from a genuinely
     * different thread. A token-discriminated ownership check must drop it silently — its token was
     * never recorded as live for this page — rather than accepting it as this coordinator's own and
     * wiping the live retry chain's attempt bookkeeping, which is what reopened the `StackOverflowError`
     * this coordinator's bounded resubmission was supposed to prevent.
     *
     * The injection fires on *every* start attempt, not just the first: injecting only once, before
     * any attempt budget exists yet, wipes a bookkeeping entry that is still empty and proves
     * nothing — the assertion below is the exact bound the retry budget promises, not merely an
     * upper bound, so this test cannot pass by accident against an ownership check that is too
     * permissive in a way this specific interleaving doesn't happen to trigger.
     */
    @Test fun aStaleOutcomeInjectedFromAnotherThreadOnEveryRetryAttemptNeverResetsTheLiveAttemptBudget() {
        val delivered = CopyOnWriteArrayList<PageRenderOutcome<String>>()
        val settled = CountDownLatch(1)
        val startAttempts = AtomicInteger(0)

        val renderer = ViewportRenderer<String> { request, _ -> RenderCandidate("page-${request.pageIndex}") {} }
        lateinit var coordinator: HorizontalViewportRequestCoordinator<String>
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
            coordinator.onSchedulerOutcome(outcome)
        }
        coordinator = HorizontalViewportRequestCoordinator(scheduler, releaseValue = {}) { outcome ->
            delivered.add(outcome)
            settled.countDown()
        }
        scheduler.threadStartHookForTests = { _ ->
            startAttempts.incrementAndGet()
            val staleRequest = ViewportRenderRequest(
                requestId = -1L,
                pageIndex = 0,
                priority = RenderPriority.VISIBLE,
                generation = 0L,
                spec = spec(),
                token = -1L
            )
            val injector = Thread {
                coordinator.onSchedulerOutcome(SchedulerOutcome.Rejected(staleRequest, RejectionReason.STALE_GENERATION))
            }
            injector.start()
            injector.join(5_000)
            assertTrue("stale-outcome injector thread did not finish", !injector.isAlive)
            throw OutOfMemoryError("simulated sustained native thread-creation failure")
        }

        try {
            val state = HorizontalViewportState.initial(pageCount = 1)
            coordinator.applyState(state) { spec() }
            assertTrue(settled.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertEquals(listOf<PageRenderOutcome<String>>(PageRenderOutcome.Failed(0, PdfFailure.Resource(retryable = true))), delivered)
        assertEquals(
            "resubmission must stay exactly bounded despite the concurrently injected stale outcome on every attempt",
            4,
            startAttempts.get()
        )
    }

    /**
     * Reproduces the leak this coordinator would otherwise have on every fast scroll: `applyState`
     * cancelling a page whose render has *already completed* races [ViewportScheduler.cancel]
     * against publication, and can lose — the request has already left the scheduler's own
     * in-flight bookkeeping by the time `cancel` runs, making it a genuine no-op there, while this
     * coordinator has already stopped considering the page's token live (it is no longer wanted).
     * The resulting [SchedulerOutcome.Rendered] then arrives here exactly as
     * [aStaleOutcomeInjectedFromAnotherThreadOnEveryRetryAttemptNeverResetsTheLiveAttemptBudget]'s
     * stale [SchedulerOutcome.Rejected] does — a genuine outcome for a token this coordinator no
     * longer owns, delivered from a different thread — so it is reproduced the same deterministic
     * way: injecting the scheduler's own real, already-published outcome directly, since the actual
     * race window between the scheduler's in-flight removal and its publish call is too narrow to
     * hit reliably by timing real threads. Without this test's fix, [onSchedulerOutcome] silently
     * drops such an outcome without ever releasing the candidate value it carries.
     */
    @Test fun aRenderedOutcomeNoLongerOwnedBecauseItsPageWasCancelledIsReleasedExactlyOnce() {
        val released = CopyOnWriteArrayList<String>()
        val settled = CountDownLatch(1)

        val renderer = ViewportRenderer<String> { request, _ -> RenderCandidate("page-${request.pageIndex}") {} }
        lateinit var coordinator: HorizontalViewportRequestCoordinator<String>
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
            coordinator.onSchedulerOutcome(outcome)
        }
        coordinator = HorizontalViewportRequestCoordinator(
            scheduler = scheduler,
            releaseValue = { value -> released.add(value) }
        ) { outcome ->
            if (outcome is PageRenderOutcome.Rendered) settled.countDown()
        }

        try {
            val state = HorizontalViewportState.initial(pageCount = 1)
            coordinator.applyState(state) { spec() }
            assertTrue(settled.await(5, TimeUnit.SECONDS))

            val lateRequest = ViewportRenderRequest(
                requestId = -1L,
                pageIndex = 0,
                priority = RenderPriority.VISIBLE,
                generation = 0L,
                spec = spec(),
                token = -1L
            )
            coordinator.onSchedulerOutcome(SchedulerOutcome.Rendered(lateRequest, "page-0-raced-with-cancel"))
        } finally {
            scheduler.close()
        }

        assertEquals(listOf("page-0-raced-with-cancel"), released)
    }

    /**
     * The generation bridge in `HorizontalViewportRequestCoordinator.applyState` (calling
     * `scheduler.advanceGeneration()` on a generation change, before recomputing the wanted window)
     * is only load-bearing when the wanted page set itself does not change -- a fling changes the
     * wanted pages, so cancellation alone already covers it (see
     * `movingTheWindowOwnsOnlyTheCurrentGenerationAndDropsEveryOutcomeFromTheAbandonedOne`).
     * [GestureIntent.ViewportResized] is exactly that case: the current page is unchanged, so
     * without the bridge a stale, old-size render already in flight would be delivered as if it
     * were current.
     */
    @Test fun viewportResizeInvalidatesAnInFlightRenderEvenWhenTheWantedPageSetIsUnchanged() {
        val renderedWidths = CopyOnWriteArrayList<Int>()
        val oldSizeStarted = CountDownLatch(1)
        val releaseOldSize = CountDownLatch(1)
        val settled = CountDownLatch(1)

        val renderer = ViewportRenderer<Int> { request, _ ->
            if (request.spec.width == 100) {
                oldSizeStarted.countDown()
                releaseOldSize.await(5, TimeUnit.SECONDS)
            }
            RenderCandidate(request.spec.width) {}
        }
        lateinit var coordinator: HorizontalViewportRequestCoordinator<Int>
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
            coordinator.onSchedulerOutcome(outcome)
        }
        coordinator = HorizontalViewportRequestCoordinator(scheduler, releaseValue = {}) { outcome ->
            if (outcome is PageRenderOutcome.Rendered) {
                renderedWidths.add(outcome.value)
                settled.countDown()
            }
        }

        try {
            val state = HorizontalViewportState.initial(pageCount = 1)
            coordinator.applyState(state) { RenderSpec(width = 100, height = 100) }
            assertTrue(oldSizeStarted.await(5, TimeUnit.SECONDS))

            val resized = HorizontalViewportReducer.reduce(state, GestureIntent.ViewportResized)
            coordinator.applyState(resized) { RenderSpec(width = 250, height = 100) }

            releaseOldSize.countDown()
            assertTrue(settled.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertEquals(listOf(250), renderedWidths)
    }

    /**
     * Reproduces the shutdown crash: a worker resubmits an owned retryable rejection
     * ([HorizontalViewportRequestCoordinator.onSchedulerOutcome]'s `:208` branch) exactly as
     * [ViewportScheduler.close] has already flipped its `closed` flag but is still waiting for this
     * very worker to finish. [ViewportScheduler.submit] correctly throws [SchedulerClosedException]
     * — that refusal is a verified, unchanged invariant (see
     * `submitAfterCloseIsRejectedRatherThanSilentlyDropped`) — but the coordinator resubmitting into
     * it must not let that exception surface uncaught on the worker thread, since nobody is waiting
     * for that outcome once the scheduler that would have carried it is already gone.
     */
    @Test
    fun resubmittingIntoASchedulerThatClosedWhileARetryableFailureWasInFlightNeverThrowsUncaughtOnTheWorkerThread() {
        lateinit var workerThread: Thread
        val started = CountDownLatch(1)
        val releaseFirstAttempt = CountDownLatch(1)
        val attempts = AtomicInteger(0)

        val renderer = ViewportRenderer<String> { request, _ ->
            workerThread = Thread.currentThread()
            if (attempts.getAndIncrement() == 0) {
                started.countDown()
                releaseFirstAttempt.await(5, TimeUnit.SECONDS)
                throw PdfException(PdfFailure.Resource(retryable = true))
            }
            RenderCandidate("page-${request.pageIndex}") {}
        }
        lateinit var coordinator: HorizontalViewportRequestCoordinator<String>
        val delivered = CopyOnWriteArrayList<PageRenderOutcome<String>>()
        val scheduler = ViewportScheduler(
            maxConcurrentWorkers = 1,
            renderer = renderer,
            closeDrainTimeoutMillis = 200
        ) { outcome -> coordinator.onSchedulerOutcome(outcome) }
        coordinator = HorizontalViewportRequestCoordinator(scheduler, releaseValue = {}) { delivered.add(it) }

        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }

        try {
            val state = HorizontalViewportState.initial(pageCount = 1)
            coordinator.applyState(state) { spec() }
            assertTrue(started.await(5, TimeUnit.SECONDS))

            try {
                scheduler.close()
                throw AssertionError("close() must time out while the worker is still blocked in the renderer")
            } catch (expected: SchedulerCloseTimeoutException) {
                // expected: the worker is still inside render(), unable to finish before the drain deadline
            }

            releaseFirstAttempt.countDown()
            workerThread.join(5_000)
            assertTrue("worker thread never finished", !workerThread.isAlive)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }

        assertTrue(
            "resubmitting into a closed scheduler must never surface as an uncaught exception on the worker thread: $uncaught",
            uncaught.isEmpty()
        )
        assertTrue(
            "a resubmission refused only because the scheduler is closed must not surface as a page error",
            delivered.isEmpty()
        )
    }

    private fun spec(): RenderSpec = RenderSpec(width = 100, height = 100)
}
