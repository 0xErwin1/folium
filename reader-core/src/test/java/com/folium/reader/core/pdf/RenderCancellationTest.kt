package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RenderCancellationTest {
    @Test fun cancelledCandidateIsReleasedAndNeverPublished() {
        val policy = RenderPublicationPolicy()
        val work = policy.openSession("corpus-a")
        var released = 0
        var published = 0

        work.cancel()
        val decision = policy.publish(work, candidate("raster") { released++ }) { published++ }

        assertEquals(RenderPublicationDecision.RejectedCancelled, decision)
        assertEquals(1, released)
        assertEquals(0, published)
        assertFalse(policy.isCurrent(work))
    }

    @Test fun reopeningSameSourceInvalidatesPriorSession() {
        val policy = RenderPublicationPolicy()
        val oldWork = policy.openSession("corpus-a")
        val newWork = policy.openSession("corpus-a")
        var released = 0
        var published = 0

        assertEquals(RenderPublicationDecision.RejectedStale, policy.publish(oldWork, candidate(Unit) { released++ }) { published++ })
        assertEquals(RenderPublicationDecision.Published, policy.publish(newWork, candidate(Unit) { released++ }) { published++ })
        assertEquals(1, released)
        assertEquals(1, published)
        assertFalse(policy.isCurrent(oldWork))
        assertTrue(policy.isCurrent(newWork))
    }

    @Test fun duplicateCandidateIsReleasedExactlyOnceAfterAcceptedPublication() {
        val policy = RenderPublicationPolicy()
        val work = policy.openSession("corpus-a")
        var acceptedReleased = 0
        var duplicateReleased = 0
        var published = 0

        assertEquals(RenderPublicationDecision.Published, policy.publish(work, candidate("accepted") { acceptedReleased++ }) { published++ })
        assertEquals(RenderPublicationDecision.AlreadyTerminal, policy.publish(work, candidate("duplicate") { duplicateReleased++ }) { published++ })

        assertEquals(1, published)
        assertEquals(0, acceptedReleased)
        assertEquals(1, duplicateReleased)
    }

    @Test fun competingCompletionsPublishOnlyOneAndReleaseEveryLoser() {
        val policy = RenderPublicationPolicy()
        val work = policy.openSession("corpus-a")
        val workers = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val published = AtomicInteger()
        val released = AtomicInteger()
        try {
            repeat(2) {
                workers.execute {
                    ready.countDown()
                    start.await()
                    policy.publish(work, candidate(it) { released.incrementAndGet() }) { published.incrementAndGet() }
                    completed.countDown()
                }
            }
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(completed.await(1, TimeUnit.SECONDS))
        } finally {
            workers.shutdownNow()
        }

        assertEquals(1, published.get())
        assertEquals(1, released.get())
    }

    @Test fun staleViewportCandidateIsReleasedExactlyOnce() {
        val policy = RenderPublicationPolicy()
        val work = policy.openSession("corpus-a")
        var released = 0
        var published = 0
        policy.invalidateViewportDemand()

        assertEquals(RenderPublicationDecision.RejectedStale, policy.publish(work, candidate(Unit) { released++ }) { published++ })
        assertEquals(1, released)
        assertEquals(0, published)
    }

    private fun <T> candidate(value: T, release: () -> Unit) = RenderCandidate(value) { release() }
}
