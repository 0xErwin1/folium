package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ViewportSchedulerTest {

    @Test fun visibleWorkOutranksNearPrefetchAndOcr() {
        val started = CopyOnWriteArrayList<RenderPriority>()
        val holdFirst = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val allDone = CountDownLatch(5)

        val renderer = ViewportRenderer<String> { request, _ ->
            if (request.priority == RenderPriority.OCR && started.isEmpty()) {
                started.add(request.priority)
                firstStarted.countDown()
                holdFirst.await(5, TimeUnit.SECONDS)
            } else {
                started.add(request.priority)
            }
            RenderCandidate("page-${request.pageIndex}") {}
        }

        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { allDone.countDown() }
        try {
            scheduler.submit(0, RenderPriority.OCR, spec())
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

            scheduler.submit(1, RenderPriority.OCR, spec())
            scheduler.submit(2, RenderPriority.PREFETCH, spec())
            scheduler.submit(3, RenderPriority.NEAR, spec())
            scheduler.submit(4, RenderPriority.VISIBLE, spec())

            holdFirst.countDown()
            assertTrue(allDone.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertEquals(
            listOf(RenderPriority.OCR, RenderPriority.VISIBLE, RenderPriority.NEAR, RenderPriority.PREFETCH, RenderPriority.OCR),
            started
        )
    }

    @Test fun boundedWorkersNeverExceedTheConfiguredLimit() {
        val bound = 2
        val activeNow = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val readyToProceed = CountDownLatch(bound)
        val releaseAll = CountDownLatch(1)
        val outcomes = CountDownLatch(3)

        val renderer = ViewportRenderer<String> { request, _ ->
            val nowActive = activeNow.incrementAndGet()
            maxObserved.updateAndGet { current -> maxOf(current, nowActive) }
            readyToProceed.countDown()
            releaseAll.await(5, TimeUnit.SECONDS)
            activeNow.decrementAndGet()
            RenderCandidate("page-${request.pageIndex}") {}
        }

        val scheduler = ViewportScheduler(bound, renderer) { outcomes.countDown() }
        try {
            repeat(3) { index -> scheduler.submit(index, RenderPriority.VISIBLE, spec()) }

            assertTrue(readyToProceed.await(5, TimeUnit.SECONDS))
            assertEquals(bound, maxObserved.get())
            assertTrue(activeNow.get() <= bound)

            releaseAll.countDown()
            assertTrue(outcomes.await(5, TimeUnit.SECONDS))
            assertEquals(bound, maxObserved.get())
        } finally {
            scheduler.close()
        }
    }

    @Test fun rapidSubmissionsAcrossPrioritiesResolveDeterministically() {
        val outcomes = CopyOnWriteArrayList<SchedulerOutcome<String>>()
        val allDone = CountDownLatch(20)

        val renderer = ViewportRenderer<String> { request, _ -> RenderCandidate("page-${request.pageIndex}") {} }
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 3, renderer = renderer) {
            outcomes.add(it)
            allDone.countDown()
        }
        try {
            repeat(20) { index ->
                val priority = RenderPriority.entries[index % RenderPriority.entries.size]
                scheduler.submit(index, priority, spec())
            }
            assertTrue(allDone.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        assertEquals(20, outcomes.size)
        assertTrue(outcomes.all { it is SchedulerOutcome.Rendered })
    }
}

internal fun spec(): RenderSpec = RenderSpec(width = 100, height = 100)
