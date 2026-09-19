package com.folium.reader.core.diskcache

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap

internal const val WRITE_QUEUE_CAPACITY = 8
private const val TEMP_SUFFIX = ".tmp"
private const val LAST_USED_MARKER = ".last-used"

/** How much of [FileDiskPageCacheStore.rootDir] the whole cache, across every document, may occupy. */
const val DISK_PAGE_CACHE_BUDGET_BYTES: Long = 512L * 1024 * 1024

/**
 * File-backed [DiskPageCacheStore]: one directory per document under [rootDir], named after
 * [DiskPageCacheKey.contentId] directly since that is already a filesystem-safe hex digest, and one
 * file per entry inside it, named by [diskPageCacheFileName].
 *
 * Writes are atomic — encoded into a [TEMP_SUFFIX] file in the entry's own directory, then moved
 * into place with [StandardCopyOption.ATOMIC_MOVE] — so a reader ever only sees a complete prior
 * version or a complete new one, never a partial file, and a process kill between the write and the
 * rename leaves nothing visible; [cleanUpAbandonedTempFiles] removes the orphaned temp file itself
 * the next time this store is constructed.
 *
 * All encoding and all disk writes happen on [writerThread], a single low-priority thread fed by a
 * bounded queue: [enqueueWrite] never blocks its caller, and a write submitted while the queue is
 * full is simply dropped. [read] and [containsKey] are plain blocking calls and may run on any
 * thread, including the thread that just rendered the page they are about to look up.
 */
class FileDiskPageCacheStore(
    private val rootDir: File,
    private val maxBytes: Long = DISK_PAGE_CACHE_BUDGET_BYTES,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** Notified, on the writer thread, whenever [enforceBudget] actually evicts one or more documents. */
    private val onEvict: (List<String>) -> Unit = {}
) : DiskPageCacheStore {

    private val openContentIds = ConcurrentHashMap.newKeySet<String>()
    private val writeQueue = ArrayBlockingQueue<() -> Unit>(WRITE_QUEUE_CAPACITY)

    private val writerThread = Thread({ drainQueue() }, "folium-disk-page-cache-writer").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }

    init {
        rootDir.mkdirs()
        cleanUpAbandonedTempFiles()
        writerThread.start()
    }

    override fun markOpen(contentId: String) {
        openContentIds += contentId
    }

    override fun markClosed(contentId: String) {
        openContentIds -= contentId
    }

    override fun containsKey(key: DiskPageCacheKey): Boolean = entryFile(key).isFile

    override fun read(key: DiskPageCacheKey): DiskPageCacheEntry? {
        val file = entryFile(key)
        if (!file.isFile) return null

        val entry = try {
            DataInputStream(FileInputStream(file).buffered()).use { readPageCacheEntry(it, key) }
        } catch (_: IOException) {
            null
        }

        if (entry == null) {
            file.delete()
            return null
        }

        touch(documentDir(key.contentId))
        return entry
    }

    override fun enqueueWrite(key: DiskPageCacheKey, rgba: ByteArray, pageAspect: Float) {
        writeQueue.offer { performWrite(key, rgba, pageAspect) }
    }

    /**
     * Blocks until every write already offered to [writeQueue] has been processed, by offering one
     * more task behind them and waiting for it to run. Exists for tests that need to observe the
     * result of a write, since [enqueueWrite] itself only ever offers work and returns immediately;
     * nothing in the production render path needs this, because nothing there waits on a write.
     */
    internal fun awaitIdle(timeoutMillis: Long = 5_000): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000
        while (!writeQueue.offer(latch::countDown)) {
            if (System.nanoTime() >= deadlineNanos) return false
            Thread.sleep(1)
        }
        val remainingMillis = ((deadlineNanos - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
        return latch.await(remainingMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * Offers a raw task to [writeQueue] exactly as [enqueueWrite] does, bypassing encoding entirely
     * so a test can observe the bounded-queue-drop behavior — [writeQueue]'s own capacity — without
     * timing its way around how fast [performWrite] happens to run.
     */
    internal fun offerRawTask(task: () -> Unit): Boolean = writeQueue.offer(task)

    private fun drainQueue() {
        while (true) {
            val task = try {
                writeQueue.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            task()
        }
    }

    private fun performWrite(key: DiskPageCacheKey, rgba: ByteArray, pageAspect: Float) {
        val dir = documentDir(key.contentId)
        dir.mkdirs()
        val target = File(dir, diskPageCacheFileName(key))
        val temp = File(dir, diskPageCacheFileName(key) + TEMP_SUFFIX)

        try {
            DataOutputStream(FileOutputStream(temp).buffered()).use { out ->
                writePageCacheEntry(out, key, rgba, pageAspect)
            }
            Files.move(
                temp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
            touch(dir)
            enforceBudget()
        } catch (_: IOException) {
            temp.delete()
        }
    }

    private fun enforceBudget() {
        val dirs = rootDir.listFiles { file -> file.isDirectory } ?: return
        val usages = dirs.map { dir -> DocumentDirUsage(dir.name, dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }, lastUsedMillis(dir)) }
        val evict = DiskCacheEviction.plan(usages, maxBytes, openContentIds)
        if (evict.isEmpty()) return
        evict.forEach { contentId -> documentDir(contentId).deleteRecursively() }
        onEvict(evict)
    }

    private fun documentDir(contentId: String): File = File(rootDir, contentId)

    private fun entryFile(key: DiskPageCacheKey): File = File(documentDir(key.contentId), diskPageCacheFileName(key))

    private fun touch(dir: File) {
        val marker = File(dir, LAST_USED_MARKER)
        if (!marker.exists()) marker.createNewFile()
        marker.setLastModified(nowMillis())
    }

    private fun lastUsedMillis(dir: File): Long {
        val marker = File(dir, LAST_USED_MARKER)
        return if (marker.exists()) marker.lastModified() else dir.lastModified()
    }

    private fun cleanUpAbandonedTempFiles() {
        rootDir.walkTopDown().filter { it.isFile && it.name.endsWith(TEMP_SUFFIX) }.forEach { it.delete() }
    }
}
