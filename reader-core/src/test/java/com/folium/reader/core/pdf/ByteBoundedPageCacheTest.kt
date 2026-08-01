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
        assertEquals("page-0", cache.peek(key))
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
     * first, then B, then A is touched by [ByteBoundedPageCache.acquire] so it becomes the
     * most-recently-used entry, then C is inserted. With a two-slot budget, the eviction that C's
     * insertion triggers must drop B — the entry nobody touched since it was inserted — not A.
     * Touching A (not B, which is already the most-recently-inserted entry and so would make the
     * promotion a no-op) is what makes this test actually exercise access-order promotion: a
     * plain-FIFO mutant of the cache passes the old version of this test verbatim.
     */
    @Test fun evictionIsStrictLeastRecentlyUsed() {
        val released = mutableListOf<String>()
        val cache = ByteBoundedPageCache<String>(maxBytes = 20)
        val keyA = key(pageIndex = 0)
        val keyB = key(pageIndex = 1)
        val keyC = key(pageIndex = 2)

        cache.put(keyA, RenderCandidate("a") { released.add("a") }, sizeBytes = 10)
        cache.put(keyB, RenderCandidate("b") { released.add("b") }, sizeBytes = 10)
        assertEquals("a", cache.peek(keyA))

        cache.put(keyC, RenderCandidate("c") { released.add("c") }, sizeBytes = 10)

        assertEquals(listOf("b"), released)
        assertNotNull(cache.peek(keyA))
        assertNull(cache.peek(keyB))
        assertNotNull(cache.peek(keyC))
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
        assertNull(cache.peek(keyOversized))
        assertNotNull(cache.peek(keyA))
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
        assertEquals("second", cache.peek(key))
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
        assertNotNull(cache.peek(key(documentId = "doc-b", pageIndex = 0)))
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
        assertNotNull(cache.peek(key(pageIndex = 1, generation = 0)))
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
        assertNull(cache.peek(key(pageIndex = 0, generation = 0)))
        assertNull(cache.peek(key(pageIndex = 0, generation = 1)))
        assertNotNull(cache.peek(key(pageIndex = 0, generation = 2)))
        assertNotNull(cache.peek(key(documentId = "doc-other", pageIndex = 0, generation = 0)))
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
        assertNotNull(cache.peek(keyC))
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

    @Test fun rejectsZeroAndNegativeSizeBytes() {
        val cache = ByteBoundedPageCache<String>(maxBytes = 100)

        assertThrows { cache.put(key(pageIndex = 0), RenderCandidate("a") {}, sizeBytes = 0) }
        assertThrows { cache.put(key(pageIndex = 1), RenderCandidate("b") {}, sizeBytes = -1) }
    }

    /**
     * Deterministic construction (per the project's concurrency-verification convention: construct
     * the interleaving, do not sample it) proving no cache operation ever runs a release callback
     * while holding the cache's internal monitor. Thread A's [ByteBoundedPageCache.put] for keyB
     * evicts keyA's entry — inside the monitor, keyB is already committed to the map before the
     * monitor is left — and keyA's release then blocks on [proceed]. While that release is still
     * running, the main thread's [ByteBoundedPageCache.acquire] for keyB must complete promptly
     * rather than blocking on the same lock keyA's release would be holding if release ran inside
     * the monitor.
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
        val stillCached = cache.acquire(keyB)
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

        assertTrue("cache.acquire must not block on a release still in progress, took ${elapsedMillis}ms", elapsedMillis < 500)
        assertNotNull("keyB is committed to the map inside the monitor, before keyA's release runs outside it", stillCached)
        stillCached?.release()

        proceed.countDown()
        evictingThread.join(5_000)
        assertFalse(evictingThread.isAlive)
    }

    /**
     * The same leaf-lock property as [releaseNeverRunsWhileTheCacheLockIsHeld], but for the
     * deferred-release path [ByteBoundedPageCache.unpin] introduces: keyA is evicted while
     * pinned (so its release is deferred, not run by the evicting [put] itself), then the borrow
     * is released on a separate thread whose release callback blocks on [proceed]. While that
     * release is still running, the main thread's [ByteBoundedPageCache.totalBytesTracked] and a
     * concurrent [ByteBoundedPageCache.acquire] on a different key must both complete promptly,
     * proving [ByteBoundedPageCache.unpin] also never runs a release callback under the lock.
     */
    @Test fun deferredReleaseOnUnpinNeverRunsWhileTheCacheLockIsHeld() {
        val releaseStarted = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val cache = ByteBoundedPageCache<String>(maxBytes = 1000)
        val keyA = key(pageIndex = 0)
        val keyB = key(pageIndex = 1)

        cache.put(keyA, RenderCandidate("a") {
            releaseStarted.countDown()
            assertTrue(proceed.await(5, TimeUnit.SECONDS))
        }, sizeBytes = 10)
        val borrow = cache.acquire(keyA)!!
        cache.invalidateDocument(keyA.documentId)
        assertEquals(1, cache.pinnedAwaitingReleaseCount())

        val releasingThread = Thread { borrow.release() }
        releasingThread.start()

        assertTrue(releaseStarted.await(5, TimeUnit.SECONDS))

        val startNanos = System.nanoTime()
        cache.put(keyB, RenderCandidate("b") {}, sizeBytes = 10)
        val stillWorks = cache.totalBytesTracked()
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

        assertTrue("cache operations must not block on a deferred release still in progress, took ${elapsedMillis}ms", elapsedMillis < 500)
        assertTrue(stillWorks > 0)

        proceed.countDown()
        releasingThread.join(5_000)
        assertFalse(releasingThread.isAlive)
        assertEquals(0, cache.pinnedAwaitingReleaseCount())
    }

    /**
     * The interim safety gate for byte-bounded caching is "no monotonic resource growth"; this is
     * that gate in unit form. A long randomised sequence of every mutating operation must never
     * let [ByteBoundedPageCache.totalBytesTracked] drift from the sum of the sizes of the entries
     * actually held plus the sizes of any entries still awaiting their last unpin, must never
     * exceed [ByteBoundedPageCache.maxBytes] by more than the currently pinned bytes, and every
     * constructed candidate must end up either still cached, pinned-and-awaiting-release, or
     * released — exactly one of the three, never two, never none. Randomised pin/unpin traffic is
     * included so the same invariants are proven to hold with borrowing in play, not just without it.
     */
    @Test fun byteAccountingStaysExactAcrossALongRandomizedSequenceIncludingPins() {
        val cache = ByteBoundedPageCache<Int>(maxBytes = 500)
        val random = Random(seed = 42)
        val releaseCount = AtomicInteger(0)
        var totalConstructed = 0
        val outstandingBorrows = mutableListOf<CachedPage<Int>>()

        repeat(20_000) {
            when (random.nextInt(7)) {
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
                5 -> {
                    val doc = "doc-${random.nextInt(3)}"
                    val page = random.nextInt(10)
                    val generation = random.nextLong(0, 4)
                    cache.acquire(key(doc, page, generation))?.let { outstandingBorrows.add(it) }
                }
                6 -> if (outstandingBorrows.isNotEmpty()) {
                    outstandingBorrows.removeAt(random.nextInt(outstandingBorrows.size)).release()
                }
            }

            // Trichotomy: every constructed candidate is, at all times, either still reachable in
            // entries (whether pinned or not), evicted-but-pinned and awaiting its last unpin, or
            // already released -- exactly one of the three, never two, never none.
            assertEquals(totalConstructed, releaseCount.get() + cache.entryCount() + cache.pinnedAwaitingReleaseCount())
            assertEquals(cache.sizeOfLiveEntries() + cache.pinnedAwaitingReleaseBytes(), cache.totalBytesTracked())
            assertTrue(cache.totalBytesTracked() <= cache.maxBytes + cache.pinnedAwaitingReleaseBytes())
        }

        outstandingBorrows.forEach { it.release() }
        cache.clear()

        assertEquals(0L, cache.totalBytesTracked())
        assertEquals(0, cache.entryCount())
        assertEquals(0, cache.pinnedAwaitingReleaseCount())
        assertEquals(totalConstructed, releaseCount.get())
    }

    /**
     * The borrow-safety construction K1 requires: hold a borrow, force eviction from another
     * thread via every path that can release an entry, and assert the underlying resource is NOT
     * freed while the borrow is held, then IS freed exactly once once the borrow ends. This must
     * (and, run against the pre-fix `get()`-based API, did — see the batch report) fail against
     * code that hands out the raw candidate without pinning it.
     */
    @Test fun borrowedEntryIsNeverFreedByPutEvictWhileHeldThenFreedExactlyOnceAfterRelease() =
        verifyBorrowSurvivesRelease(maxBytes = 10) { cache, keyA -> cache.put(key(pageIndex = 1), RenderCandidate("b") {}, sizeBytes = 10) }

    @Test fun borrowedEntryIsNeverFreedByTrimToBytesWhileHeldThenFreedExactlyOnceAfterRelease() =
        verifyBorrowSurvivesRelease { cache, keyA -> cache.trimToBytes(0) }

    @Test fun borrowedEntryIsNeverFreedByClearWhileHeldThenFreedExactlyOnceAfterRelease() =
        verifyBorrowSurvivesRelease { cache, keyA -> cache.clear() }

    @Test fun borrowedEntryIsNeverFreedByInvalidateDocumentWhileHeldThenFreedExactlyOnceAfterRelease() =
        verifyBorrowSurvivesRelease { cache, keyA -> cache.invalidateDocument(keyA.documentId) }

    @Test fun borrowedEntryIsNeverFreedByInvalidatePageWhileHeldThenFreedExactlyOnceAfterRelease() =
        verifyBorrowSurvivesRelease { cache, keyA -> cache.invalidatePage(keyA.documentId, keyA.pageIndex) }

    @Test fun borrowedEntryIsNeverFreedByInvalidateStaleGenerationsWhileHeldThenFreedExactlyOnceAfterRelease() =
        verifyBorrowSurvivesRelease { cache, keyA -> cache.invalidateStaleGenerations(keyA.documentId, keyA.generation + 1) }

    @Test fun borrowedEntryIsNeverFreedByOverwriteOnInsertWhileHeldThenFreedExactlyOnceAfterRelease() =
        verifyBorrowSurvivesRelease { cache, keyA -> cache.put(keyA, RenderCandidate("replacement") {}, sizeBytes = 10) }

    /**
     * Shared construction for the six mutating paths above: put keyA, borrow it, apply [evict]
     * (whichever removal path the caller wants to prove), assert the underlying resource is still
     * intact and the borrow's entry is now counted in [ByteBoundedPageCache.pinnedAwaitingReleaseCount],
     * then release the borrow and assert the resource is freed exactly once and the pinned-awaiting
     * count returns to zero.
     */
    private fun verifyBorrowSurvivesRelease(maxBytes: Long = 1000, evict: (ByteBoundedPageCache<String>, PageCacheKey) -> Unit) {
        val cache = ByteBoundedPageCache<String>(maxBytes = maxBytes)
        val keyA = key(pageIndex = 0)
        val releaseCount = AtomicInteger(0)
        cache.put(keyA, RenderCandidate("a") { releaseCount.incrementAndGet() }, sizeBytes = 10)

        val borrow = cache.acquire(keyA)
        assertNotNull("must have retrieved the entry to borrow it", borrow)

        evict(cache, keyA)

        assertEquals("underlying must not be freed while the borrow is still held", 0, releaseCount.get())
        assertEquals(1, cache.pinnedAwaitingReleaseCount())

        borrow!!.release()

        assertEquals("underlying must be freed exactly once once the borrow ends", 1, releaseCount.get())
        assertEquals(0, cache.pinnedAwaitingReleaseCount())

        borrow.release()
        assertEquals("a second release() on the same borrow must be a no-op", 1, releaseCount.get())
    }

    @Test fun aBorrowedEntryDoesNotBlockANewPutUnderTheSameKey() {
        val cache = ByteBoundedPageCache<String>(maxBytes = 1000)
        val keyA = key(pageIndex = 0)
        val releaseCount = AtomicInteger(0)
        cache.put(keyA, RenderCandidate("a") { releaseCount.incrementAndGet() }, sizeBytes = 10)

        val borrow = cache.acquire(keyA)
        cache.put(keyA, RenderCandidate("a-refreshed") {}, sizeBytes = 10)

        assertEquals("a", borrow?.value)
        assertEquals("a-refreshed", cache.peek(keyA))
        assertEquals(0, releaseCount.get())

        borrow?.release()
        assertEquals(1, releaseCount.get())
    }

    private fun <T> ByteBoundedPageCache<T>.peek(key: PageCacheKey): T? {
        val borrow = acquire(key)
        val value = borrow?.value
        borrow?.release()
        return value
    }

    private fun assertThrows(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue("expected an IllegalArgumentException", threw)
    }

    private fun key(
        documentId: String = "doc-0",
        pageIndex: Int,
        generation: Long = 0
    ): PageCacheKey = PageCacheKey(documentId, pageIndex, generation, spec())
}
