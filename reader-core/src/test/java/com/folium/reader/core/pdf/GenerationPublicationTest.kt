package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GenerationPublicationTest {

    /**
     * A regression on any of these paths shows up as an unbounded hang rather than as a failed
     * assertion, and a wedged suite costs far more than a red build (`folium/probe-timeout-scaling`).
     * The bound dominates every observation timeout in this class by a wide margin, so it can only
     * ever fire on a genuine hang.
     */
    @get:Rule val perTestTimeout: Timeout = Timeout.seconds(60)

    @Test fun inFlightResultFromASupersededGenerationIsRejectedAndReleased() {
        val openHandles = AtomicInteger(0)
        val started = CountDownLatch(1)
        val holdRender = CountDownLatch(1)
        val outcomes = CountDownLatch(1)
        var lastOutcome: SchedulerOutcome<String>? = null

        val renderer = ViewportRenderer<String> { request, _ ->
            started.countDown()
            holdRender.await(5, TimeUnit.SECONDS)
            openHandles.incrementAndGet()
            RenderCandidate("page-${request.pageIndex}") { openHandles.decrementAndGet() }
        }

        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) {
            lastOutcome = it
            outcomes.countDown()
        }
        try {
            scheduler.submit(0, RenderPriority.VISIBLE, spec())
            assertTrue(started.await(5, TimeUnit.SECONDS))

            scheduler.advanceGeneration()
            holdRender.countDown()

            assertTrue(outcomes.await(5, TimeUnit.SECONDS))
            val outcome = lastOutcome as SchedulerOutcome.Rejected
            assertEquals(RejectionReason.STALE_GENERATION, outcome.reason)
        } finally {
            scheduler.close()
        }

        // The outcome callback fires before candidate.release() (F2: publish before release, so
        // a throwing release() cannot strand other already-decided outcomes), so this assertion
        // must come after close() rather than right after the outcome latch — close() is the
        // guarantee that release has actually happened by the time it returns.
        assertEquals(0, openHandles.get())
    }

    @Test fun queuedRequestFromASupersededGenerationIsRejectedWithoutEverRendering() {
        val everRendered = AtomicInteger(0)
        val started = CountDownLatch(1)
        val holdBlocker = CountDownLatch(1)
        val outcomes = CountDownLatch(2)
        val outcomeReasons = mutableListOf<RejectionReason>()

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
            if (it is SchedulerOutcome.Rejected) synchronized(outcomeReasons) { outcomeReasons.add(it.reason) }
            outcomes.countDown()
        }
        try {
            scheduler.submit(0, RenderPriority.VISIBLE, spec())
            assertTrue(started.await(5, TimeUnit.SECONDS))

            scheduler.submit(1, RenderPriority.NEAR, spec())
            scheduler.advanceGeneration()

            holdBlocker.countDown()
            assertTrue(outcomes.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertEquals(0, everRendered.get())
        assertEquals(listOf(RejectionReason.STALE_GENERATION, RejectionReason.STALE_GENERATION), outcomeReasons.sorted())
    }

    @Test fun requestsSubmittedAfterAdvanceCarryTheNewGenerationAndPublishNormally() {
        val outcomes = CountDownLatch(1)
        var lastOutcome: SchedulerOutcome<String>? = null

        val renderer = ViewportRenderer<String> { request, _ -> RenderCandidate("page-${request.pageIndex}") {} }
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) {
            lastOutcome = it
            outcomes.countDown()
        }
        try {
            val newGeneration = scheduler.advanceGeneration()
            scheduler.submit(0, RenderPriority.VISIBLE, spec())

            assertTrue(outcomes.await(5, TimeUnit.SECONDS))
            val outcome = lastOutcome as SchedulerOutcome.Rendered<String>
            assertEquals(newGeneration, outcome.request.generation)
            assertFalse(outcome.request.generation == 0L)
            assertEquals(newGeneration, scheduler.generation())
        } finally {
            scheduler.close()
        }
    }

    /**
     * Criterion 4 ("rapid state changes are deterministic") names repeated generation advances
     * interleaved with in-flight work as the production scenario, not repeated submissions alone.
     * A single worker stays occupied by a blocked request for the whole test, so every submit/advance
     * pair below is resolved synchronously on the calling thread with no reliance on scheduling order.
     */
    @Test fun repeatedGenerationAdvancesInterleavedWithInFlightWorkAreDeterministic() {
        val advanceCount = 20
        val started = CountDownLatch(1)
        val holdBlocker = CountDownLatch(1)
        val outcomes = CountDownLatch(advanceCount + 2)
        val renderedGenerations = CopyOnWriteArrayList<Long>()
        val rejectedReasons = CopyOnWriteArrayList<RejectionReason>()

        val renderer = ViewportRenderer<String> { request, _ ->
            if (request.pageIndex == 0) {
                started.countDown()
                holdBlocker.await(5, TimeUnit.SECONDS)
            }
            RenderCandidate("page-${request.pageIndex}") {}
        }

        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
            when (outcome) {
                is SchedulerOutcome.Rendered -> renderedGenerations.add(outcome.request.generation)
                is SchedulerOutcome.Rejected -> rejectedReasons.add(outcome.reason)
            }
            outcomes.countDown()
        }
        try {
            scheduler.submit(0, RenderPriority.VISIBLE, spec())
            assertTrue(started.await(5, TimeUnit.SECONDS))

            repeat(advanceCount) { index ->
                scheduler.submit(index + 1, RenderPriority.NEAR, spec())
                scheduler.advanceGeneration()
            }

            val finalGeneration = scheduler.advanceGeneration()
            scheduler.submit(999, RenderPriority.VISIBLE, spec())

            holdBlocker.countDown()
            assertTrue(outcomes.await(5, TimeUnit.SECONDS))

            assertEquals(listOf(finalGeneration), renderedGenerations)
            assertEquals(advanceCount + 1, rejectedReasons.size)
            assertTrue(rejectedReasons.all { it == RejectionReason.STALE_GENERATION })
        } finally {
            scheduler.close()
        }
    }
}
