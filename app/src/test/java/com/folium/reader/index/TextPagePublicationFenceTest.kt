package com.folium.reader.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TextPagePublicationFenceTest {
    @Test fun publicationCallbackDefersExclusiveCleanupUntilOutermostFenceRelease() {
        val fence = TextPagePublicationFences.isolated()
        val callbackReturned = CountDownLatch(1)
        val cleanupCount = AtomicInteger()
        val cleanupRef = AtomicReference<DeferredExclusiveCleanup>()
        val worker = daemonThread("fence-owner") {
            fence.locked {
                val callback = daemonThread("publication-callback") {
                    fence.publishing {
                        cleanupRef.set(fence.closeOrDefer { cleanupCount.incrementAndGet() })
                        assertFalse(requireNotNull(cleanupRef.get()).isComplete())
                    }
                    callbackReturned.countDown()
                }
                callback.start()
                assertTrue(callbackReturned.await(2, TimeUnit.SECONDS))
                callback.join(2_000)
                assertEquals(0, cleanupCount.get())
            }
        }

        worker.start()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        requireNotNull(cleanupRef.get()).await()
        assertTrue(requireNotNull(cleanupRef.get()).isComplete())
        assertEquals(1, cleanupCount.get())
    }

    @Test fun normalCleanupWaitsForEarlierFenceOwnerAndCompletesSynchronously() {
        val fence = TextPagePublicationFences.isolated()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanupCount = AtomicInteger()
        val owner = daemonThread("fence-owner") {
            fence.locked {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        }
        owner.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closerReturned = CountDownLatch(1)
        val closer = daemonThread("fence-closer") {
            val cleanup = fence.closeOrDefer { cleanupCount.incrementAndGet() }
            cleanup.await()
            closerReturned.countDown()
        }
        closer.start()

        assertFalse(closerReturned.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(closerReturned.await(2, TimeUnit.SECONDS))
        owner.join(2_000)
        closer.join(2_000)
        assertEquals(1, cleanupCount.get())
    }
}

private fun daemonThread(name: String, block: () -> Unit) = Thread(block, name).apply { isDaemon = true }
