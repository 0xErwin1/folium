package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class ByteBoundedPageCacheTest {

    @Test fun putAccountsExactSizeAndGetReturnsTheCachedValue() {
        val cache = ByteBoundedPageCache<String>(maxBytes = 100)
        val key = key(pageIndex = 0)

        assertTrue(cache.put(key, RenderCandidate("page-0") {}, sizeBytes = 40))

        assertEquals(40L, cache.totalBytesTracked())
        assertEquals(1, cache.entryCount())
        assertEquals("page-0", cache.get(key)?.value)
    }

    @Test fun totalBytesTrackedNeverExceedsMaxBytesAfterAnyPut() {
        val cache = ByteBoundedPageCache<Int>(maxBytes = 50)

        repeat(10) { index ->
            cache.put(key(pageIndex = index), RenderCandidate(index) {}, sizeBytes = 20)
            assertTrue(cache.totalBytesTracked() <= 50)
        }
    }

    /**
     * Deterministic construction, not a coincidence of iteration order: [key] A is inserted
     * first, then B, then B is touched by [ByteBoundedPageCache.get] so it becomes the
     * most-recently-used entry, then C is inserted. With a two-slot budget, the eviction that C's
     * insertion triggers must drop A — the entry nobody touched since it was inserted — not B.
     */
    @Test fun evictionIsStrictLeastRecentlyUsed() {
        val released = mutableListOf<String>()
        val cache = ByteBoundedPageCache<String>(maxBytes = 20)
        val keyA = key(pageIndex = 0)
        val keyB = key(pageIndex = 1)
        val keyC = key(pageIndex = 2)

        cache.put(keyA, RenderCandidate("a") { released.add("a") }, sizeBytes = 10)
        cache.put(keyB, RenderCandidate("b") { released.add("b") }, sizeBytes = 10)
        assertEquals("b", cache.get(keyB)?.value)

        cache.put(keyC, RenderCandidate("c") { released.add("c") }, sizeBytes = 10)

        assertEquals(listOf("a"), released)
        assertNull(cache.get(keyA))
        assertNotNull(cache.get(keyB))
        assertNotNull(cache.get(keyC))
        assertEquals(20L, cache.totalBytesTracked())
    }

    @Test fun oversizedEntryIsRefusedAndReleasedWithoutDisturbingExistingEntries() {
        val cache = ByteBoundedPageCache<String>(maxBytes = 30)
        val keyA = key(pageIndex = 0)
        val keyOversized = key(pageIndex = 1)
        var oversizedReleased = false

        cache.put(keyA, RenderCandidate("a") {}, sizeBytes = 10)

        val retained = cache.put(keyOversized, RenderCandidate("too-big") { oversizedReleased = true }, sizeBytes = 40)

        assertFalse(retained)
        assertTrue(oversizedReleased)
        assertNull(cache.get(keyOversized))
        assertNotNull(cache.get(keyA))
        assertEquals(10L, cache.totalBytesTracked())
    }

    @Test fun replacingAnExistingKeyReleasesTheReplacedCandidateExactlyOnce() {
        val cache = ByteBoundedPageCache<String>(maxBytes = 100)
        val key = key(pageIndex = 0)
        val firstReleases = AtomicInteger(0)

        val first = RenderCandidate("first") { firstReleases.incrementAndGet() }
        cache.put(key, first, sizeBytes = 10)
        cache.put(key, RenderCandidate("second") {}, sizeBytes = 20)

        assertEquals(1, firstReleases.get())
        assertEquals("second", cache.get(key)?.value)
        assertEquals(20L, cache.totalBytesTracked())
        assertEquals(1, cache.entryCount())
    }

    @Test fun invalidateDocumentRemovesAndReleasesEveryEntryForThatDocumentOnly() {
        val cache = ByteBoundedPageCache<String>(maxBytes = 1000)
        val released = mutableListOf<String>()
        cache.put(key(documentId = "doc-a", pageIndex = 0), RenderCandidate("a0") { released.add("a0") }, 10)
        cache.put(key(documentId = "doc-a", pageIndex = 1), RenderCandidate("a1") { released.add("a1") }, 10)
        cache.put(key(documentId = "doc-b", pageIndex = 0), RenderCandidate("b0") { released.add("b0") }, 10)

        cache.invalidateDocument("doc-a")

        assertEquals(setOf("a0", "a1"), released.toSet())
        assertEquals(1, cache.entryCount())
        assertEquals(10L, cache.totalBytesTracked())
        assertNotNull(cache.get(key(documentId = "doc-b", pageIndex = 0)))
    }

    @Test fun invalidatePageRemovesAcrossGenerationsAndSpecsButOnlyThatPage() {
        val cache = ByteBoundedPageCache<String>(maxBytes = 1000)
        val released = mutableListOf<String>()
        cache.put(key(pageIndex = 0, generation = 0), RenderCandidate("p0g0") { released.add("p0g0") }, 10)
        cache.put(key(pageIndex = 0, generation = 1), RenderCandidate("p0g1") { released.add("p0g1") }, 10)
        cache.put(key(pageIndex = 1, generation = 0), RenderCandidate("p1g0") { released.add("p1g0") }, 10)

        cache.invalidatePage(documentId = "doc-0", pageIndex = 0)

        assertEquals(setOf("p0g0", "p0g1"), released.toSet())
        assertEquals(1, cache.entryCount())
        assertNotNull(cache.get(key(pageIndex = 1, generation = 0)))
    }

    @Test fun invalidateStaleGenerationsRemovesOnlyOlderGenerationsForThatDocument() {
        val cache = ByteBoundedPageCache<String>(maxBytes = 1000)
        val released = mutableListOf<String>()
        cache.put(key(pageIndex = 0, generation = 0), RenderCandidate("gen0") { released.add("gen0") }, 10)
        cache.put(key(pageIndex = 0, generation = 1), RenderCandidate("gen1") { released.add("gen1") }, 10)
        cache.put(key(pageIndex = 0, generation = 2), RenderCandidate("gen2") { released.add("gen2") }, 10)
        cache.put(key(documentId = "doc-other", pageIndex = 0, generation = 0), RenderCandidate("other") {}, 10)

        cache.invalidateStaleGenerations(documentId = "doc-0", currentGeneration = 2)

        assertEquals(setOf("gen0", "gen1"), released.toSet())
        assertNull(cache.get(key(pageIndex = 0, generation = 0)))
        assertNull(cache.get(key(pageIndex = 0, generation = 1)))
        assertNotNull(cache.get(key(pageIndex = 0, generation = 2)))
        assertNotNull(cache.get(key(documentId = "doc-other", pageIndex = 0, generation = 0)))
    }

    @Test fun trimToBytesShedsLeastRecentlyUsedDownToTheTarget() {
        val released = mutableListOf<String>()
        val cache = ByteBoundedPageCache<String>(maxBytes = 100)
        val keyA = key(pageIndex = 0)
        val keyB = key(pageIndex = 1)
        val keyC = key(pageIndex = 2)
        cache.put(keyA, RenderCandidate("a") { released.add("a") }, 10)
        cache.put(keyB, RenderCandidate("b") { released.add("b") }, 10)
        cache.put(keyC, RenderCandidate("c") { released.add("c") }, 10)

        cache.trimToBytes(targetBytes = 10)

        assertEquals(listOf("a", "b"), released)
        assertEquals(10L, cache.totalBytesTracked())
        assertNotNull(cache.get(keyC))
    }

    @Test fun trimToZeroClearsEverything() {
        val cache = ByteBoundedPageCache<String>(maxBytes = 100)
        cache.put(key(pageIndex = 0), RenderCandidate("a") {}, 10)
        cache.put(key(pageIndex = 1), RenderCandidate("b") {}, 10)

        cache.trimToBytes(targetBytes = 0)

        assertEquals(0L, cache.totalBytesTracked())
        assertEquals(0, cache.entryCount())
    }

    @Test fun clearReleasesEveryEntryAndResetsAccountingToZero() {
        val released = mutableListOf<String>()
        val cache = ByteBoundedPageCache<String>(maxBytes = 100)
        cache.put(key(pageIndex = 0), RenderCandidate("a") { released.add("a") }, 10)
        cache.put(key(pageIndex = 1), RenderCandidate("b") { released.add("b") }, 20)

        cache.clear()

        assertEquals(setOf("a", "b"), released.toSet())
        assertEquals(0L, cache.totalBytesTracked())
        assertEquals(0, cache.entryCount())
    }

    @Test fun evictionNeverReleasesTheSameCandidateTwiceEvenWhenAlsoReleasedExternally() {
        val releaseCount = AtomicInteger(0)
        val cache = ByteBoundedPageCache<String>(maxBytes = 100)
        val key = key(pageIndex = 0)
        val candidate = RenderCandidate("a") { releaseCount.incrementAndGet() }
        cache.put(key, candidate, sizeBytes = 10)

        // Simulates a consumer error path racing the cache's own eviction: both try to free the
        // same engine resource, and RenderCandidate.release()'s CAS guard must make only one win.
        candidate.release()
        cache.clear()
        candidate.release()

        assertEquals(1, releaseCount.get())
    }

    /**
     * Deterministic construction (per the project's concurrency-verification convention: construct
     * the interleaving, do not sample it) proving no cache operation ever runs a release callback
     * while holding the cache's internal monitor. Thread A's [ByteBoundedPageCache.put] for keyB
     * evicts keyA's entry — inside the monitor, keyB is already committed to the map before the
     * monitor is left — and keyA's release then blocks on [proceed]. While that release is still
     * running, the main thread's [ByteBoundedPageCache.get] for keyB must complete promptly rather
     * than blocking on the same lock keyA's release would be holding if release ran inside the
     * monitor.
     */
    @Test fun releaseNeverRunsWhileTheCacheLockIsHeld() {
        val evictionStarted = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val cache = ByteBoundedPageCache<String>(maxBytes = 10)
        val keyA = key(pageIndex = 0)
        val keyB = key(pageIndex = 1)

        cache.put(keyA, RenderCandidate("a") {
            evictionStarted.countDown()
            assertTrue(proceed.await(5, TimeUnit.SECONDS))
        }, sizeBytes = 10)

        val evictingThread = Thread { cache.put(keyB, RenderCandidate("b") {}, sizeBytes = 10) }
        evictingThread.start()

        assertTrue(evictionStarted.await(5, TimeUnit.SECONDS))

        val startNanos = System.nanoTime()
        val stillCached = cache.get(keyB)
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

        assertTrue("cache.get must not block on a release still in progress, took ${elapsedMillis}ms", elapsedMillis < 500)
        assertNotNull("keyB is committed to the map inside the monitor, before keyA's release runs outside it", stillCached)

        proceed.countDown()
        evictingThread.join(5_000)
        assertFalse(evictingThread.isAlive)
    }

    /**
     * The interim safety gate for byte-bounded caching is "no monotonic resource growth"; this is
     * that gate in unit form. A long randomised sequence of every mutating operation must never
     * let [ByteBoundedPageCache.totalBytesTracked] drift from the sum of the sizes of the entries
     * actually held, must never exceed [ByteBoundedPageCache.maxBytes] after a retained [put], and
     * every constructed candidate must end up either still cached or released — exactly once,
     * never both, never neither.
     */
    @Test fun byteAccountingStaysExactAcrossALongRandomizedSequence() {
        val cache = ByteBoundedPageCache<Int>(maxBytes = 500)
        val random = Random(seed = 42)
        val releaseCount = AtomicInteger(0)
        var totalConstructed = 0

        repeat(20_000) {
            when (random.nextInt(5)) {
                0 -> {
                    val doc = "doc-${random.nextInt(3)}"
                    val page = random.nextInt(10)
                    val generation = random.nextLong(0, 4)
                    val size = random.nextLong(1, 200)
                    totalConstructed++
                    cache.put(key(doc, page, generation), RenderCandidate(totalConstructed) { releaseCount.incrementAndGet() }, size)
                }
                1 -> cache.invalidateDocument("doc-${random.nextInt(3)}")
                2 -> cache.invalidatePage("doc-${random.nextInt(3)}", random.nextInt(10))
                3 -> cache.invalidateStaleGenerations("doc-${random.nextInt(3)}", random.nextLong(0, 4))
                4 -> cache.trimToBytes(random.nextLong(0, 500))
            }

            assertEquals(cache.sizeOfLiveEntries(), cache.totalBytesTracked())
            assertTrue(cache.totalBytesTracked() <= cache.maxBytes)
            assertEquals(totalConstructed, releaseCount.get() + cache.entryCount())
        }

        cache.clear()

        assertEquals(0L, cache.totalBytesTracked())
        assertEquals(0, cache.entryCount())
        assertEquals(totalConstructed, releaseCount.get())
    }

    private fun key(
        documentId: String = "doc-0",
        pageIndex: Int,
        generation: Long = 0
    ): PageCacheKey = PageCacheKey(documentId, pageIndex, generation, spec())
}
