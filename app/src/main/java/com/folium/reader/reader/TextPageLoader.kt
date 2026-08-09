package com.folium.reader.reader

import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextPageMatch
import com.folium.reader.core.text.TextPageMatcher
import com.folium.reader.index.TextPageIndex
import com.folium.reader.index.TextPageIndexKey
import com.folium.reader.index.TextPageIndexState
import com.folium.reader.index.TextPageIndexWriteOutcome
import com.folium.reader.index.TextPagePublicationOutcome
import com.folium.reader.index.TextPageSearchHit
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.concurrent.CountDownLatch

private const val DEFAULT_TEXT_CACHE_BYTES = 4L * 1024 * 1024

internal sealed class TextPageLoadResult {
    data class Loaded(val page: TextPage) : TextPageLoadResult()
    data object Failed : TextPageLoadResult()
}

internal data class TextSearchProgress(
    val query: String,
    val matches: List<TextPageSearchHit>,
    val indexedPages: Int,
    val failedPages: Int,
    val totalPages: Int,
    val running: Boolean,
    val error: Boolean = false
)

/**
 * Session-scoped text extraction with one active request and one latest-wins pending slot.
 *
 * The document engine serializes operations, so parallel extraction would only add waiting work. A new
 * page supersedes any queued page while the active native call drains. Repeated requests for the
 * same active or queued page replace its single publication owner instead of accumulating callbacks.
 */
internal class TextPageLoader(
    private val document: PdfDocument,
    private val pageCount: Int,
    private val deliver: ((() -> Unit) -> Unit),
    private val maxCacheBytes: Long = DEFAULT_TEXT_CACHE_BYTES,
    private val index: TextPageIndex? = null,
    private val indexKey: ((Int) -> TextPageIndexKey)? = null,
    private val matchPage: (TextPage, String) -> List<TextPageMatch> = TextPageMatcher::find,
    private val onResultPageAggregated: () -> Unit = {},
    threadFactory: (Runnable) -> Thread = { runnable ->
        Thread(runnable, "reader-text").apply { isDaemon = true }
    }
) : SessionTextLoader {
    private data class Request(
        val id: Long,
        val pageIndex: Int,
        val callback: (TextPageLoadResult) -> Unit
    )

    private data class CachedPage(val page: TextPage, val bytes: Long)
    private class SearchRequest(
        val generation: Long,
        val query: String,
        val callback: (TextSearchProgress) -> Unit,
        pageCount: Int,
        private val onResultPageAggregated: () -> Unit
    ) {
        var coverage: SearchCoverage? = null
        private val matchesByPage = TreeMap<Int, List<TextPageSearchHit>>()
        private val matchedPages = BooleanArray(pageCount)
        var initialSearchComplete = false
        var lastUpdatedPage: Int? = null

        fun replaceInitial(hits: List<TextPageSearchHit>, completePages: Set<Int>) {
            matchesByPage.clear()
            hits.groupBy(TextPageSearchHit::pageIndex).forEach { (page, pageHits) ->
                if (page in matchedPages.indices && pageHits.isNotEmpty()) matchesByPage[page] = pageHits
            }
            completePages.forEach { if (it in matchedPages.indices) matchedPages[it] = true }
        }

        fun claimPage(pageIndex: Int): Boolean {
            if (pageIndex !in matchedPages.indices || matchedPages[pageIndex]) return false
            matchedPages[pageIndex] = true
            return true
        }

        fun replacePage(pageIndex: Int, hits: List<TextPageSearchHit>) {
            if (pageIndex !in matchedPages.indices) return
            if (hits.isEmpty()) matchesByPage.remove(pageIndex) else matchesByPage[pageIndex] = hits
        }

        fun matches(): List<TextPageSearchHit> = matchesByPage.values.flatMap {
            onResultPageAggregated()
            it
        }
    }

    private class SearchCoverage(pageCount: Int, snapshot: Map<Int, TextPageIndexState>) {
        private val states = arrayOfNulls<TextPageIndexState>(pageCount)
        private val attempted = BooleanArray(pageCount)
        private val completePages = mutableSetOf<Int>()
        var indexedPages = 0
            private set
        var failedPages = 0
            private set
        private var cursor = 0

        init {
            snapshot.forEach { (page, state) -> if (page in states.indices) updateState(page, state) }
        }

        fun hasNextPage(): Boolean {
            while (cursor < states.size && (states[cursor] == TextPageIndexState.COMPLETE || attempted[cursor])) cursor++
            return cursor < states.size
        }

        fun claimNextPage(): Int? {
            if (!hasNextPage()) return null
            return cursor.also { attempted[it] = true }
        }

        fun record(pageIndex: Int, result: TextPageLoadResult) {
            if (pageIndex !in states.indices) return
            updateState(
                pageIndex,
                if (result is TextPageLoadResult.Loaded) TextPageIndexState.COMPLETE else TextPageIndexState.FAILED
            )
        }

        fun completePages(): Set<Int> = completePages

        private fun updateState(pageIndex: Int, state: TextPageIndexState) {
            when (states[pageIndex]) {
                TextPageIndexState.COMPLETE -> indexedPages--
                TextPageIndexState.FAILED -> failedPages--
                else -> Unit
            }
            states[pageIndex] = state
            when (state) {
                TextPageIndexState.COMPLETE -> {
                    indexedPages++
                    completePages += pageIndex
                }
                TextPageIndexState.FAILED -> failedPages++
                TextPageIndexState.IN_PROGRESS -> Unit
            }
            if (state != TextPageIndexState.COMPLETE) completePages -= pageIndex
        }
    }

    private val lock = Object()
    private val cache = LinkedHashMap<Int, CachedPage>(4, .75f, true)
    private val worker = threadFactory(Runnable(::workLoop))

    private var cacheBytes = 0L
    private var latestRequest: Request? = null
    private var activePageIndex: Int? = null
    private var nextRequestId = 0L
    private var currentRequestId = 0L
    private var pendingForegroundDelivery: PendingDelivery? = null
    private var pendingSearchDelivery: PendingDelivery? = null
    private var searchRequest: SearchRequest? = null
    private var nextSearchGeneration = 0L
    private var searchDirty = false
    private var closed = false

    private class PendingDelivery {
        private val completed = CountDownLatch(1)
        @Volatile private var cancelled = false

        fun cancel() {
            cancelled = true
            completed.countDown()
        }

        fun complete() = completed.countDown()
        fun isCancelled(): Boolean = cancelled

        fun await() {
            var interrupted = false
            while (completed.count > 0L) {
                try {
                    completed.await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    init {
        require(pageCount > 0)
        require(maxCacheBytes > 0)
        require((index == null) == (indexKey == null))
        worker.start()
    }

    override fun load(pageIndex: Int, callback: (TextPageLoadResult) -> Unit) {
        require(pageIndex in 0 until pageCount)

        synchronized(lock) {
            if (closed) return

            val requestId = ++nextRequestId
            currentRequestId = requestId
            pendingForegroundDelivery?.cancel()
            latestRequest = Request(requestId, pageIndex, callback)
            lock.notifyAll()
        }
    }

    override fun search(query: String, callback: (TextSearchProgress) -> Unit) {
        val literal = query.trim()
        synchronized(lock) {
            if (closed) return
            pendingSearchDelivery?.cancel()
            searchRequest = if (literal.isEmpty()) null else {
                SearchRequest(++nextSearchGeneration, query, callback, pageCount, onResultPageAggregated)
            }
            searchDirty = searchRequest != null
            lock.notifyAll()
        }
    }

    override fun closeSearch() {
        synchronized(lock) {
            searchRequest = null
            searchDirty = false
            pendingSearchDelivery?.cancel()
            lock.notifyAll()
        }
    }

    /** Suppresses publication immediately and wakes the worker so teardown can drain it. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            latestRequest = null
            searchRequest = null
            searchDirty = false
            pendingForegroundDelivery?.cancel()
            pendingSearchDelivery?.cancel()
            lock.notifyAll()
        }
    }

    /** Blocking. Returns only after the active extraction has left the document. */
    override fun dispose() {
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
            val work = synchronized(lock) {
                while (!closed && latestRequest == null && (searchRequest == null || !searchDirty)) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        // Publication waits restore interruption; this private worker consumes it here.
                    }
                }
                if (closed) return
                val foreground = latestRequest?.also { activePageIndex = it.pageIndex }
                foreground to searchRequest.takeIf { foreground == null }
            }
            val foreground = work.first
            val selectedSearch = work.second
            try {
                if (foreground == null) selectedSearch?.let(::processSearch)
                else processForeground(foreground.pageIndex)
            } catch (_: Exception) {
                if (foreground == null) selectedSearch?.let(::failSearch)
                else failForeground(foreground)
            }
        }
    }

    private fun processForeground(pageIndex: Int) {
            val cached = synchronized(lock) { cache[pageIndex]?.page }
            val extracted = cached?.let(TextPageLoadResult::Loaded) ?: extract(pageIndex)
            val result = if (extracted is TextPageLoadResult.Loaded && !admitIfCurrent(pageIndex, extracted.page)) {
                evict(pageIndex, extracted.page)
                TextPageLoadResult.Failed
            } else {
                extracted
            }
            val activeSearch = synchronized(lock) { searchRequest }
            if (result is TextPageLoadResult.Loaded && activeSearch != null) {
                feedPageIfNeeded(activeSearch, pageIndex, result.page)
            }
            val publication = synchronized(lock) {
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

            publication?.let { (id, callback, completed) -> publish(id, pageIndex, callback, completed) }
            synchronized(lock) {
                searchRequest?.coverage?.record(pageIndex, result)
                if (searchRequest != null) searchDirty = true
            }
    }

    /** Publishes the current indexed snapshot, then extracts at most one missing page. */
    private fun processSearch(request: SearchRequest) {
        if (!isCurrent(request)) return
        val coverage = coverage(request) ?: return
        if (!isCurrent(request)) return
        val running = coverage.hasNextPage()
        if (!request.initialSearchComplete) {
            if (!runInitialSearch(request, coverage, running)) return
        } else {
            publishSearchProgress(request, coverage, running, usePageFence = true)
        }
        if (!isCurrent(request)) return
        val pageIndex = synchronized(lock) {
            if (!isCurrentLocked(request) || latestRequest != null) return
            val claimed = coverage.claimNextPage()
            if (claimed == null) {
                searchDirty = false
                return
            }
            activePageIndex = claimed
            claimed
        }
        var result = extract(pageIndex)
        if (result is TextPageLoadResult.Loaded && !admitIfCurrent(pageIndex, result.page)) {
            evict(pageIndex, result.page)
            result = TextPageLoadResult.Failed
        }
        if (result is TextPageLoadResult.Loaded) feedPageIfNeeded(request, pageIndex, result.page)
        synchronized(lock) {
            activePageIndex = null
            if (isCurrentLocked(request)) {
                coverage.record(pageIndex, result)
                searchDirty = true
            }
            lock.notifyAll()
        }
    }

    private fun coverage(request: SearchRequest): SearchCoverage? {
        val index = index ?: return null
        val key = indexKey ?: return null
        request.coverage?.let { return it }
        return SearchCoverage(
            pageCount,
            index.pageStatesIfCurrent(key(0))
                ?: throw IllegalStateException("text index is no longer current")
        ).also { request.coverage = it }
    }

    private fun runInitialSearch(
        request: SearchRequest,
        coverage: SearchCoverage,
        running: Boolean
    ): Boolean {
        val index = requireNotNull(index)
        val key = requireNotNull(indexKey)
        val outcome = index.searchIfCurrent(key(0).bookId, key(0).documentVersion, request.query) { hits ->
            request.replaceInitial(hits, coverage.completePages())
            request.initialSearchComplete = true
            request.lastUpdatedPage = null
            publishSearchProgress(request, coverage, running)
        }
        if (outcome != TextPagePublicationOutcome.CURRENT) {
            synchronized(lock) { if (isCurrentLocked(request)) searchDirty = false }
            return false
        }
        return true
    }

    private fun feedPageIfNeeded(request: SearchRequest, pageIndex: Int, page: TextPage) {
        if (!isCurrent(request) || !request.claimPage(pageIndex)) return
        val key = requireNotNull(indexKey).invoke(pageIndex)
        val hits = matchPage(page, request.query).mapIndexed { occurrence, match ->
            TextPageSearchHit(
                pageIndex,
                key.source,
                occurrence,
                match.wordRange,
                match.boxes,
                match.snippet
            )
        }
        if (isCurrent(request)) {
            request.replacePage(pageIndex, hits)
            request.lastUpdatedPage = pageIndex
        }
    }

    private fun publishSearchProgress(
        request: SearchRequest,
        coverage: SearchCoverage,
        running: Boolean,
        error: Boolean = false,
        usePageFence: Boolean = false
    ) {
        val deliverProgress = {
            deliverSearchAndWait(request) {
                request.callback(
                    TextSearchProgress(
                        request.query,
                        request.matches(),
                        coverage.indexedPages,
                        coverage.failedPages,
                        pageCount,
                        running,
                        error
                    )
                )
            }
        }
        val page = request.lastUpdatedPage.takeIf { usePageFence }
        if (page == null) {
            deliverProgress()
            return
        }
        val outcome = requireNotNull(index).publishIfCurrent(requireNotNull(indexKey).invoke(page), deliverProgress)
        if (outcome != TextPagePublicationOutcome.CURRENT) {
            throw IllegalStateException("incremental search page is no longer current")
        }
        if (request.lastUpdatedPage == page) request.lastUpdatedPage = null
    }

    private fun failSearch(request: SearchRequest) {
        if (!isCurrent(request)) return
        val coverage = request.coverage
        synchronized(lock) {
            if (!isCurrentLocked(request)) return
            searchDirty = false
            activePageIndex = null
            lock.notifyAll()
        }
        runCatching {
            deliverSearchAndWait(request) {
                request.callback(
                    TextSearchProgress(
                        request.query,
                        request.matches(),
                        coverage?.indexedPages ?: 0,
                        coverage?.failedPages ?: 0,
                        pageCount,
                        running = false,
                        error = true
                    )
                )
            }
        }
    }

    private fun isCurrent(request: SearchRequest): Boolean = synchronized(lock) {
        isCurrentLocked(request)
    }

    private fun isCurrentLocked(request: SearchRequest): Boolean =
        !closed && searchRequest === request

    private fun failForeground(request: Request) {
        synchronized(lock) {
            activePageIndex = null
            if (latestRequest?.id == request.id) latestRequest = null
            lock.notifyAll()
        }
        runCatching { deliverAndWait(request.id) { request.callback(TextPageLoadResult.Failed) } }
    }

    private fun extract(pageIndex: Int): TextPageLoadResult {
        val key = indexKey?.invoke(pageIndex)
        return try {
            if (key != null) index?.load(key)?.let { return TextPageLoadResult.Loaded(it) }
            if (key != null) {
                val started = requireNotNull(index).markInProgress(key)
                if (started.outcome != TextPageIndexWriteOutcome.APPLIED) return TextPageLoadResult.Failed
                if (started.previousState == TextPageIndexState.COMPLETE) {
                    index.load(key)?.let { return TextPageLoadResult.Loaded(it) }
                }
            }
            val page = document.extractText(pageIndex)
            if (key != null && index?.complete(key, page) != TextPageIndexWriteOutcome.APPLIED) {
                return TextPageLoadResult.Failed
            }
            TextPageLoadResult.Loaded(page)
        } catch (_: Exception) {
            if (key != null) runCatching { index?.markFailed(key) }
            TextPageLoadResult.Failed
        }
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

    private fun admitIfCurrent(pageIndex: Int, page: TextPage): Boolean {
        val keyFactory = indexKey
        if (keyFactory == null) {
            synchronized(lock) { if (!closed) cache(pageIndex, page) }
            return true
        }
        val outcome = requireNotNull(index).publishIfCurrent(keyFactory(pageIndex)) {
            synchronized(lock) { if (!closed) cache(pageIndex, page) }
        }
        return outcome == TextPagePublicationOutcome.CURRENT
    }

    private fun publish(
        requestId: Long,
        pageIndex: Int,
        callback: (TextPageLoadResult) -> Unit,
        result: TextPageLoadResult
    ) {
        val loaded = result as? TextPageLoadResult.Loaded
        if (loaded == null || indexKey == null) {
            deliverAndWait(requestId) { callback(result) }
            return
        }

        val outcome = requireNotNull(index).publishIfCurrent(indexKey.invoke(pageIndex)) {
            deliverAndWait(requestId) {
                requireNotNull(index).runPublicationCallback { callback(loaded) }
            }
        }
        when (outcome) {
            TextPagePublicationOutcome.CURRENT -> Unit
            TextPagePublicationOutcome.INVALIDATED_DURING_PUBLICATION -> evict(pageIndex, loaded.page)
            TextPagePublicationOutcome.NOT_CURRENT -> {
                evict(pageIndex, loaded.page)
                deliverAndWait(requestId) { callback(TextPageLoadResult.Failed) }
            }
        }
    }

    /** Posts without holding [lock], then keeps the reader-text worker at the publication fence. */
    private fun deliverAndWait(requestId: Long, callback: () -> Unit) {
        val pending = PendingDelivery()
        synchronized(lock) {
            if (closed || currentRequestId != requestId) return
            pendingForegroundDelivery = pending
        }
        try {
            deliver {
                try {
                    if (!pending.isCancelled()) {
                        val current = synchronized(lock) { !closed && currentRequestId == requestId }
                        if (current) callback()
                    }
                } finally {
                    pending.complete()
                }
            }
            pending.await()
        } finally {
            synchronized(lock) {
                if (pendingForegroundDelivery === pending) pendingForegroundDelivery = null
            }
        }
    }

    private fun deliverSearchAndWait(request: SearchRequest, callback: () -> Unit) {
        val pending = PendingDelivery()
        synchronized(lock) {
            if (!isCurrentLocked(request)) return
            pendingSearchDelivery = pending
        }
        try {
            deliver {
                try {
                    val current = synchronized(lock) {
                        isCurrentLocked(request) && !pending.isCancelled()
                    }
                    if (current) callback()
                } finally {
                    pending.complete()
                }
            }
            pending.await()
        } finally {
            synchronized(lock) { if (pendingSearchDelivery === pending) pendingSearchDelivery = null }
        }
    }

    private fun evict(pageIndex: Int, expected: TextPage) = synchronized(lock) {
        val cached = cache[pageIndex]
        if (cached?.page === expected) {
            cache.remove(pageIndex)
            cacheBytes -= cached.bytes
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
