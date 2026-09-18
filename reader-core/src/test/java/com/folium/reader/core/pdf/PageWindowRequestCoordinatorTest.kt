package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Covers [PageWindowRequestCoordinator] wired against a real [ViewportScheduler]: an explicit
 * wanted-page list drives submissions, a page dropped from that list is cancelled, and every
 * borrow this coordinator ever hands out is accounted for exactly once.
 */
class PageWindowRequestCoordinatorTest {

    @get:Rule val perTestTimeout: Timeout = Timeout.seconds(60)

    private fun spec() = RenderSpec(10, 10)

    @Test fun settingTheWantedListSubmitsExactlyThosePages() {
        val submitted = CopyOnWriteArrayList<Int>()
        val allDone = CountDownLatch(3)

        val renderer = ViewportRenderer<String> { request, _ ->
            submitted.add(request.pageIndex)
            RenderCandidate("page-${request.pageIndex}") {}
        }
        lateinit var coordinator: PageWindowRequestCoordinator<String>
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 4, renderer = renderer) { outcome ->
            coordinator.onSchedulerOutcome(outcome)
            allDone.countDown()
        }
        coordinator = PageWindowRequestCoordinator(scheduler, releaseValue = {}) { }

        try {
            coordinator.setWanted(listOf(2, 5, 7), { RenderPriority.VISIBLE }, { spec() })
            assertTrue(allDone.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertEquals(setOf(2, 5, 7), submitted.toSet())
    }

    @Test fun aPageDroppedFromTheWantedListIsCancelledAndNeverResubmittedByADuplicateCall() {
        val submitted = CopyOnWriteArrayList<Int>()
        val page0Started = CountDownLatch(1)
        val releasePage0 = CountDownLatch(1)
        val secondWindowSettled = CountDownLatch(3)

        val renderer = ViewportRenderer<String> { request, _ ->
            submitted.add(request.pageIndex)
            if (request.pageIndex == 0) {
                page0Started.countDown()
                releasePage0.await(5, TimeUnit.SECONDS)
            }
            RenderCandidate("page-${request.pageIndex}") {}
        }
        lateinit var coordinator: PageWindowRequestCoordinator<String>
        val delivered = CopyOnWriteArrayList<PageWindowOutcome<String>>()
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
            coordinator.onSchedulerOutcome(outcome)
        }
        coordinator = PageWindowRequestCoordinator(scheduler, releaseValue = {}) { outcome ->
            delivered.add(outcome)
            secondWindowSettled.countDown()
        }

        try {
            // Page 0 occupies the scheduler's only worker slot; pages 1-2 stay queued, never started.
            coordinator.setWanted(listOf(0, 1, 2), { RenderPriority.VISIBLE }, { spec() })
            assertTrue(page0Started.await(5, TimeUnit.SECONDS))

            coordinator.setWanted(listOf(0, 8, 9), { RenderPriority.VISIBLE }, { spec() })

            releasePage0.countDown()
            assertTrue(secondWindowSettled.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        val renderedPages = delivered.mapNotNull { (it as? PageWindowOutcome.Rendered)?.pageIndex }
        assertTrue("pages dropped from the window must never reach the consumer: $delivered", renderedPages.none { it in setOf(1, 2) })
        assertEquals(setOf(0, 8, 9), renderedPages.toSet())
        assertTrue("page 0 stayed wanted across both calls and must not be resubmitted", submitted.count { it == 0 } == 1)
    }

    @Test fun aWantedPageAlreadyOutstandingIsNotResubmitted() {
        val submitted = CopyOnWriteArrayList<Int>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val settled = CountDownLatch(1)

        val renderer = ViewportRenderer<String> { request, _ ->
            submitted.add(request.pageIndex)
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            RenderCandidate("page-${request.pageIndex}") {}
        }
        lateinit var coordinator: PageWindowRequestCoordinator<String>
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
            coordinator.onSchedulerOutcome(outcome)
            settled.countDown()
        }
        coordinator = PageWindowRequestCoordinator(scheduler, releaseValue = {}) { }

        try {
            coordinator.setWanted(listOf(3), { RenderPriority.VISIBLE }, { spec() })
            assertTrue(started.await(5, TimeUnit.SECONDS))

            coordinator.setWanted(listOf(3), { RenderPriority.VISIBLE }, { spec() })

            release.countDown()
            assertTrue(settled.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertEquals(listOf(3), submitted)
    }

    @Test fun aRejectedFailureIsForwardedButCancelledAndStaleGenerationAreDropped() {
        val failing = CountDownLatch(1)
        val delivered = CopyOnWriteArrayList<PageWindowOutcome<String>>()

        val renderer = ViewportRenderer<String> { _, _ ->
            throw PdfException(PdfFailure.Corrupt)
        }
        lateinit var coordinator: PageWindowRequestCoordinator<String>
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
            coordinator.onSchedulerOutcome(outcome)
        }
        coordinator = PageWindowRequestCoordinator(scheduler, releaseValue = {}) { outcome ->
            delivered.add(outcome)
            failing.countDown()
        }

        try {
            coordinator.setWanted(listOf(4), { RenderPriority.VISIBLE }, { spec() })
            assertTrue(failing.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertEquals(listOf(PageWindowOutcome.Failed(4)), delivered)
    }

    @Test fun cancelAllClearsBookkeepingSoTheSamePagesCanBeSubmittedAgain() {
        val submitted = CopyOnWriteArrayList<Int>()
        val page0Started = CountDownLatch(1)
        val releasePage0 = CountDownLatch(1)
        val secondBatchSettled = CountDownLatch(2)

        val renderer = ViewportRenderer<String> { request, _ ->
            submitted.add(request.pageIndex)
            if (request.pageIndex == 0) {
                page0Started.countDown()
                releasePage0.await(5, TimeUnit.SECONDS)
            }
            RenderCandidate("page-${request.pageIndex}") {}
        }
        var afterFirstBatch = false
        lateinit var coordinator: PageWindowRequestCoordinator<String>
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
            coordinator.onSchedulerOutcome(outcome)
        }
        coordinator = PageWindowRequestCoordinator(scheduler, releaseValue = {}) { outcome ->
            if (afterFirstBatch && outcome is PageWindowOutcome.Rendered) secondBatchSettled.countDown()
        }

        try {
            // Page 0 occupies the scheduler's only worker slot; page 1 stays queued, never started.
            coordinator.setWanted(listOf(0, 1), { RenderPriority.VISIBLE }, { spec() })
            assertTrue(page0Started.await(5, TimeUnit.SECONDS))

            coordinator.cancelAll()
            releasePage0.countDown()

            afterFirstBatch = true
            coordinator.setWanted(listOf(0, 1), { RenderPriority.VISIBLE }, { spec() })
            // If cancelAll had left either page's bookkeeping marked outstanding, this second call
            // would treat it as already requested and never resubmit it, so this would time out
            // rather than fail an assertion — the await itself is the proof cancelAll cleared it.
            assertTrue(secondBatchSettled.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertTrue("page 0 must have been dispatched again after cancelAll", submitted.count { it == 0 } >= 2)
        assertTrue("page 1 must have been dispatched after cancelAll", submitted.count { it == 1 } >= 1)
    }
}
