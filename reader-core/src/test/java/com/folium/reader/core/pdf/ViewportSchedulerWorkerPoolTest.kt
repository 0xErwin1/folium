package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Covers what changed when [ViewportScheduler] stopped creating one OS thread per request and
 * started dispatching onto a fixed, per-instance worker pool: the pool's own refusal path, the
 * pool's queue as a new place a dispatched request can be sitting when [ViewportScheduler.close]
 * runs, and the reuse bound that is the whole point of the change.
 *
 * Every latch that blocks a worker here is given a timeout that dominates the observation timeouts
 * by a wide margin, so a green result can never mean "the observer stopped watching first"
 * (`folium/probe-timeout-scaling`).
 */
class ViewportSchedulerWorkerPoolTest {

    /**
     * The pool refuses work with [RejectedExecutionException] where the previous implementation
     * failed with a thread-creation [Error]. Both must take the identical rollback path and settle
     * the *entire* remaining backlog as retryable, per `folium/scheduler-retryable-contract` — not
     * just the request whose dispatch was refused, which would leave the rest of the queue with no
     * internal trigger to ever run.
     *
     * The backlog is built while the only worker slot is occupied, and the refusal is armed only
     * once four requests are genuinely queued, so this exercises the drain rather than a sequence of
     * independent single-request refusals.
     */
    @Test fun executorRejectionSettlesTheEntireRemainingBacklogAsRetryable() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val allSettled = CountDownLatch(5)
        val outcomes = CopyOnWriteArrayList<SchedulerOutcome<String>>()

        val renderer = ViewportRenderer<String> { request, _ ->
            if (request.pageIndex == 0) {
                firstStarted.countDown()
                releaseFirst.await(60, TimeUnit.SECONDS)
            }
            RenderCandidate("page-${request.pageIndex}") {}
        }
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
            outcomes.add(outcome)
            allSettled.countDown()
        }

        try {
            scheduler.submit(0, RenderPriority.VISIBLE, spec())
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

            repeat(4) { index -> scheduler.submit(index + 1, RenderPriority.NEAR, spec()) }
            assertEquals(4, scheduler.pendingCount())

            scheduler.workerDispatchHookForTests = { throw RejectedExecutionException("pool refused the task") }
            releaseFirst.countDown()

            assertTrue(allSettled.await(5, TimeUnit.SECONDS))
        } finally {
            releaseFirst.countDown()
            scheduler.workerDispatchHookForTests = null
            scheduler.close()
        }

        val rendered = outcomes.filterIsInstance<SchedulerOutcome.Rendered<String>>()
        assertEquals(listOf(0), rendered.map { it.request.pageIndex })

        val rejected = outcomes.filterIsInstance<SchedulerOutcome.Rejected>()
        assertEquals(setOf(1, 2, 3, 4), rejected.map { it.request.pageIndex }.toSet())
        assertTrue(
            "every request left behind by a refused dispatch must be retryable, not a terminal error: $rejected",
            rejected.all { it.reason == RejectionReason.FAILED && it.failure == PdfFailure.Resource(retryable = true) }
        )
        assertEquals(0, scheduler.pendingCount())
    }

    /**
     * A pool introduces a state the previous thread-per-request scheduler could not reach: a request
     * that has been dispatched — so it holds a worker slot and sits in the drain set — while no pool
     * thread has picked it up yet, because every pool thread is still publishing an earlier outcome.
     *
     * [ViewportScheduler.close] must settle such a request itself rather than wait for it. Waiting
     * would be a cycle in the worst case (a consumer callback that reenters close occupies the very
     * thread the queued task needs) and a pointless timeout in this one. The settlement is asserted
     * to arrive *while* the pool thread is still blocked, which is what distinguishes eviction from
     * merely waiting long enough.
     */
    @Test fun closeSettlesADispatchedRequestNoPoolThreadHasStartedYet() {
        val rendered = CopyOnWriteArrayList<Int>()
        val firstStarted = CountDownLatch(1)
        val releaseRender = CountDownLatch(1)
        val consumerBlocked = CountDownLatch(1)
        val releaseConsumer = CountDownLatch(1)
        val secondSettled = CountDownLatch(1)
        val outcomes = CopyOnWriteArrayList<SchedulerOutcome<String>>()

        val renderer = ViewportRenderer<String> { request, _ ->
            rendered.add(request.pageIndex)
            if (request.pageIndex == 0) {
                firstStarted.countDown()
                releaseRender.await(60, TimeUnit.SECONDS)
            }
            RenderCandidate("page-${request.pageIndex}") {}
        }
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
            outcomes.add(outcome)
            when (pageOf(outcome)) {
                0 -> {
                    consumerBlocked.countDown()
                    releaseConsumer.await(60, TimeUnit.SECONDS)
                }

                1 -> secondSettled.countDown()
            }
        }

        val closeError = CopyOnWriteArrayList<Throwable>()
        val closer = Thread({
            try {
                scheduler.close()
            } catch (error: Throwable) {
                closeError.add(error)
            }
        }, "scheduler-closer")

        try {
            scheduler.submit(0, RenderPriority.VISIBLE, spec())
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

            // Queued behind the only worker slot, so it is dispatched onto the pool exactly when the
            // first request frees that slot -- while the single pool thread is still publishing.
            scheduler.submit(1, RenderPriority.NEAR, spec())
            releaseRender.countDown()
            assertTrue(consumerBlocked.await(5, TimeUnit.SECONDS))

            closer.start()
            assertTrue(
                "close() must settle a request the pool has not started rather than wait for a thread that cannot free up",
                secondSettled.await(5, TimeUnit.SECONDS)
            )
        } finally {
            releaseRender.countDown()
            releaseConsumer.countDown()
            closer.join(30_000)
        }

        assertTrue("close() thread never finished", !closer.isAlive)
        assertTrue("close() must drain cleanly once the blocked consumer returns: $closeError", closeError.isEmpty())
        assertEquals("the evicted request must never reach the renderer", listOf(0), rendered)

        val secondOutcome = outcomes.filterIsInstance<SchedulerOutcome.Rejected>().single { it.request.pageIndex == 1 }
        assertEquals(RejectionReason.CANCELLED, secondOutcome.reason)
        assertEquals(0, scheduler.drainingCount())
    }

    /**
     * The closed check must stay strictly in front of any interaction with the pool: once
     * [ViewportScheduler.close] has shut the pool down, a late [ViewportScheduler.submit] would
     * otherwise surface the pool's own [RejectedExecutionException] instead of the
     * [SchedulerClosedException] that [HorizontalViewportRequestCoordinator.submitForPage] catches
     * by type. This exercises it after the pool has really been used, so the shutdown is of a live
     * pool with materialized threads rather than of one that was never touched.
     */
    @Test fun submitAfterCloseStillThrowsSchedulerClosedExceptionOnceThePoolHasBeenShutDown() {
        val settled = CountDownLatch(1)
        val renderer = ViewportRenderer<String> { request, _ -> RenderCandidate("page-${request.pageIndex}") {} }
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 2, renderer = renderer) { settled.countDown() }

        scheduler.submit(0, RenderPriority.VISIBLE, spec())
        assertTrue(settled.await(5, TimeUnit.SECONDS))
        scheduler.close()

        try {
            scheduler.submit(1, RenderPriority.VISIBLE, spec())
            throw AssertionError("submit after close must throw")
        } catch (expected: SchedulerClosedException) {
            // expected: refused by the scheduler itself, never by the pool underneath it
        }
    }

    /**
     * The defect this change exists to fix: the scheduler used to construct and start one OS thread
     * per request, synchronously on the submitting thread, which in the reader is the UI thread on
     * every pointer sample. A fixed pool must serve an arbitrary number of requests from at most
     * [ViewportScheduler] `maxConcurrentWorkers` threads, reusing them.
     *
     * The thread names are asserted too: they are the documented debugging handle for this pool, and
     * [HorizontalViewportRequestCoordinator] names them in its own contract.
     */
    @Test fun anArbitraryNumberOfRequestsIsServedByAtMostTheConfiguredNumberOfPooledThreads() {
        val bound = 2
        val submissions = 200
        val servingThreads = CopyOnWriteArrayList<String>()
        val allSettled = CountDownLatch(submissions)

        val renderer = ViewportRenderer<String> { request, _ ->
            val name = Thread.currentThread().name
            if (name !in servingThreads) servingThreads.addIfAbsent(name)
            RenderCandidate("page-${request.pageIndex}") {}
        }
        val scheduler = ViewportScheduler(bound, renderer) { allSettled.countDown() }

        try {
            repeat(submissions) { index -> scheduler.submit(index, RenderPriority.VISIBLE, spec()) }
            assertTrue(allSettled.await(10, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertTrue(
            "$submissions requests must never be served by more than $bound threads, saw $servingThreads",
            servingThreads.size <= bound
        )
        assertTrue(
            "pooled worker threads must stay identifiable in a stack dump, saw $servingThreads",
            servingThreads.all { Regex("^viewport-render-\\d+$").matches(it) }
        )
    }

    private fun pageOf(outcome: SchedulerOutcome<*>): Int = when (outcome) {
        is SchedulerOutcome.Rendered -> outcome.request.pageIndex
        is SchedulerOutcome.Rejected -> outcome.request.pageIndex
    }
}
