package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val RACE_ITERATIONS = 500
private const val CANCEL_STORM_ITERATIONS = 500

/**
 * Scheduler-level cancellation-draining coverage, added alongside the existing
 * [RenderCancellationTest] (which covers [RenderPublicationPolicy] in isolation, from RCO-004).
 * This file exercises the same guarantee one layer up, through [ViewportScheduler].
 */
class ViewportSchedulerCancellationTest {

    @Test fun cancellingAQueuedRequestRejectsItWithoutEverRendering() {
        val everRendered = AtomicInteger(0)
        val started = CountDownLatch(1)
        val holdBlocker = CountDownLatch(1)
        val outcomes = CountDownLatch(2)
        var queuedOutcome: SchedulerOutcome<String>? = null

        val renderer = ViewportRenderer<String> { request, _ ->
            if (request.pageIndex == 0) {
                started.countDown()
                holdBlocker.await(5, TimeUnit.SECONDS)
            } else {
                everRendered.incrementAndGet()
            }
            RenderCandidate("page-${request.pageIndex}") {}
        }

        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) {
            if (it is SchedulerOutcome.Rejected && it.request.pageIndex == 1) queuedOutcome = it
            outcomes.countDown()
        }
        try {
            scheduler.submit(0, RenderPriority.VISIBLE, spec())
            assertTrue(started.await(5, TimeUnit.SECONDS))

            val queuedHandle = scheduler.submit(1, RenderPriority.NEAR, spec())
            scheduler.cancel(queuedHandle)

            holdBlocker.countDown()
            assertTrue(outcomes.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertEquals(0, everRendered.get())
        assertEquals(RejectionReason.CANCELLED, (queuedOutcome as SchedulerOutcome.Rejected).reason)
    }

    @Test fun cancellingAnInFlightRequestReleasesItsHandleExactlyOnce() {
        val openHandles = AtomicInteger(0)
        val started = CountDownLatch(1)
        val outcomes = CountDownLatch(1)
        var lastOutcome: SchedulerOutcome<String>? = null

        val renderer = ViewportRenderer<String> { request, cancellationSignal ->
            started.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!cancellationSignal.isCancelled() && System.nanoTime() < deadline) {
                Thread.onSpinWait()
            }
            openHandles.incrementAndGet()
            RenderCandidate("page-${request.pageIndex}") { openHandles.decrementAndGet() }
        }

        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) {
            lastOutcome = it
            outcomes.countDown()
        }
        try {
            val handle = scheduler.submit(0, RenderPriority.VISIBLE, spec())
            assertTrue(started.await(5, TimeUnit.SECONDS))

            scheduler.cancel(handle)
            assertTrue(outcomes.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertEquals(RejectionReason.CANCELLED, (lastOutcome as SchedulerOutcome.Rejected).reason)
        assertEquals(0, openHandles.get())
    }

    @Test fun closeDrainsAllOutstandingWorkAndLeaksNoHandle() {
        val openHandles = AtomicInteger(0)
        val started = CountDownLatch(2)
        val outcomes = CountDownLatch(3)

        val renderer = ViewportRenderer<String> { request, cancellationSignal ->
            started.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!cancellationSignal.isCancelled() && System.nanoTime() < deadline) {
                Thread.onSpinWait()
            }
            openHandles.incrementAndGet()
            RenderCandidate("page-${request.pageIndex}") { openHandles.decrementAndGet() }
        }

        val scheduler = ViewportScheduler(maxConcurrentWorkers = 2, renderer = renderer) { outcomes.countDown() }
        scheduler.submit(0, RenderPriority.VISIBLE, spec())
        scheduler.submit(1, RenderPriority.NEAR, spec())
        scheduler.submit(2, RenderPriority.PREFETCH, spec())
        assertTrue(started.await(5, TimeUnit.SECONDS))

        scheduler.close()

        // No intervening await here on purpose: close() itself must already guarantee every
        // candidate was released and every outcome published by the time it returns, without
        // needing the test to wait for a side-effect latch to mask a late worker.
        assertEquals(0, openHandles.get())

        assertTrue(outcomes.await(1, TimeUnit.SECONDS))
        assertEquals(0, openHandles.get())
    }

    /**
     * Constructs the exact hazard documented on [ViewportScheduler.close]'s KDoc: a thread that
     * holds a lock the outcome callback needs, then calls [ViewportScheduler.close] itself, rather
     * than the reentrant-from-within-the-callback case [consumerCallbackReenteringCloseDoesNotDeadlock]
     * already covers. Without a bounded join this would hang forever; with it, [close] must throw
     * [SchedulerCloseTimeoutException] rather than return silently with the worker still draining.
     */
    @Test fun closeThrowsSchedulerCloseTimeoutExceptionWhenAWorkerCannotFinishPublishingInTime() {
        val consumerLock = Object()
        val started = CountDownLatch(1)
        val callbackDelivered = CountDownLatch(1)

        val renderer = ViewportRenderer<String> { request, _ ->
            started.countDown()
            RenderCandidate("page-${request.pageIndex}") {}
        }

        val scheduler = ViewportScheduler(
            maxConcurrentWorkers = 1,
            renderer = renderer,
            closeDrainTimeoutMillis = 200
        ) {
            synchronized(consumerLock) {}
            callbackDelivered.countDown()
        }

        var error: SchedulerCloseTimeoutException? = null
        synchronized(consumerLock) {
            scheduler.submit(0, RenderPriority.VISIBLE, spec())
            assertTrue(started.await(5, TimeUnit.SECONDS))

            try {
                scheduler.close()
                throw AssertionError("close() must not return successfully while a worker cannot finish publishing")
            } catch (expected: SchedulerCloseTimeoutException) {
                error = expected
            }
        }

        assertTrue(callbackDelivered.await(5, TimeUnit.SECONDS))
        assertEquals(1, error!!.stillDrainingCount)
        assertEquals(1, error!!.totalAwaitingDrain)
    }

    /**
     * Reproduces the interaction between a misbehaving consumer callback and a concurrent drain
     * timeout: request 1 stays genuinely queued (never dispatched, since request 0 permanently
     * occupies the only worker slot), so [ViewportScheduler.close] drains it synchronously through
     * its own `publish` call and observes the callback's exception directly, while request 0's
     * worker thread is independently and separately still blocked and cannot finish in time. Both
     * conditions are real, not simulated, and [SchedulerCloseTimeoutException] must not discard the
     * callback's exception when it also fires.
     */
    @Test fun closeCapturesAConsumerExceptionAsSuppressedWhenTheDrainAlsoTimesOut() {
        val started = CountDownLatch(1)
        val neverReleased = CountDownLatch(1)

        val renderer = ViewportRenderer<String> { request, _ ->
            started.countDown()
            neverReleased.await(5, TimeUnit.SECONDS)
            RenderCandidate("page-${request.pageIndex}") {}
        }

        val scheduler = ViewportScheduler(
            maxConcurrentWorkers = 1,
            renderer = renderer,
            closeDrainTimeoutMillis = 200
        ) { outcome ->
            if (outcome is SchedulerOutcome.Rejected && outcome.request.pageIndex == 1) {
                throw IllegalStateException("consumer blew up on the queued rejection")
            }
        }

        try {
            scheduler.submit(0, RenderPriority.VISIBLE, spec())
            assertTrue(started.await(5, TimeUnit.SECONDS))
            scheduler.submit(1, RenderPriority.NEAR, spec())
            assertEquals(1, scheduler.pendingCount())

            val error = try {
                scheduler.close()
                throw AssertionError("close() must not return successfully while a worker cannot finish publishing")
            } catch (expected: SchedulerCloseTimeoutException) {
                expected
            }

            assertEquals(1, error.suppressed.size)
            assertEquals("consumer blew up on the queued rejection", error.suppressed[0].message)
        } finally {
            neverReleased.countDown()
        }
    }

    @Test fun submitAfterCloseIsRejectedRatherThanSilentlyDropped() {
        val renderer = ViewportRenderer<String> { request, _ -> RenderCandidate("page-${request.pageIndex}") {} }
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) {}
        scheduler.close()

        try {
            scheduler.submit(0, RenderPriority.VISIBLE, spec())
            throw AssertionError("submit after close must throw")
        } catch (expected: IllegalStateException) {
            // expected: scheduler refuses new work once closed
        }
    }

    /**
     * Forces the "cancel wins" ordering deterministically rather than sampling for it:
     * [ViewportScheduler.cancel] is a plain synchronous call, so by the time it returns,
     * `cancelledIds` already contains this request's id under [lock]. Only after `cancel()`
     * returns is the renderer released to reach its own synchronized decision block, so that
     * block is guaranteed to observe the cancellation -- there is no window in which it could
     * race it and lose.
     */
    @Test fun cancelWinningTheRaceAgainstNaturalCompletionRejectsWithCancelled() {
        val readyToRace = CountDownLatch(1)
        val releaseRenderer = CountDownLatch(1)
        val outcomeLatch = CountDownLatch(1)
        var lastOutcome: SchedulerOutcome<String>? = null

        val renderer = ViewportRenderer<String> { request, _ ->
            readyToRace.countDown()
            releaseRenderer.await(5, TimeUnit.SECONDS)
            RenderCandidate("page-${request.pageIndex}") {}
        }

        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
            lastOutcome = outcome
            outcomeLatch.countDown()
        }
        try {
            val handle = scheduler.submit(0, RenderPriority.VISIBLE, spec())
            assertTrue(readyToRace.await(5, TimeUnit.SECONDS))

            scheduler.cancel(handle)
            releaseRenderer.countDown()

            assertTrue(outcomeLatch.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        val outcome = lastOutcome as SchedulerOutcome.Rejected
        assertEquals(RejectionReason.CANCELLED, outcome.reason)
    }

    /**
     * Forces the "natural completion wins" ordering deterministically: waiting for the outcome
     * latch before calling [ViewportScheduler.cancel] guarantees the worker's synchronized
     * decision block already ran and removed the request from `inFlight`, so the `cancel()` call
     * below is deterministically a no-op arriving strictly after the decision was made, rather
     * than racing it.
     */
    @Test fun naturalCompletionWinningTheRaceAgainstALateCancelRendersSuccessfully() {
        val outcomeLatch = CountDownLatch(1)
        var lastOutcome: SchedulerOutcome<String>? = null

        val renderer = ViewportRenderer<String> { request, _ -> RenderCandidate("page-${request.pageIndex}") {} }

        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
            lastOutcome = outcome
            outcomeLatch.countDown()
        }
        try {
            val handle = scheduler.submit(0, RenderPriority.VISIBLE, spec())

            assertTrue(outcomeLatch.await(5, TimeUnit.SECONDS))
            scheduler.cancel(handle)
        } finally {
            scheduler.close()
        }

        val outcome = lastOutcome as SchedulerOutcome.Rendered
        assertEquals(0, outcome.request.pageIndex)
    }

    /**
     * Regression/stress companion to the two deterministic ordering tests above: both threads are
     * released from a shared [CyclicBarrier] so which one reaches the monitor first is genuinely
     * undetermined each iteration. Unlike the replaced test this asserts only the invariant that
     * must hold regardless of which ordering wins -- exactly one outcome, ever -- and never
     * asserts on the distribution between cancellation and natural completion, since that
     * distribution is not equalized by the barrier (measured at roughly 99% cancel / 1% natural
     * completion) and asserting on it made the previous version of this test flaky.
     */
    @Test fun concurrentCancelAndNaturalCompletionAlwaysProduceExactlyOneOutcome() {
        repeat(RACE_ITERATIONS) {
            val outcomeCount = AtomicInteger(0)
            val outcomeLatch = CountDownLatch(1)
            val readyToRace = CountDownLatch(1)
            val releaseRenderer = CountDownLatch(1)
            val barrier = CyclicBarrier(2)
            var lastOutcome: SchedulerOutcome<String>? = null

            val renderer = ViewportRenderer<String> { request, _ ->
                readyToRace.countDown()
                releaseRenderer.await(5, TimeUnit.SECONDS)
                RenderCandidate("page-${request.pageIndex}") {}
            }

            val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
                lastOutcome = outcome
                outcomeCount.incrementAndGet()
                outcomeLatch.countDown()
            }
            val racer = Executors.newSingleThreadExecutor()
            try {
                val handle = scheduler.submit(0, RenderPriority.VISIBLE, spec())
                assertTrue(readyToRace.await(5, TimeUnit.SECONDS))

                racer.execute {
                    barrier.await(5, TimeUnit.SECONDS)
                    scheduler.cancel(handle)
                }
                barrier.await(5, TimeUnit.SECONDS)
                releaseRenderer.countDown()

                racer.shutdown()
                assertTrue(racer.awaitTermination(5, TimeUnit.SECONDS))
                assertTrue(outcomeLatch.await(5, TimeUnit.SECONDS))
            } finally {
                scheduler.close()
                racer.shutdownNow()
            }

            assertEquals(1, outcomeCount.get())
            when (val outcome = lastOutcome) {
                is SchedulerOutcome.Rendered -> Unit
                is SchedulerOutcome.Rejected -> assertEquals(RejectionReason.CANCELLED, outcome.reason)
                null -> throw AssertionError("an outcome must have been delivered before close() returned")
            }
        }
    }

    @Test fun cancelledIdsSetStaysBoundedByMaxConcurrentWorkersAcrossALongCancelStorm() {
        val bound = 3
        val openHandles = AtomicInteger(0)
        var maxCancelledObserved = 0

        val renderer = ViewportRenderer<String> { request, cancellationSignal ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!cancellationSignal.isCancelled() && System.nanoTime() < deadline) {
                Thread.onSpinWait()
            }
            openHandles.incrementAndGet()
            RenderCandidate("page-${request.pageIndex}") { openHandles.decrementAndGet() }
        }

        val scheduler = ViewportScheduler(maxConcurrentWorkers = bound, renderer = renderer) {}
        try {
            repeat(CANCEL_STORM_ITERATIONS) { iteration ->
                repeat(bound + 2) { index -> scheduler.submit(index, RenderPriority.NEAR, spec()) }

                maxCancelledObserved = maxOf(maxCancelledObserved, scheduler.cancelledCount())
                scheduler.cancelAll()

                assertTrue(
                    "cancelledIds must never exceed maxConcurrentWorkers (bound=$bound), " +
                        "was ${scheduler.cancelledCount()} at iteration $iteration",
                    scheduler.cancelledCount() <= bound
                )
            }
        } finally {
            scheduler.close()
        }

        assertTrue("expected the storm to actually populate cancelledIds at least once", maxCancelledObserved > 0)
        assertEquals(0, openHandles.get())
    }

    @Test fun cancelAllDrainsQueuedWorkAndMarksInFlightWorkForCancellation() {
        val everRendered = AtomicInteger(0)
        val openHandles = AtomicInteger(0)
        val started = CountDownLatch(1)
        val outcomes = CountDownLatch(3)
        val rejectedReasons = java.util.concurrent.CopyOnWriteArrayList<RejectionReason>()

        val renderer = ViewportRenderer<String> { request, cancellationSignal ->
            if (request.pageIndex == 0) {
                started.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!cancellationSignal.isCancelled() && System.nanoTime() < deadline) {
                    Thread.onSpinWait()
                }
            } else {
                everRendered.incrementAndGet()
            }
            openHandles.incrementAndGet()
            RenderCandidate("page-${request.pageIndex}") { openHandles.decrementAndGet() }
        }

        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) {
            if (it is SchedulerOutcome.Rejected) rejectedReasons.add(it.reason)
            outcomes.countDown()
        }
        try {
            assertEquals(0L, scheduler.generation())

            scheduler.submit(0, RenderPriority.VISIBLE, spec())
            assertTrue(started.await(5, TimeUnit.SECONDS))

            scheduler.submit(1, RenderPriority.NEAR, spec())
            scheduler.submit(2, RenderPriority.PREFETCH, spec())
            assertEquals(2, scheduler.pendingCount())

            scheduler.cancelAll()
            assertEquals(0, scheduler.pendingCount())

            assertTrue(outcomes.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertEquals(0, everRendered.get())
        assertEquals(0, openHandles.get())
        assertEquals(listOf(RejectionReason.CANCELLED, RejectionReason.CANCELLED, RejectionReason.CANCELLED), rejectedReasons.sorted())
    }
}
