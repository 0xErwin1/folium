package com.folium.reader.core.diskcache

import com.folium.reader.core.pdf.PageSpaceRect
import java.io.File
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDiskPageCacheStoreTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private fun rgba(width: Int, height: Int, seed: Long = 1L): ByteArray {
        val bytes = ByteArray(width * height * 4)
        Random(seed).nextBytes(bytes)
        return bytes
    }

    private fun key(contentId: String = "content-1", pageIndex: Int = 0, width: Int = 10, height: Int = 10) =
        DiskPageCacheKey(1, "engine-1", contentId, null, pageIndex, width, height, PageSpaceRect(0f, 0f, 1f, 1f))

    /**
     * Blocks until [key]'s write, and every eviction it may have triggered, has fully run on the
     * store's background thread — see [FileDiskPageCacheStore.awaitIdle].
     */
    private fun FileDiskPageCacheStore.writeAndAwait(
        key: DiskPageCacheKey,
        rgba: ByteArray,
        pageAspect: Float = 1f
    ) {
        enqueueWrite(key, rgba, pageAspect)
        require(awaitIdle()) { "write for ${key.contentId}/${diskPageCacheFileName(key)} never completed" }
    }

    @Test fun putThenGetReturnsIdenticalPixelsAndShape() {
        val root = tempFolder.newFolder("cache")
        val store = FileDiskPageCacheStore(root)
        val pixels = rgba(10, 10)
        val diskKey = key()

        store.writeAndAwait(diskKey, pixels, pageAspect = 612f / 792f)
        val entry = store.read(diskKey)

        requireNotNull(entry)
        assertArrayEquals(pixels, entry.rgba)
        assertEquals(10, entry.width)
        assertEquals(10, entry.height)
        assertEquals(612f / 792f, entry.pageAspect)
    }

    @Test fun aDifferentKeyIsAMiss() {
        val root = tempFolder.newFolder("cache")
        val store = FileDiskPageCacheStore(root)
        store.writeAndAwait(key(pageIndex = 0), rgba(10, 10))

        assertNull(store.read(key(pageIndex = 1)))
    }

    @Test fun containsKeyIsCheapAndDoesNotRequireAValidHeader() {
        val root = tempFolder.newFolder("cache")
        val store = FileDiskPageCacheStore(root)
        val diskKey = key()
        assertFalse(store.containsKey(diskKey))

        store.writeAndAwait(diskKey, rgba(10, 10))
        assertTrue(store.containsKey(diskKey))
    }

    @Test fun aCorruptStoredFileIsAMissAndIsDeleted() {
        val root = tempFolder.newFolder("cache")
        val store = FileDiskPageCacheStore(root)
        val diskKey = key()
        store.writeAndAwait(diskKey, rgba(10, 10))

        val file = File(File(root, diskKey.contentId), diskPageCacheFileName(diskKey))
        file.writeBytes(ByteArray(2))

        assertNull(store.read(diskKey))
        assertFalse(file.exists())
    }

    @Test fun anAbandonedTempFileIsCleanedUpOnNextStart() {
        val root = tempFolder.newFolder("cache")
        val docDir = File(root, "content-1").apply { mkdirs() }
        val leftover = File(docDir, "orphan.pgc.tmp").apply { writeBytes(ByteArray(4)) }
        assertTrue(leftover.exists())

        FileDiskPageCacheStore(root)

        assertFalse(leftover.exists())
    }

    @Test fun evictionNotifiesOnEvictOnlyWhenSomethingWasActuallyRemoved() {
        val root = tempFolder.newFolder("cache")
        val evicted = mutableListOf<List<String>>()
        // Large enough to hold exactly one 10x10 entry (roughly 450-500 bytes with header), too small for two.
        val store = FileDiskPageCacheStore(root, maxBytes = 600, onEvict = { evicted += it })

        store.writeAndAwait(key(contentId = "first"), rgba(10, 10))
        assertEquals(emptyList<List<String>>(), evicted)

        store.writeAndAwait(key(contentId = "second"), rgba(10, 10))
        assertEquals(listOf(listOf("first")), evicted)
    }

    @Test fun budgetEvictsLeastRecentlyUsedDocumentsButNeverTheOpenOne() {
        val root = tempFolder.newFolder("cache")
        val store = FileDiskPageCacheStore(root, maxBytes = 1)
        store.markOpen("open-doc")

        val openKey = key(contentId = "open-doc")
        val closedKey = key(contentId = "closed-doc")
        store.writeAndAwait(openKey, rgba(10, 10))
        store.writeAndAwait(closedKey, rgba(10, 10))

        assertTrue("the open document must survive even though it alone exceeds the budget", store.containsKey(openKey))
        assertFalse("the closed document must be evicted to bring the cache back under budget", store.containsKey(closedKey))
    }

    @Test fun theWriteQueueDropsWorkInsteadOfBlockingWhenFull() {
        val root = tempFolder.newFolder("cache")
        val store = FileDiskPageCacheStore(root)

        // Occupies the writer thread itself, so the tasks offered below are the only ones counted
        // against the queue's own bounded capacity rather than racing the thread that drains it.
        // The blocker has to be running, not merely queued: until the writer takes it, it still
        // holds one of the slots the fill below counts on.
        val blockerRunning = CountDownLatch(1)
        val blocker = CountDownLatch(1)
        assertTrue(store.offerRawTask { blockerRunning.countDown(); blocker.await() })
        assertTrue(blockerRunning.await(5, java.util.concurrent.TimeUnit.SECONDS))

        repeat(WRITE_QUEUE_CAPACITY) { assertTrue(store.offerRawTask {}) }
        assertFalse("a task offered beyond the bound capacity must be dropped, not queued", store.offerRawTask {})

        blocker.countDown()
        assertTrue(store.awaitIdle())
    }

    @Test fun anyFileInADocumentsDirectoryCountsTowardItsBudgetUsage() {
        val root = tempFolder.newFolder("cache")
        val evicted = mutableListOf<List<String>>()
        // Budget large enough for the raster entry alone, but not once an unrelated file — such as a
        // page preview file, which is written by code outside this store — sits in the same directory.
        val store = FileDiskPageCacheStore(root, maxBytes = 700, onEvict = { evicted += it })

        store.writeAndAwait(key(contentId = "first"), rgba(10, 10))
        assertEquals("a lone raster entry must fit under the budget on its own", emptyList<List<String>>(), evicted)

        File(File(root, "first"), "previews.pgv").writeBytes(ByteArray(500))
        store.writeAndAwait(key(contentId = "second"), rgba(10, 10))

        assertEquals(
            "the extra unrelated file must be counted against 'first's usage, forcing its eviction",
            listOf(listOf("first")),
            evicted
        )
    }

    @Test fun nonWholePageSpecsAreNeverWrittenOrLookedUp() {
        val spec = com.folium.reader.core.pdf.RenderSpec(10, 10, PageSpaceRect(0.1f, 0.1f, 0.9f, 0.9f))
        assertFalse(spec.isWholePage())
        assertNull(DiskPageCacheKey.forWholePageSpec("engine-1", "content-1", null, 0, spec))
    }

    @Test fun concurrentGetAndPutOfTheSameKeyNeverYieldsTornData() {
        val root = tempFolder.newFolder("cache")
        val store = FileDiskPageCacheStore(root)
        val diskKey = key(width = 50, height = 50)
        val variantA = rgba(50, 50, seed = 1L)
        val variantB = rgba(50, 50, seed = 2L)

        store.writeAndAwait(diskKey, variantA)

        val readers = 8
        val iterationsPerReader = 50
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        val start = CountDownLatch(1)
        val threads = mutableListOf<Thread>()

        threads += Thread {
            start.await()
            repeat(iterationsPerReader) {
                store.enqueueWrite(diskKey, if (it % 2 == 0) variantA else variantB, 1f)
            }
        }
        repeat(readers) {
            threads += Thread {
                start.await()
                repeat(iterationsPerReader) {
                    val entry = store.read(diskKey) ?: return@repeat
                    val matchesA = entry.rgba.contentEquals(variantA)
                    val matchesB = entry.rgba.contentEquals(variantB)
                    if (!matchesA && !matchesB) failures.incrementAndGet()
                }
            }
        }

        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }

        assertEquals(0, failures.get())
    }
}
