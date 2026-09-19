package com.folium.reader.reader

import android.content.Context
import com.folium.reader.core.preview.PagePreview
import com.folium.reader.core.preview.PagePreviewFile
import com.folium.reader.core.preview.PagePreviewScaler
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val PAGE_PREVIEW_CACHE_DIR_NAME = "page-preview-cache"

/**
 * How many preview writes may sit queued for [PagePreviews]' writer thread before [PagePreviews.offer]
 * starts dropping them. Small on purpose: a dropped preview only costs a placeholder shown one page a
 * reader may never even visit, and a queue deep enough to matter would only mean the writer is falling
 * behind a burst of page turns, at which point the extra previews are the least useful bytes to keep.
 */
internal const val PAGE_PREVIEW_WRITE_QUEUE_CAPACITY = 8

/** Where every document's preview files live under the app's cache directory. */
internal fun pagePreviewCacheRoot(context: Context): File =
    File(context.applicationContext.cacheDir, PAGE_PREVIEW_CACHE_DIR_NAME)

/**
 * One document layout's worth of blurred page previews, held through [PagePreviewFile] and mirrored
 * to disk under `rootDir/contentId/` so a later session over the same document and layout starts with
 * them already available instead of rebuilding every one from scratch.
 *
 * [offer] is safe to call from any renderer thread. The downscale itself — see [PagePreviewScaler] —
 * is cheap enough to run inline on the calling thread, but the disk write it produces is handed to
 * [writer], a single [Thread.MIN_PRIORITY] thread fed by a small bounded queue, so neither a burst of
 * page turns nor a slow disk ever blocks whatever thread just finished rendering the reader's current
 * page.
 *
 * [version] increases every time a write actually lands in [file], so a caller only interested in
 * whether anything changed can compare an integer instead of re-reading every page it is showing.
 */
internal class PagePreviews private constructor(
    private val file: PagePreviewFile,
    private val writeQueue: ArrayBlockingQueue<() -> Unit>,
    private val writer: Thread
) {
    private val stopped = AtomicBoolean(false)
    private val versionCounter = AtomicInteger(0)

    val version: Int get() = versionCounter.get()

    fun previewFor(pageIndex: Int): PagePreview? = file.previewFor(pageIndex)

    /**
     * Downscales [rgba] — a [width]x[height] whole-page raster — and queues the result for disk,
     * unless [pageIndex] already has a preview. That check runs before any pixel is touched, so a
     * page whose preview already landed, or that this instance already queued a write for under a
     * concurrent call, costs nothing beyond the lookup. A write dropped because [writeQueue] is full
     * is never retried by this instance: a later session reopening the same file simply finds the
     * page still missing and can offer it again.
     */
    fun offer(pageIndex: Int, rgba: ByteArray, width: Int, height: Int) {
        if (stopped.get()) return
        if (file.previewFor(pageIndex) != null) return

        val preview = traced({ "folium:preview:make:$pageIndex" }) {
            PagePreviewScaler.scale(rgba, width, height)
        }

        writeQueue.offer { writeAndBump(pageIndex, preview) }
    }

    private fun writeAndBump(pageIndex: Int, preview: PagePreview) = traced({ "folium:preview:write" }) {
        if (file.addPreview(pageIndex, preview)) versionCounter.incrementAndGet()
    }

    /**
     * Stops [writer] promptly and is safe to call more than once. A write already queued but not yet
     * running is simply dropped rather than completed, exactly like one [offer] itself drops when the
     * queue is full: nothing here ever blocks waiting for disk I/O to finish.
     */
    fun close() {
        if (!stopped.compareAndSet(false, true)) return
        writer.interrupt()
        try {
            writer.join(1_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Offers a raw task to [writeQueue] exactly as [offer] does after downscaling, bypassing that
     * downscale entirely so a test can observe the bounded-queue-drop behavior without timing its way
     * around how fast a real write happens to run.
     */
    internal fun offerRawTaskForTest(task: () -> Unit): Boolean = writeQueue.offer(task)

    /**
     * Blocks until every write already offered to [writeQueue] has been processed, by offering one
     * more task behind them and waiting for it to run. Exists for tests only: nothing in the
     * production path needs this, since nothing there waits on a preview write.
     */
    internal fun awaitIdleForTest(timeoutMillis: Long = 5_000): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000
        while (!writeQueue.offer(latch::countDown)) {
            if (System.nanoTime() >= deadlineNanos) return false
            Thread.sleep(1)
        }
        val remainingMillis = ((deadlineNanos - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
        return latch.await(remainingMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    companion object {
        /**
         * Opens the preview file for (engineId, contentId, layoutVersion, pageCount) under
         * `rootDir/contentId/` and starts its writer thread. Performs real file I/O — see
         * [PagePreviewFile.open] — so this must run off the UI thread; traced as `folium:preview:load`.
         */
        fun open(
            rootDir: File,
            engineId: String,
            contentId: String,
            layoutVersion: String?,
            pageCount: Int
        ): PagePreviews {
            val documentDir = File(rootDir, contentId)
            val file = traced({ "folium:preview:load" }) {
                PagePreviewFile.open(
                    File(documentDir, PagePreviewFile.fileName(engineId, contentId, layoutVersion)),
                    engineId, contentId, layoutVersion, pageCount
                )
            }

            val writeQueue = ArrayBlockingQueue<() -> Unit>(PAGE_PREVIEW_WRITE_QUEUE_CAPACITY)
            val writer = Thread({ drainQueue(writeQueue) }, "reader-preview-write").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
            writer.start()

            return PagePreviews(file, writeQueue, writer)
        }

        private fun drainQueue(queue: ArrayBlockingQueue<() -> Unit>) {
            while (true) {
                val task = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                task()
            }
        }
    }
}
