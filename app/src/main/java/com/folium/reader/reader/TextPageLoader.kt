package com.folium.reader.reader

import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.text.TextPage
import java.util.LinkedHashMap

private const val DEFAULT_TEXT_CACHE_BYTES = 4L * 1024 * 1024

internal sealed class TextPageLoadResult {
    data class Loaded(val page: TextPage) : TextPageLoadResult()
    data object Failed : TextPageLoadResult()
}

/**
 * Session-scoped text extraction with one active request and one latest-wins pending slot.
 *
 * MuPDF serializes document operations, so parallel extraction would only add waiting work. A new
 * page supersedes any queued page while the active native call drains. Repeated requests for the
 * same active or queued page replace its single publication owner instead of accumulating callbacks.
 */
internal class TextPageLoader(
    private val document: PdfDocument,
    private val pageCount: Int,
    private val deliver: ((() -> Unit) -> Unit),
    private val maxCacheBytes: Long = DEFAULT_TEXT_CACHE_BYTES,
    threadFactory: (Runnable) -> Thread = { runnable ->
        Thread(runnable, "reader-text").apply { isDaemon = true }
    }
) {
    private data class Request(
        val id: Long,
        val pageIndex: Int,
        val callback: (TextPageLoadResult) -> Unit
    )

    private data class CachedPage(val page: TextPage, val bytes: Long)

    private val lock = Object()
    private val cache = LinkedHashMap<Int, CachedPage>(4, .75f, true)
    private val worker = threadFactory(Runnable(::workLoop))

    private var cacheBytes = 0L
    private var latestRequest: Request? = null
    private var activePageIndex: Int? = null
    private var nextRequestId = 0L
    private var currentRequestId = 0L
    private var closed = false

    init {
        require(pageCount > 0)
        require(maxCacheBytes > 0)
        worker.start()
    }

    fun load(pageIndex: Int, callback: (TextPageLoadResult) -> Unit) {
        require(pageIndex in 0 until pageCount)

        val cached: Pair<Long, TextPage>? = synchronized(lock) {
            if (closed) return

            val requestId = ++nextRequestId
            currentRequestId = requestId
            cache[pageIndex]?.let { entry ->
                latestRequest = null
                return@synchronized requestId to entry.page
            }

            latestRequest = Request(requestId, pageIndex, callback)
            lock.notifyAll()
            null
        }

        cached?.let { (requestId, page) -> publish(requestId, callback, TextPageLoadResult.Loaded(page)) }
    }

    /** Suppresses publication immediately and wakes the worker so teardown can drain it. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            latestRequest = null
            lock.notifyAll()
        }
    }

    /** Blocking. Returns only after the active extraction has left the document. */
    fun dispose() {
        close()
        var interrupted = false
        while (worker.isAlive) {
            try {
                worker.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        synchronized(lock) {
            cache.clear()
            cacheBytes = 0L
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    internal fun cachedPageCount(): Int = synchronized(lock) { cache.size }
    internal fun cachedBytes(): Long = synchronized(lock) { cacheBytes }
    internal fun queuedPageCount(): Int = synchronized(lock) {
        if (latestRequest != null && latestRequest?.pageIndex != activePageIndex) 1 else 0
    }
    internal fun activePage(): Int? = synchronized(lock) { activePageIndex }

    private fun workLoop() {
        while (true) {
            val pageIndex = synchronized(lock) {
                while (!closed && (latestRequest == null || latestRequest?.pageIndex == activePageIndex)) {
                    lock.wait()
                }
                if (closed) return

                latestRequest!!.pageIndex.also { activePageIndex = it }
            }

            val result = extract(pageIndex)
            val publication = synchronized(lock) {
                if (result is TextPageLoadResult.Loaded) cache(pageIndex, result.page)

                activePageIndex = null
                val current = latestRequest
                val publish = if (!closed && current?.pageIndex == pageIndex) {
                    latestRequest = null
                    Triple(current.id, current.callback, result)
                } else {
                    null
                }
                lock.notifyAll()
                publish
            }

            publication?.let { (id, callback, completed) -> publish(id, callback, completed) }
        }
    }

    private fun extract(pageIndex: Int): TextPageLoadResult = try {
        TextPageLoadResult.Loaded(document.extractText(pageIndex))
    } catch (_: Exception) {
        TextPageLoadResult.Failed
    }

    private fun cache(pageIndex: Int, page: TextPage) {
        val bytes = estimateTextPageBytes(page)
        cache.remove(pageIndex)?.let { cacheBytes -= it.bytes }
        if (bytes > maxCacheBytes) return

        cache[pageIndex] = CachedPage(page, bytes)
        cacheBytes += bytes
        while (cacheBytes > maxCacheBytes) {
            val eldest = cache.entries.iterator().next()
            cacheBytes -= eldest.value.bytes
            cache.remove(eldest.key)
        }
    }

    private fun publish(
        requestId: Long,
        callback: (TextPageLoadResult) -> Unit,
        result: TextPageLoadResult
    ) {
        deliver {
            val current = synchronized(lock) { !closed && currentRequestId == requestId }
            if (current) callback(result)
        }
    }
}

/** Conservative deterministic estimate of the retained TextPage hierarchy and derived strings. */
internal fun estimateTextPageBytes(page: TextPage): Long {
    var bytes = 96L
    bytes += stringBytes(page.text)
    bytes += page.lines.size * 8L
    bytes += page.words.size * 8L

    page.blocks.forEach { block ->
        bytes += 96L + stringBytes(block.text) + block.lines.size * 8L
        block.lines.forEach { line ->
            bytes += 96L + stringBytes(line.text) + line.words.size * 8L
            line.words.forEach { word ->
                bytes += 144L + stringBytes(word.text) + stringBytes(word.languageTag) + word.fonts.size * 8L
                word.fonts.forEach { font ->
                    bytes += 96L + stringBytes(font.name)
                }
            }
        }
    }
    return bytes
}

private fun stringBytes(value: String?): Long = if (value == null) 0L else 40L + value.length * 2L
