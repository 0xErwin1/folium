package com.folium.reader.reader

import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextPageMatch
import com.folium.reader.core.text.TextPageMatcher
import com.folium.reader.core.text.TextPageMatchResult
import com.folium.reader.core.text.TextSearchError
import com.folium.reader.core.text.TextSearchSpec
import com.folium.reader.core.text.MAX_TEXT_SEARCH_RESULTS
import com.folium.reader.index.TextPageIndex
import com.folium.reader.index.TextPageIndexKey
import com.folium.reader.index.TextPageIndexState
import com.folium.reader.index.TextPageIndexWriteOutcome
import com.folium.reader.index.TextPagePublicationOutcome
import com.folium.reader.index.TextPageSearchHit
import com.folium.reader.index.OcrPageKey
import com.folium.reader.core.ocr.OcrCancellationReason
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.ArrayDeque

private const val DEFAULT_TEXT_CACHE_BYTES = 4L * 1024 * 1024
private const val SEARCH_PUBLICATION_INTERVAL_NANOS = 150_000_000L
/** Foreground pages preempt this FIFO; the bound also caps how long accepted OCR work can delay search. */
internal const val MAX_OCR_COMMAND_QUEUE = 64

private enum class OcrSessionAvailability { NOT_CONFIGURED, AVAILABLE, UNAVAILABLE }

internal data class SearchPublicationClaim(val generation: Long, val running: Boolean)

internal data class SearchPublicationContext(
    val generation: Long,
    val pageUpdates: List<PendingPageUpdate>
)

internal class SearchPublicationGate(initialGeneration: Long) {
    private var generation = initialGeneration
    private var terminalClaimed = false

    @Synchronized fun claim(
        running: Boolean,
        expectedGeneration: Long = generation
    ): SearchPublicationClaim? {
        if (expectedGeneration != generation) return null
        if (terminalClaimed) return null
        if (!running) terminalClaimed = true
        return SearchPublicationClaim(generation, running)
    }

    @Synchronized fun canDeliver(claim: SearchPublicationClaim): Boolean =
        claim.generation == generation && (!claim.running || !terminalClaimed)

    @Synchronized fun nextGeneration() {
        generation++
        terminalClaimed = false
    }

    @Synchronized fun currentGeneration(): Long = generation
}

internal data class SearchCoverageSnapshot(
    val completePages: Set<Int>,
    val indexedPages: Int,
    val failedPages: Int,
    val running: Boolean
)

internal class SearchCoverage(pageCount: Int, snapshot: Map<Int, TextPageIndexState>) {
    private val states = arrayOfNulls<TextPageIndexState>(pageCount)
    private val attempted = BooleanArray(pageCount)
    private val completePages = mutableSetOf<Int>()
    private var indexedPages = 0
    private var failedPages = 0
    private var cursor = 0
    private var running = true

    init {
        snapshot.forEach { (page, state) -> if (page in states.indices) updateState(page, state) }
    }

    @Synchronized fun claimNextPage(): Int? {
        while (cursor < states.size && (
                states[cursor] == TextPageIndexState.COMPLETE ||
                    states[cursor] == TextPageIndexState.FAILED || attempted[cursor]
                )) cursor++
        if (cursor >= states.size) return null

        return cursor.also { attempted[it] = true }
    }

    @Synchronized fun record(pageIndex: Int, result: TextPageLoadResult) {
        if (pageIndex !in states.indices) return
        updateState(
            pageIndex,
            if (result is TextPageLoadResult.Loaded) TextPageIndexState.COMPLETE else TextPageIndexState.FAILED
        )
    }

    @Synchronized fun setMaintenancePending(pending: Boolean) {
        running = pending
    }

    @Synchronized fun snapshot(): SearchCoverageSnapshot = SearchCoverageSnapshot(
        completePages = completePages.toSet(),
        indexedPages = indexedPages,
        failedPages = failedPages,
        running = running
    )

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

internal data class PendingPageUpdate(
    val pageIndex: Int,
    val source: com.folium.reader.core.text.TextSource,
    val revision: Long
)

internal class PendingPageUpdates {
    private val pendingByPage = mutableMapOf<Int, PendingPageUpdate>()
    private val latestRevisionByPage = mutableMapOf<Int, Long>()
    private var nextRevision = 0L

    @Synchronized fun record(
        pageIndex: Int,
        source: com.folium.reader.core.text.TextSource
    ): PendingPageUpdate = PendingPageUpdate(pageIndex, source, ++nextRevision).also { update ->
        pendingByPage[pageIndex] = update
        latestRevisionByPage[pageIndex] = update.revision
    }

    @Synchronized fun capture(): List<PendingPageUpdate> =
        pendingByPage.values.sortedBy(PendingPageUpdate::pageIndex).also { captured ->
            captured.forEach { update ->
                if (pendingByPage[update.pageIndex]?.revision == update.revision) {
                    pendingByPage.remove(update.pageIndex)
                }
            }
        }

    @Synchronized fun isLatest(update: PendingPageUpdate): Boolean =
        latestRevisionByPage[update.pageIndex] == update.revision

    @Synchronized fun areLatest(updates: List<PendingPageUpdate>): Boolean =
        updates.all { update -> latestRevisionByPage[update.pageIndex] == update.revision }

    @Synchronized fun hasPending(): Boolean = pendingByPage.isNotEmpty()
}

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
    val error: Boolean = false,
    val searchError: TextSearchError? = null,
    val spec: TextSearchSpec = TextSearchSpec(query),
    val truncated: Boolean = false
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
    private val ocrKey: ((Int) -> OcrPageKey)? = null,
    private val initialOcrFailure: Throwable? = null,
    private val onOcrEligible: (Int) -> Unit = {},
    private val matchPage: ((TextPage, String) -> List<TextPageMatch>)? = null,
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
    internal class SearchRequest(
        val generation: Long,
        val spec: TextSearchSpec,
        val callback: (TextSearchProgress) -> Unit,
        pageCount: Int,
        private val onResultPageAggregated: () -> Unit
    ) {
        private val matchesByPage = TreeMap<Int, List<TextPageSearchHit>>()
        private var matchCount = 0
        var truncated = false
        private val matchedPages = BooleanArray(pageCount)
        private val pageVersions = LongArray(pageCount)
        @Volatile var initialSearchComplete = false
        var lastPublicationNanos = 0L
        private val publicationGate = SearchPublicationGate(generation)
        private val pendingPageUpdates = PendingPageUpdates()
        private var refreshPending = false
        private var fullRefreshPending = false

        @Synchronized fun replaceInitial(
            result: com.folium.reader.index.TextPageSearchResult,
            completePages: Set<Int>,
            versionsAtQueryStart: LongArray
        ) {
            val grouped = result.hits.take(MAX_TEXT_SEARCH_RESULTS).groupBy(TextPageSearchHit::pageIndex)
            (completePages + grouped.keys).forEach { page ->
                if (page !in matchedPages.indices || pageVersions[page] != versionsAtQueryStart[page]) {
                    return@forEach
                }
                matchCount -= matchesByPage.remove(page)?.size ?: 0
                grouped[page]?.takeIf(List<TextPageSearchHit>::isNotEmpty)?.let { pageHits ->
                    matchesByPage[page] = pageHits
                    matchCount += pageHits.size
                    onResultPageAggregated()
                }
            }
            truncated = result.truncated || result.hits.size > MAX_TEXT_SEARCH_RESULTS
            refreshPending = false
            completePages.forEach { if (it in matchedPages.indices) matchedPages[it] = true }
        }

        @Synchronized fun pageVersions(): LongArray = pageVersions.copyOf()

        @Synchronized fun claimPage(pageIndex: Int): Boolean {
            if (pageIndex !in matchedPages.indices || matchedPages[pageIndex]) return false
            matchedPages[pageIndex] = true
            return true
        }

        @Synchronized fun beginPageReplacement(pageIndex: Int, selectedSourceRefresh: Boolean): Int {
            if (pageIndex !in matchedPages.indices) return 0
            if (selectedSourceRefresh && truncated) refreshPending = true
            val previousSize = matchesByPage[pageIndex]?.size ?: 0
            matchCount -= previousSize
            matchesByPage.remove(pageIndex)
            return (MAX_TEXT_SEARCH_RESULTS - matchCount).coerceAtLeast(0)
        }

        @Synchronized fun finishPageReplacement(pageIndex: Int, hits: List<TextPageSearchHit>) {
            if (pageIndex !in matchedPages.indices) return
            pageVersions[pageIndex]++
            if (hits.isNotEmpty()) matchesByPage[pageIndex] = hits
            if (hits.isNotEmpty()) {
                onResultPageAggregated()
                matchCount += hits.size
            }
        }

        @Synchronized fun matches(): List<TextPageSearchHit> =
            matchesByPage.values.asSequence().flatten().take(MAX_TEXT_SEARCH_RESULTS).toList()

        @Synchronized fun hasRefreshPending(): Boolean = refreshPending

        @Synchronized fun recordPageUpdate(
            pageIndex: Int,
            source: com.folium.reader.core.text.TextSource
        ): PendingPageUpdate {
            publicationGate.nextGeneration()
            lastPublicationNanos = 0L
            return pendingPageUpdates.record(pageIndex, source)
        }

        @Synchronized fun capturePublicationContext(): SearchPublicationContext =
            SearchPublicationContext(
                generation = publicationGate.currentGeneration(),
                pageUpdates = pendingPageUpdates.capture()
            )

        @Synchronized fun isLatest(update: PendingPageUpdate): Boolean = pendingPageUpdates.isLatest(update)

        @Synchronized fun hasPendingPageUpdates(): Boolean = pendingPageUpdates.hasPending()

        @Synchronized fun requestFullRefresh() {
            fullRefreshPending = true
        }

        @Synchronized fun requestFullRefreshAndInvalidate() {
            fullRefreshPending = true
            publicationGate.nextGeneration()
            lastPublicationNanos = 0L
        }

        @Synchronized fun captureFullRefresh(): Boolean = fullRefreshPending.also {
            fullRefreshPending = false
        }

        @Synchronized fun claimPublication(
            context: SearchPublicationContext,
            running: Boolean,
            force: Boolean,
            now: Long
        ): SearchPublicationClaim? {
            if (context.generation != publicationGate.currentGeneration() ||
                !pendingPageUpdates.areLatest(context.pageUpdates)) return null
            if (!running) {
                val claim = publicationGate.claim(running, context.generation) ?: return null
                lastPublicationNanos = now
                return claim
            }
            if (!force && lastPublicationNanos != 0L &&
                now - lastPublicationNanos < SEARCH_PUBLICATION_INTERVAL_NANOS) return null
            val claim = publicationGate.claim(running, context.generation) ?: return null
            lastPublicationNanos = now
            return claim
        }

        @Synchronized fun canDeliver(
            context: SearchPublicationContext,
            claim: SearchPublicationClaim
        ): Boolean = context.generation == publicationGate.currentGeneration() &&
            pendingPageUpdates.areLatest(context.pageUpdates) && publicationGate.canDeliver(claim)

    }

    private val lock = Object()
    private val cache = LinkedHashMap<Int, CachedPage>(4, .75f, true)
    private val worker = threadFactory(Runnable(::workLoop))
    private val queryWorker = threadFactory(Runnable(::queryLoop)).apply { name = "reader-text-query" }

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
    private var queryInProgress = false
    private var indexCoverage: SearchCoverage? = null
    private var backgroundFailure: Throwable? = null
    private var backgroundDone = index == null
    private var backgroundReady = index == null
    private var backgroundServedSinceOcr = false
    private val ocrCommands = ArrayDeque<OcrSessionCommand>()
    private var ocrPreparationFailure: Throwable? = null
    private var ocrAvailability = OcrSessionAvailability.NOT_CONFIGURED
    private var drainingOcr = false
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
        require(ocrKey == null || index != null)
        worker.start()
        queryWorker.start()
    }

    override fun load(pageIndex: Int, callback: (TextPageLoadResult) -> Unit) {
        require(pageIndex in 0 until pageCount)

        synchronized(lock) {
            if (closed || drainingOcr) return

            val requestId = ++nextRequestId
            currentRequestId = requestId
            pendingForegroundDelivery?.cancel()
            latestRequest = Request(requestId, pageIndex, callback)
            lock.notifyAll()
        }
    }

    override fun search(spec: TextSearchSpec, callback: (TextSearchProgress) -> Unit) {
        val literal = spec.query.trim()
        synchronized(lock) {
            if (closed || drainingOcr) return
            pendingSearchDelivery?.cancel()
            searchRequest = if (literal.isEmpty()) null else {
                SearchRequest(++nextSearchGeneration, spec, callback, pageCount, onResultPageAggregated)
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

    override fun beginOcrDrain() {
        synchronized(lock) {
            if (closed || drainingOcr) return
            drainingOcr = true
            latestRequest = null
            searchRequest = null
            searchDirty = false
            pendingForegroundDelivery?.cancel()
            pendingSearchDelivery?.cancel()
            lock.notifyAll()
        }
    }

    override fun ocrStatus(pageIndex: Int, callback: (OcrCommandResult<com.folium.reader.core.ocr.OcrPageStatus?>) -> Unit) =
        enqueueOcr(OcrSessionCommand.Status(pageIndex, callback))

    override fun claimOcr(pageIndex: Int, callback: (OcrCommandResult<com.folium.reader.index.OcrTransition>) -> Unit) =
        enqueueOcr(OcrSessionCommand.Claim(pageIndex, callback))

    override fun completeOcr(
        attempt: com.folium.reader.index.OcrAttempt,
        page: TextPage,
        callback: (OcrCommandResult<com.folium.reader.index.OcrTransition>) -> Unit
    ) = enqueueOcr(OcrSessionCommand.Complete(attempt, page, callback))

    override fun failOcr(
        attempt: com.folium.reader.index.OcrAttempt,
        failureKind: String,
        retryable: Boolean,
        callback: (OcrCommandResult<com.folium.reader.index.OcrTransition>) -> Unit
    ) = enqueueOcr(OcrSessionCommand.Fail(attempt, failureKind, retryable, callback))

    override fun cancelOcr(
        attempt: com.folium.reader.index.OcrAttempt,
        reason: OcrCancellationReason,
        callback: (OcrCommandResult<com.folium.reader.index.OcrTransition>) -> Unit
    ) = enqueueOcr(OcrSessionCommand.Cancel(attempt, reason, callback))

    override fun resumePausedOcr(
        pageIndex: Int,
        callback: (OcrCommandResult<com.folium.reader.index.OcrTransition>) -> Unit
    ) = enqueueOcr(OcrSessionCommand.ResumePaused(pageIndex, callback))

    override fun retryOcr(
        pageIndex: Int,
        callback: (OcrCommandResult<com.folium.reader.index.OcrTransition>) -> Unit
    ) = enqueueOcr(OcrSessionCommand.Retry(pageIndex, callback))

    /** Suppresses publication immediately and wakes the worker so teardown can drain it. */
    override fun close() {
        val abandoned = synchronized(lock) {
            if (closed) return
            closed = true
            latestRequest = null
            searchRequest = null
            searchDirty = false
            pendingForegroundDelivery?.cancel()
            pendingSearchDelivery?.cancel()
            val commands = ocrCommands.toList().also { ocrCommands.clear() }
            lock.notifyAll()
            commands
        }
        abandoned.forEach { command -> deliver(command::closed) }
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
        while (queryWorker.isAlive) {
            try {
                queryWorker.join()
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
        prepareOcrSession()
        try {
            prepareBackgroundCoverage()
        } catch (failure: Throwable) {
            backgroundFailure = failure
            synchronized(lock) { backgroundDone = true; backgroundReady = true; lock.notifyAll() }
        }
        while (true) {
            val work = synchronized(lock) {
                while (!closed && latestRequest == null && ocrCommands.isEmpty() &&
                    (backgroundDone || drainingOcr)) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        // Publication waits restore interruption; this private worker consumes it here.
                    }
                }
                if (closed) return
                val foreground = latestRequest?.takeUnless { drainingOcr }?.also {
                    activePageIndex = it.pageIndex
                }
                val chooseOcr = foreground == null && ocrCommands.isNotEmpty() &&
                    (backgroundDone || backgroundServedSinceOcr || drainingOcr)
                val command = if (chooseOcr) ocrCommands.pollFirst() else null
                if (command != null) backgroundServedSinceOcr = false
                if (foreground == null && command == null) backgroundServedSinceOcr = true
                Triple(foreground, command, !drainingOcr && foreground == null && command == null)
            }
            val foreground = work.first
            val command = work.second
            val background = work.third
            try {
                if (command != null) processOcrCommand(command)
                else if (background) processBackgroundSlice()
                else processForeground(requireNotNull(foreground).pageIndex)
            } catch (failure: Exception) {
                if (command != null) runCatching {
                    deliverOcr(command) { command.persistenceFailure(failure) }
                }
                else if (background) failBackground()
                else failForeground(requireNotNull(foreground))
            }
        }
    }

    private fun queryLoop() {
        while (true) {
            val request = synchronized(lock) {
                while (!closed && (drainingOcr || searchRequest == null || !searchDirty || !backgroundReady)) {
                    try { lock.wait() } catch (_: InterruptedException) { }
                }
                if (closed) return
                searchDirty = false
                queryInProgress = true
                searchRequest
            } ?: continue
            val context = request.capturePublicationContext()
            try {
                processQuery(request, context)
            } catch (_: Exception) {
                failSearch(request, context)
            } finally {
                val terminal = synchronized(lock) {
                    queryInProgress = false
                    lock.notifyAll()
                    request.takeIf {
                        isCurrentLocked(it) && backgroundDone && !searchDirty && it.initialSearchComplete
                    }
                }
                val coverage = indexCoverage?.snapshot()
                if (terminal != null && coverage != null) {
                    publishSearchProgress(
                        terminal,
                        context,
                        coverage,
                        running = false,
                        force = true
                    )
                }
            }
        }
    }

    private fun prepareBackgroundCoverage() {
        val target = index ?: return
        val key = indexKey ?: return
        indexCoverage = SearchCoverage(
            pageCount,
            target.pageStatesIfCurrent(key(0))
                ?: throw IllegalStateException("text index is no longer current")
        )
        synchronized(lock) {
            backgroundReady = true
            backgroundDone = false
            lock.notifyAll()
        }
    }

    private fun prepareOcrSession() {
        initialOcrFailure?.let {
            ocrPreparationFailure = it
            ocrAvailability = OcrSessionAvailability.UNAVAILABLE
            return
        }
        val target = index ?: return
        val keyFactory = ocrKey ?: return
        try {
            check(target.prepareOcr(keyFactory(0)) == com.folium.reader.index.OcrTransitionOutcome.APPLIED) {
                "OCR state ownership changed during session preparation"
            }
            ocrAvailability = OcrSessionAvailability.AVAILABLE
        } catch (failure: Throwable) {
            ocrPreparationFailure = failure
            ocrAvailability = OcrSessionAvailability.UNAVAILABLE
        }
    }

    private fun enqueueOcr(command: OcrSessionCommand) {
        val rejection = synchronized(lock) {
            if (closed) OcrCommandError.CLOSED
            else if (drainingOcr && !command.isTerminalReport()) OcrCommandError.CLOSED
            else if (ocrCommands.size >= MAX_OCR_COMMAND_QUEUE) OcrCommandError.OVERFLOW
            else {
                ocrCommands.addLast(command)
                lock.notifyAll()
                null
            }
        }
        when (rejection) {
            OcrCommandError.CLOSED -> deliver(command::closed)
            OcrCommandError.OVERFLOW -> deliver { command.commandFailure(OcrCommandError.OVERFLOW) }
            else -> Unit
        }
    }

    private fun processOcrCommand(command: OcrSessionCommand) {
        val pageIndex = when (command) {
            is OcrSessionCommand.Status -> command.pageIndex
            is OcrSessionCommand.Claim -> command.pageIndex
            is OcrSessionCommand.Retry -> command.pageIndex
            is OcrSessionCommand.ResumePaused -> command.pageIndex
            is OcrSessionCommand.Complete -> command.attempt.key.pageIndex
            is OcrSessionCommand.Fail -> command.attempt.key.pageIndex
            is OcrSessionCommand.Cancel -> command.attempt.key.pageIndex
        }
        if (pageIndex !in 0 until pageCount) {
            deliverOcr(command) { command.commandFailure(OcrCommandError.INVALID_PAGE) }
            return
        }
        if (command is OcrSessionCommand.Complete &&
            command.page.source != com.folium.reader.core.text.TextSource.OCR) {
            deliverOcr(command) { command.commandFailure(OcrCommandError.INVALID_REQUEST) }
            return
        }
        if (command is OcrSessionCommand.Fail && command.failureKind.isBlank()) {
            deliverOcr(command) { command.commandFailure(OcrCommandError.INVALID_REQUEST) }
            return
        }
        when (ocrAvailability) {
            OcrSessionAvailability.UNAVAILABLE -> {
                val failure = ocrPreparationFailure
                deliverOcr(command) {
                    command.commandFailure(OcrCommandError.UNAVAILABLE, failure)
                }
                return
            }
            OcrSessionAvailability.NOT_CONFIGURED -> {
                deliverOcr(command) { command.commandFailure(OcrCommandError.NOT_CONFIGURED) }
                return
            }
            OcrSessionAvailability.AVAILABLE -> Unit
        }
        val target = requireNotNull(index) { "OCR index is not configured" }
        val keyFactory = requireNotNull(ocrKey) { "OCR ownership is not configured" }
        when (command) {
            is OcrSessionCommand.Status -> {
                val result = target.ocrStatus(keyFactory(command.pageIndex))
                deliverOcr(command) { command.callback(OcrCommandResult.Success(result)) }
            }
            is OcrSessionCommand.Claim -> {
                val result = target.claimOcr(keyFactory(command.pageIndex))
                recordSelectedSourceUpdate(command.pageIndex)
                deliverOcr(command) { command.callback(OcrCommandResult.Success(result)) }
            }
            is OcrSessionCommand.Complete -> {
                val result = target.completeOcr(command.attempt, command.page)
                recordSelectedSourceUpdate(command.attempt.key.pageIndex)
                deliverOcr(command) { command.callback(OcrCommandResult.Success(result)) }
            }
            is OcrSessionCommand.Fail -> {
                val result = target.failOcr(command.attempt, command.failureKind, command.retryable)
                recordSelectedSourceUpdate(command.attempt.key.pageIndex)
                deliverOcr(command) { command.callback(OcrCommandResult.Success(result)) }
            }
            is OcrSessionCommand.Cancel -> {
                val result = target.cancelOcr(command.attempt, command.reason)
                recordSelectedSourceUpdate(command.attempt.key.pageIndex)
                deliverOcr(command) { command.callback(OcrCommandResult.Success(result)) }
            }
            is OcrSessionCommand.ResumePaused -> {
                val result = target.resumePausedOcr(keyFactory(command.pageIndex))
                recordSelectedSourceUpdate(command.pageIndex)
                deliverOcr(command) { command.callback(OcrCommandResult.Success(result)) }
            }
            is OcrSessionCommand.Retry -> {
                val result = target.retryOcr(keyFactory(command.pageIndex))
                recordSelectedSourceUpdate(command.pageIndex)
                deliverOcr(command) { command.callback(OcrCommandResult.Success(result)) }
            }
        }
    }

    private fun recordSelectedSourceUpdate(pageIndex: Int) {
        val selected = runCatching {
            requireNotNull(index).loadSelected(
                requireNotNull(indexKey).invoke(pageIndex),
                requireNotNull(ocrKey).invoke(pageIndex)
            )
        }.getOrNull()
        synchronized(lock) {
            searchRequest?.let { request ->
                if (selected == null) request.requestFullRefreshAndInvalidate()
                else request.recordPageUpdate(pageIndex, selected.source)
                searchDirty = true
            }
            lock.notifyAll()
        }
    }

    private fun deliverOcr(command: OcrSessionCommand, callback: () -> Unit) {
        deliver {
            if (synchronized(lock) { closed }) command.closed() else callback()
        }
    }

    private fun processForeground(pageIndex: Int) {
            val result = resolveForeground(pageIndex)
            val activeSearch = synchronized(lock) { searchRequest }
            if (result is TextPageLoadResult.Loaded && activeSearch != null) {
                synchronized(lock) {
                    if (isCurrentLocked(activeSearch)) {
                        activeSearch.recordPageUpdate(pageIndex, result.page.source)
                        searchDirty = true
                        lock.notifyAll()
                    }
                }
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
                indexCoverage?.record(pageIndex, result)
            }
    }

    private fun resolveForeground(pageIndex: Int): TextPageLoadResult {
        val cached = synchronized(lock) { cache[pageIndex]?.page }
        val first = cached?.let(TextPageLoadResult::Loaded) ?: extract(pageIndex)
        if (first !is TextPageLoadResult.Loaded || admitIfCurrent(pageIndex, first.page)) return first
        evict(pageIndex, first.page)
        if (cached == null) return TextPageLoadResult.Failed

        val current = extract(pageIndex)
        if (current !is TextPageLoadResult.Loaded || admitIfCurrent(pageIndex, current.page)) return current
        evict(pageIndex, current.page)
        return TextPageLoadResult.Failed
    }

    /** Query lane: reads indexed text only. It never calls [extract] or touches the PDF engine. */
    private fun processQuery(
        request: SearchRequest,
        context: SearchPublicationContext
    ) {
        if (!isCurrent(request)) return
        TextPageMatcher.validate(request.spec)?.let { error ->
            publishSearchError(request, context, error)
            return
        }
        val coverage = coverageSnapshot() ?: return
        if (!isCurrent(request)) return
        val updates = context.pageUpdates
        val fullRefresh = request.captureFullRefresh()
        if (!request.initialSearchComplete || fullRefresh) {
            runInitialSearch(request, context, coverage)
        } else if (updates.isNotEmpty()) {
            runPageUpdates(request, context, coverage)
        } else {
            publishSearchProgress(request, context, coverage, running = coverage.running)
        }
    }

    private fun coverageSnapshot(): SearchCoverageSnapshot? {
        if (index == null || indexKey == null) return null
        backgroundFailure?.let { throw IllegalStateException("text indexing did not start", it) }
        return requireNotNull(indexCoverage).snapshot()
    }

    private fun runInitialSearch(
        request: SearchRequest,
        context: SearchPublicationContext,
        coverage: SearchCoverageSnapshot
    ): Boolean {
        val index = requireNotNull(index)
        val key = requireNotNull(indexKey)
        val completePagesAtQueryStart = coverage.completePages
        val pageVersionsAtQueryStart = request.pageVersions()
        val outcome = index.searchIfCurrent(
            key(0).bookId,
            key(0).documentVersion,
            request.spec,
            includeOcr = ocrAvailability == OcrSessionAvailability.AVAILABLE,
            limit = MAX_TEXT_SEARCH_RESULTS
        ) { result ->
            if (!isCurrent(request)) return@searchIfCurrent
            request.replaceInitial(result, completePagesAtQueryStart, pageVersionsAtQueryStart)
            request.initialSearchComplete = true
            publishSearchProgress(request, context, coverage, running = true)
        }
        if (outcome != TextPagePublicationOutcome.CURRENT) {
            return false
        }
        return true
    }

    private fun runPageUpdates(
        request: SearchRequest,
        context: SearchPublicationContext,
        coverage: SearchCoverageSnapshot
    ) {
        val updates = context.pageUpdates
        updates.forEach { update ->
            if (!isCurrent(request)) return
            val page = requireNotNull(index).load(pageKey(update))
            if (page == null) {
                request.beginPageReplacement(update.pageIndex, selectedSourceRefresh = true)
                request.finishPageReplacement(update.pageIndex, emptyList())
            } else {
                feedPageIfNeeded(request, context, update.pageIndex, page, replaceExisting = true)
            }
        }
        if (request.hasRefreshPending()) {
            synchronized(lock) {
                if (isCurrentLocked(request)) {
                    request.requestFullRefresh()
                    searchDirty = true
                    lock.notifyAll()
                }
            }
        }
        val running = coverage.running || request.hasPendingPageUpdates() ||
            synchronized(lock) { searchDirty }
        publishSearchProgress(
            request,
            context,
            coverage,
            running = running,
            force = true
        )
    }

    private fun feedPageIfNeeded(
        request: SearchRequest,
        context: SearchPublicationContext,
        pageIndex: Int,
        page: TextPage,
        replaceExisting: Boolean = false
    ) {
        if (!isCurrent(request) || (!replaceExisting && !request.claimPage(pageIndex))) return
        val key = requireNotNull(indexKey).invoke(pageIndex)
        val remaining = request.beginPageReplacement(pageIndex, replaceExisting)
        val result = if (request.spec == TextSearchSpec(request.spec.query) && matchPage != null) {
            val matches = matchPage.invoke(page, request.spec.query)
            TextPageMatchResult.Success(matches.take(remaining), matches.size > remaining)
        } else {
            TextPageMatcher.find(page, request.spec, limit = remaining)
        }
        if (result is TextPageMatchResult.Failure) {
            request.finishPageReplacement(pageIndex, emptyList())
            publishSearchError(request, context, result.error)
            return
        }
        result as TextPageMatchResult.Success
        if (result.truncated) request.truncated = true
        val hits = result.matches.take(remaining).mapIndexed { occurrence, match ->
            TextPageSearchHit(
                pageIndex,
                page.source,
                occurrence,
                match.wordRange,
                match.boxes,
                match.snippet
            )
        }
        if (isCurrent(request)) {
            request.finishPageReplacement(pageIndex, hits)
        }
    }

    private fun publishSearchProgress(
        request: SearchRequest,
        context: SearchPublicationContext,
        coverage: SearchCoverageSnapshot,
        running: Boolean,
        error: TextSearchError? = null,
        force: Boolean = false
    ) {
        val now = System.nanoTime()
        val claim = request.claimPublication(context, running, force, now) ?: return
        val deliverProgress = {
            deliverSearchAndWait(request) {
                if (!request.canDeliver(context, claim)) return@deliverSearchAndWait
                request.callback(
                    TextSearchProgress(
                        request.spec.query,
                        request.matches(),
                        coverage.indexedPages,
                        coverage.failedPages,
                        pageCount,
                        running,
                        error != null,
                        error,
                        request.spec,
                        request.truncated
                    )
                )
            }
        }
        if (context.pageUpdates.isEmpty()) {
            deliverProgress()
            return
        }
        publishWithPageFences(request, context.pageUpdates, deliverProgress)
    }

    private fun publishWithPageFences(
        request: SearchRequest,
        updates: List<PendingPageUpdate>,
        publication: () -> Unit
    ) {
        fun publishAt(index: Int) {
            if (index == updates.size) {
                publication()
                return
            }
            val update = updates[index]
            if (!request.isLatest(update)) return
            publishPage(pageKey(update)) {
                if (request.isLatest(update)) publishAt(index + 1)
            }
        }
        publishAt(0)
    }

    private fun pageKey(update: PendingPageUpdate): TextPageIndexKey =
        if (update.source == com.folium.reader.core.text.TextSource.OCR) {
            requireNotNull(ocrKey).invoke(update.pageIndex).textKey()
        } else requireNotNull(indexKey).invoke(update.pageIndex)

    private fun publishSearchError(
        request: SearchRequest,
        context: SearchPublicationContext,
        error: TextSearchError
    ) {
        val coverage = indexCoverage?.snapshot()
        val claim = request.claimPublication(
            context,
            running = false,
            force = true,
            now = System.nanoTime()
        ) ?: return
        deliverSearchAndWait(request) {
            if (!request.canDeliver(context, claim)) return@deliverSearchAndWait
            request.callback(TextSearchProgress(
                request.spec.query, emptyList(), coverage?.indexedPages ?: 0,
                coverage?.failedPages ?: 0, pageCount, running = false, error = true,
                searchError = error, spec = request.spec, truncated = false
            ))
        }
    }

    private fun failSearch(
        request: SearchRequest,
        context: SearchPublicationContext
    ) {
        if (!isCurrent(request)) return
        val coverage = indexCoverage?.snapshot()
        synchronized(lock) {
            if (!isCurrentLocked(request)) return
            activePageIndex = null
            lock.notifyAll()
        }
        runCatching {
            val claim = request.claimPublication(
                context,
                running = false,
                force = true,
                now = System.nanoTime()
            ) ?: return@runCatching
            deliverSearchAndWait(request) {
                if (!request.canDeliver(context, claim)) return@deliverSearchAndWait
                request.callback(
                    TextSearchProgress(
                        request.spec.query,
                        request.matches(),
                        coverage?.indexedPages ?: 0,
                        coverage?.failedPages ?: 0,
                        pageCount,
                        running = false,
                        error = true,
                        searchError = TextSearchError.InvalidPattern,
                        spec = request.spec,
                        truncated = request.truncated
                    )
                )
            }
        }
    }

    private fun processBackgroundSlice() {
        val coverage = requireNotNull(indexCoverage)
        val pageIndex = coverage.claimNextPage()
        if (pageIndex == null) {
            val moreDerived = requireNotNull(index).maintainDerivedData(
                requireNotNull(indexKey).invoke(0),
                ocrKey?.takeIf { ocrAvailability == OcrSessionAvailability.AVAILABLE }?.invoke(0)
            )
            synchronized(lock) {
                coverage.setMaintenancePending(moreDerived)
                backgroundDone = !moreDerived
                if (moreDerived && searchRequest != null) searchDirty = true
                lock.notifyAll()
            }
            val terminal = if (!moreDerived) synchronized(lock) {
                searchRequest?.takeIf { !queryInProgress && !searchDirty }?.let { request ->
                    request to request.capturePublicationContext()
                }
            } else null
            terminal?.let { (request, context) ->
                if (request.initialSearchComplete) {
                    publishSearchProgress(
                        request,
                        context,
                        coverage.snapshot(),
                        running = false,
                        force = true
                    )
                }
            }
            return
        }
        synchronized(lock) { activePageIndex = pageIndex }
        var result = extract(pageIndex)
        if (result is TextPageLoadResult.Loaded && !admitIfCurrent(pageIndex, result.page)) {
            evict(pageIndex, result.page)
            result = TextPageLoadResult.Failed
        }
        synchronized(lock) {
            activePageIndex = null
            coverage.record(pageIndex, result)
            lock.notifyAll()
        }
        val request = synchronized(lock) { searchRequest }
        if (result is TextPageLoadResult.Loaded && request != null &&
            TextPageMatcher.validate(request.spec) == null) {
            synchronized(lock) {
                if (isCurrentLocked(request)) {
                    request.recordPageUpdate(pageIndex, result.page.source)
                    searchDirty = true
                    lock.notifyAll()
                }
            }
        }
    }

    private fun failBackground() {
        val failure = synchronized(lock) {
            activePageIndex = null
            indexCoverage?.setMaintenancePending(false)
            backgroundDone = true
            lock.notifyAll()
            searchRequest?.let { request -> request to request.capturePublicationContext() }
        }
        failure?.let { (request, context) -> failSearch(request, context) }
    }

    private fun isCurrent(request: SearchRequest): Boolean = synchronized(lock) {
        isCurrentLocked(request)
    }

    private fun isCurrentLocked(request: SearchRequest): Boolean =
        !closed && !drainingOcr && searchRequest === request

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
            if (key != null) {
                val ownership = ocrKey?.takeIf {
                    ocrAvailability == OcrSessionAvailability.AVAILABLE
                }?.invoke(pageIndex)
                val persisted = if (ownership == null) index?.load(key)
                else index?.loadSelected(key, ownership)
                persisted?.let {
                    if (ownership != null && it.source == com.folium.reader.core.text.TextSource.NATIVE_PDF &&
                        requireNotNull(index).completeNativeAndReconcile(key, it, ownership) !=
                        TextPageIndexWriteOutcome.APPLIED) {
                        return TextPageLoadResult.Failed
                    }
                    notifyOcrEligibility(pageIndex, ownership)
                    return TextPageLoadResult.Loaded(it)
                }
            }
            if (key != null) {
                val started = requireNotNull(index).markInProgress(key)
                if (started.outcome != TextPageIndexWriteOutcome.APPLIED) return TextPageLoadResult.Failed
                if (started.previousState == TextPageIndexState.COMPLETE) {
                    index.load(key)?.let { return TextPageLoadResult.Loaded(it) }
                }
            }
            val page = document.extractText(pageIndex)
            val completion = if (key != null && ocrKey != null &&
                ocrAvailability == OcrSessionAvailability.AVAILABLE) {
                index?.completeNativeAndReconcile(key, page, ocrKey.invoke(pageIndex))
            } else if (key != null) index?.complete(key, page) else TextPageIndexWriteOutcome.APPLIED
            if (completion != TextPageIndexWriteOutcome.APPLIED) {
                return TextPageLoadResult.Failed
            }
            notifyOcrEligibility(pageIndex, ocrKey?.invoke(pageIndex))
            TextPageLoadResult.Loaded(page)
        } catch (_: Exception) {
            if (key != null) runCatching { index?.markFailed(key) }
            TextPageLoadResult.Failed
        }
    }

    private fun notifyOcrEligibility(pageIndex: Int, ownership: OcrPageKey?) {
        if (ownership != null && index?.ocrStatus(ownership)?.state ==
            com.folium.reader.core.ocr.OcrPageState.QUEUED) {
            onOcrEligible(pageIndex)
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
        val outcome = publishPage(publicationKey(pageIndex, page)) {
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

        val outcome = publishPage(publicationKey(pageIndex, loaded.page)) {
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
            if (closed || drainingOcr || currentRequestId != requestId) return
            pendingForegroundDelivery = pending
        }
        try {
            deliver {
                try {
                    if (!pending.isCancelled()) {
                        val current = synchronized(lock) {
                            !closed && !drainingOcr && currentRequestId == requestId
                        }
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

    private fun publicationKey(pageIndex: Int, page: TextPage): TextPageIndexKey =
        if (page.source == com.folium.reader.core.text.TextSource.OCR) {
            requireNotNull(ocrKey).invoke(pageIndex).textKey()
        } else requireNotNull(indexKey).invoke(pageIndex)

    private fun publishPage(
        key: TextPageIndexKey,
        publication: () -> Unit
    ): TextPagePublicationOutcome = if (ocrAvailability == OcrSessionAvailability.AVAILABLE) {
        requireNotNull(index).publishIfSelected(key, publication)
    } else requireNotNull(index).publishIfCurrent(key, publication)
}

private fun OcrSessionCommand.isTerminalReport(): Boolean =
    this is OcrSessionCommand.Complete || this is OcrSessionCommand.Fail || this is OcrSessionCommand.Cancel

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
