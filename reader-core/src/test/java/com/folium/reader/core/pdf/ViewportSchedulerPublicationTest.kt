package com.folium.reader.core.pdf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Proves outcome publication and resource release happen outside the scheduler's internal monitor:
 * a slow or reentrant consumer callback must never stall cancellation polling for unrelated in-flight
 * work, and must never deadlock [ViewportScheduler.close].
 */
class ViewportSchedulerPublicationTest {

    @Test fun slowConsumerCallbackDoesNotStallCancellationOfOtherInFlightWork() {
        val requestAStarted = CountDownLatch(1)
        val consumerBBlocking = CountDownLatch(1)
        val requestBOutcomeLatch = CountDownLatch(1)
        val requestACancelled = CountDownLatch(1)
        val outcomes = CountDownLatch(2)

        val renderer = ViewportRenderer<String> { request, cancellationSignal ->
            if (request.pageIndex == 0) {
                requestAStarted.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!cancellationSignal.isCancelled() && System.nanoTime() < deadline) {
                    Thread.onSpinWait()
                }
                if (cancellationSignal.isCancelled()) requestACancelled.countDown()
            }
            RenderCandidate("page-${request.pageIndex}") {}
        }

        val scheduler = ViewportScheduler(maxConcurrentWorkers = 2, renderer = renderer) { outcome ->
            val pageIndex = when (outcome) {
                is SchedulerOutcome.Rendered -> outcome.request.pageIndex
                is SchedulerOutcome.Rejected -> outcome.request.pageIndex
            }
            if (pageIndex == 1) {
                consumerBBlocking.countDown()
                requestBOutcomeLatch.await(5, TimeUnit.SECONDS)
            }
            outcomes.countDown()
        }
        try {
            val handleA = scheduler.submit(0, RenderPriority.VISIBLE, spec())
            assertTrue(requestAStarted.await(5, TimeUnit.SECONDS))

            scheduler.submit(1, RenderPriority.VISIBLE, spec())
            assertTrue(
                "slow consumer callback for B must have started before we try to cancel A",
                consumerBBlocking.await(5, TimeUnit.SECONDS)
            )

            scheduler.cancel(handleA)
            assertTrue(
                "cancelling A must not be stalled by B's blocked consumer callback",
                requestACancelled.await(5, TimeUnit.SECONDS)
            )

            requestBOutcomeLatch.countDown()
            assertTrue(outcomes.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }
    }

    @Test fun consumerCallbackReenteringCloseDoesNotDeadlock() {
        val callbackReturned = CountDownLatch(1)
        val renderer = ViewportRenderer<String> { request, _ -> RenderCandidate("page-${request.pageIndex}") {} }

        lateinit var scheduler: ViewportScheduler<String>
        scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) {
            scheduler.close()
            callbackReturned.countDown()
        }

        scheduler.submit(0, RenderPriority.VISIBLE, spec())

        assertTrue(
            "consumer callback re-entering close() from the worker thread that produced the outcome must not deadlock",
            callbackReturned.await(5, TimeUnit.SECONDS)
        )
    }
}
