package com.folium.reader.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.folium.reader.R
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSearchError
import com.folium.reader.core.text.TextSearchSpec
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.ocr.OcrCancellationReason
import com.folium.reader.core.ocr.OcrPageState
import com.folium.reader.core.ocr.OcrPageStatus
import com.folium.reader.library.OpenBookRequest
import com.folium.reader.library.documentWork
import java.util.concurrent.Executor

private fun scheduleReaderSearch(delayMillis: Long, action: () -> Unit): () -> Unit {
    val handler = Handler(Looper.getMainLooper())
    val runnable = Runnable(action)
    handler.postDelayed(runnable, delayMillis)
    return { handler.removeCallbacks(runnable) }
}

/** What the reader has to show while, and after, a document is being opened. */
sealed class ReaderScreenState {
    data object Opening : ReaderScreenState()
    data class Reading(
        val ui: ReaderUiState<BorrowedPage>,
        val text: ReaderTextState = ReaderTextState.Loading(ui.state.currentPage),
        val search: ReaderSearchState? = null,
        val ocr: ReaderOcrState? = null
    ) : ReaderScreenState()
    data object Missing : ReaderScreenState()
    data class Unreadable(val failure: PdfFailure) : ReaderScreenState()
}

data class ReaderSearchMatch(
    val identity: ReaderSearchMatchIdentity,
    val pageIndex: Int,
    val wordRange: IntRange,
    val boxes: List<PageSpaceRect>,
    val snippet: String
)

data class ReaderSearchMatchIdentity(
    val pageIndex: Int,
    val source: com.folium.reader.core.text.TextSource,
    val occurrenceIndex: Int
) {
    constructor(pageIndex: Int, occurrenceIndex: Int) :
        this(pageIndex, com.folium.reader.core.text.TextSource.NATIVE_PDF, occurrenceIndex)
}

data class ReaderSearchCoverage(
    val indexedPages: Int,
    val failedPages: Int,
    val totalPages: Int,
    val running: Boolean,
    val error: Boolean = false
)

enum class ReaderSearchPending {
    DEBOUNCE,
    QUERY
}

data class ReaderSearchState(
    val spec: TextSearchSpec,
    val matches: List<ReaderSearchMatch> = emptyList(),
    val activeIdentity: ReaderSearchMatchIdentity? = null,
    val coverage: ReaderSearchCoverage = ReaderSearchCoverage(0, 0, 0, true),
    val pending: ReaderSearchPending? = null,
    val error: TextSearchError? = null,
    val truncated: Boolean = false
) {
    constructor(query: String) : this(TextSearchSpec(query))
    val query: String get() = spec.query
    val activeIndex: Int? get() = activeIdentity?.let { identity -> matches.indexOfFirst { it.identity == identity } }
        ?.takeIf { it >= 0 }
    val activeMatch: ReaderSearchMatch? get() = activeIndex?.let(matches::get)
}

internal fun ReaderSearchState?.merge(progress: TextSearchProgress): ReaderSearchState {
    if (this?.spec == progress.spec && !coverage.running && progress.running) return this
    val matches = progress.matches.map {
        ReaderSearchMatch(
            ReaderSearchMatchIdentity(it.pageIndex, it.source, it.occurrenceIndex),
            it.pageIndex, it.wordRange, it.boxes, it.snippet
        )
    }
    val retained = this?.activeIdentity?.takeIf { identity -> matches.any { it.identity == identity } }
    return ReaderSearchState(
        spec = progress.spec,
        matches = matches,
        activeIdentity = retained ?: matches.firstOrNull()?.identity,
        coverage = ReaderSearchCoverage(
            progress.indexedPages,
            progress.failedPages,
            progress.totalPages,
            progress.running,
            progress.error
        ),
        pending = null,
        error = progress.searchError,
        truncated = progress.truncated
    )
}

internal fun ReaderSearchState.moveActiveBy(delta: Int): Pair<ReaderSearchState, Int?> {
    val active = activeIndex ?: return this to null
    val target = (active + delta).coerceIn(0, matches.lastIndex)
    if (target == active) return this to null
    val match = matches[target]
    return copy(activeIdentity = match.identity) to match.pageIndex
}

internal fun ReaderSearchState.withoutOcrPage(pageIndex: Int): ReaderSearchState {
    val retained = matches.filterNot {
        it.pageIndex == pageIndex && it.identity.source == com.folium.reader.core.text.TextSource.OCR
    }
    if (retained.size == matches.size) return this
    val active = activeIdentity?.takeIf { identity -> retained.any { it.identity == identity } }
    return copy(matches = retained, activeIdentity = active ?: retained.firstOrNull()?.identity)
}

internal data class ReaderSearchUpdate(
    val state: ReaderSearchState,
    val navigation: GestureIntent.FlingToPage?
)

internal fun ReaderSearchState.mergeWithInitialNavigation(
    progress: TextSearchProgress,
    currentPage: Int
): ReaderSearchUpdate {
    val gainedFirstMatch = activeIdentity == null && progress.matches.isNotEmpty()
    val merged = merge(progress)
    val target = merged.activeMatch?.pageIndex
        ?.takeIf { gainedFirstMatch && it != currentPage }
        ?.let(GestureIntent::FlingToPage)
    return ReaderSearchUpdate(merged, target)
}

sealed class ReaderTextState {
    abstract val pageIndex: Int

    data class Loading(override val pageIndex: Int) : ReaderTextState()
    data class Loaded(override val pageIndex: Int, val page: TextPage) : ReaderTextState()
    data class Failed(override val pageIndex: Int) : ReaderTextState()
}

data class ReaderOcrState(
    val pageIndex: Int,
    val status: OcrPageStatus? = null,
    val retryPending: Boolean = false,
    val retryFailed: Boolean = false,
    val unavailable: Boolean = false
) {
    val retryAvailable: Boolean get() = !retryPending && when (status?.state) {
        OcrPageState.FAILED -> status.failure?.retryable == true
        OcrPageState.CANCELLED -> status.cancellationReason != OcrCancellationReason.NATIVE_TEXT
        else -> false
    }

    val visible: Boolean get() = unavailable || retryFailed || retryPending || when (status?.state) {
        OcrPageState.QUEUED,
        OcrPageState.RUNNING,
        OcrPageState.FAILED,
        OcrPageState.STALE -> true
        OcrPageState.CANCELLED -> status.cancellationReason != OcrCancellationReason.NATIVE_TEXT
        else -> false
    }
}

internal fun ReaderOcrState?.accepts(status: OcrPageStatus): Boolean {
    val current = this?.status ?: return true
    if (status.generation != current.generation) return status.generation > current.generation
    return status.state.progressRank() >= current.state.progressRank()
}

private fun OcrPageState.progressRank(): Int = when (this) {
    OcrPageState.QUEUED -> 0
    OcrPageState.RUNNING -> 1
    OcrPageState.COMPLETED,
    OcrPageState.FAILED,
    OcrPageState.CANCELLED,
    OcrPageState.STALE -> 2
}

internal fun ReaderTextState.selectablePage(currentPage: Int): TextPage? =
    (this as? ReaderTextState.Loaded)?.takeIf { it.pageIndex == currentPage }?.page

internal fun TextPageLoadResult.toReaderTextState(pageIndex: Int): ReaderTextState = when (this) {
    is TextPageLoadResult.Loaded -> ReaderTextState.Loaded(pageIndex, page)
    TextPageLoadResult.Failed -> ReaderTextState.Failed(pageIndex)
}

object ReaderHostTestTags {
    const val OPENING = "reader-opening"
    const val FAILURE = "reader-failure"
    const val FAILURE_ACTION = "reader-failure-action"
}

/**
 * Owns one document's session for as long as the reader is on screen.
 *
 * Every open and every teardown runs on [documentWork], the same app-wide worker
 * [com.folium.reader.library.LibraryController] uses for import and library file I/O (design
 * decision D-T1). The rendering engine allows a single document session at a time, so reopening a
 * second document must not begin until the first has genuinely finished closing, and an import
 * batch must not overlap a live reader session; serializing every such operation onto one thread
 * makes both orderings structural rather than something a caller has to time.
 *
 * A session can finish opening after the reader has already left — the open is not interruptible
 * once it has started — so the session it produces is handed over under a lock that also records
 * whether anyone is still waiting for it. If nobody is, it is closed straight away rather than
 * being published to a composition that no longer exists.
 */
class ReaderHostController(
    private val context: Context,
    private val request: OpenBookRequest,
    private val onPageChanged: (Int) -> Unit,
    private val onState: (ReaderScreenState) -> Unit,
    private val worker: Executor = documentWork,
    private val mainPost: (() -> Unit) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
    private val scheduleSearch: (Long, () -> Unit) -> (() -> Unit) = ::scheduleReaderSearch,
    private val openSession: (
        Context,
        OpenBookRequest,
        (ReaderUiState<BorrowedPage>) -> Unit
    ) -> ReaderSessionResult = { ctx, req, onChanged -> ReaderSession.open(ctx, req.file, req.book.id, req.initialPage, onChanged) }
) {
    private data class SearchStart(
        val generation: Long,
        val cancellation: (() -> Unit)?,
        val state: ReaderSearchState?
    )

    private val lock = Any()

    @Volatile private var session: ReaderSession? = null
    private var disposed = false
    private var latestUi: ReaderUiState<BorrowedPage>? = null
    private var textPageIndex = -1
    private var textState: ReaderTextState? = null
    private var textGeneration = 0L
    private var ocrState: ReaderOcrState? = null
    private var ocrGeneration = 0L
    private var searchState: ReaderSearchState? = null
    private var searchGeneration = 0L
    private var cancelPendingSearch: (() -> Unit)? = null

    /** Seeded with the restored page so the initial state — already at that page — is not reported as a change. */
    private var lastReportedPage: Int = request.initialPage

    fun start() {
        worker.execute {
            val opened = openSession(context, request) { ui ->
                publishReading(ui)
            }
            publish(opened)
        }
    }

    fun dispose() {
        cancelPendingSearch?.invoke()
        cancelPendingSearch = null
        val abandoned = synchronized(lock) {
            disposed = true
            session.also { session = null }
        }

        abandoned?.let { session ->
            session.observeOcrStatus(null)
            closeThenScheduleDispose(session::close) { worker.execute { session.dispose() } }
        }
    }

    fun dispatch(intent: GestureIntent) = session?.presenter?.dispatch(intent) ?: Unit

    fun setViewport(viewport: ReaderViewport?) = session?.presenter?.setViewport(viewport) ?: Unit

    fun pageAspect(pageIndex: Int): Float = session?.pageAspect(pageIndex) ?: 1f

    fun search(spec: TextSearchSpec) {
        val start = synchronized(lock) {
            val generation = ++searchGeneration
            val cancellation = cancelPendingSearch
            cancelPendingSearch = null
            if (spec.query.isBlank()) {
                searchState = null
                SearchStart(generation, cancellation, null)
            } else {
                val previous = searchState.takeIf { it?.spec == spec }
                val state = ReaderSearchState(
                    spec = spec,
                    activeIdentity = previous?.activeIdentity,
                    coverage = ReaderSearchCoverage(0, 0, session?.pageCount ?: 0, true),
                    pending = ReaderSearchPending.DEBOUNCE
                )
                searchState = state
                SearchStart(generation, cancellation, state)
            }
        }
        val generation = start.generation
        start.cancellation?.invoke()
        if (start.state == null) {
            session?.closeSearch()
            publishLatest()
            return
        }
        publishLatest()
        val cancellation = scheduleSearch(250L) {
            val accepted = synchronized(lock) {
                val current = searchState
                if (disposed || generation != searchGeneration || current?.spec != spec) false
                else {
                    searchState = current.copy(pending = ReaderSearchPending.QUERY)
                    true
                }
            }
            if (!accepted) return@scheduleSearch
            publishLatest()
            val callback: (TextSearchProgress) -> Unit = { progress -> publishSearch(generation, progress) }
            session?.searchText(spec, callback)
        }
        synchronized(lock) {
            if (!disposed && generation == searchGeneration) cancelPendingSearch = cancellation
            else cancellation()
        }
    }

    fun closeSearch() {
        val cancellation = synchronized(lock) {
            searchGeneration++
            cancelPendingSearch.also {
                cancelPendingSearch = null
                searchState = null
            }
        }
        cancellation?.invoke()
        session?.closeSearch()
        publishLatest()
    }

    fun previousSearchResult() = selectSearchResult(-1)
    fun nextSearchResult() = selectSearchResult(1)

    fun retryOcr() {
        val current = ocrState?.takeIf {
            it.pageIndex == latestUi?.state?.currentPage && it.retryAvailable
        } ?: return
        ocrState = current.copy(retryPending = true, retryFailed = false)
        textGeneration++
        textState = ReaderTextState.Loading(current.pageIndex)
        searchState = searchState?.withoutOcrPage(current.pageIndex)
        publishLatest()
        session?.retryOcr(current.pageIndex) { result ->
            if (isDisposed() || latestUi?.state?.currentPage != current.pageIndex) return@retryOcr
            var accepted = false
            when (result) {
                is OcrCommandResult.Success -> {
                    val applied = result.value.outcome == com.folium.reader.index.OcrTransitionOutcome.APPLIED
                    accepted = applied
                    ocrState = if (applied) {
                        ReaderOcrState(current.pageIndex, result.value.status)
                    } else {
                        current.copy(retryPending = false, retryFailed = true)
                    }
                }
                is OcrCommandResult.Failure -> {
                    ocrState = current.copy(retryPending = false, retryFailed = true)
                }
            }
            if (!accepted) loadCurrentText(current.pageIndex)
            publishLatest()
        }
    }

    /** The open document's table of contents, or empty before it has opened or if it has none. */
    fun outline(): List<OutlineEntry> = session?.outline ?: emptyList()

    private fun reportPage(pageIndex: Int) {
        if (pageIndex == lastReportedPage) return
        lastReportedPage = pageIndex
        onPageChanged(pageIndex)
    }

    private fun publish(opened: ReaderSessionResult) {
        if (opened !is ReaderSessionResult.Opened) {
            mainPost { if (!isDisposed()) onState(failureState(opened)) }
            return
        }

        val accepted = synchronized(lock) {
            if (disposed) false else { session = opened.session; true }
        }

        if (accepted) {
            opened.session.observeOcrStatus(::publishOcrStatus)
            mainPost {
                textPageIndex = -1
                session?.let { publishReading(it.presenter.uiState) }
            }
        } else {
            // Both halves of teardown keep their threads even for a session nobody ever saw:
            // closing touches presenter state, which is confined to the main thread, and draining
            // blocks, which the main thread cannot afford.
            mainPost {
                closeThenScheduleDispose(opened.session::close) {
                    worker.execute { opened.session.dispose() }
                }
            }
        }
    }

    private fun isDisposed(): Boolean = synchronized(lock) { disposed }

    private fun publishReading(ui: ReaderUiState<BorrowedPage>) {
        if (isDisposed()) return
        latestUi = ui
        reportPage(ui.state.currentPage)

        if (textPageIndex != ui.state.currentPage) {
            textPageIndex = ui.state.currentPage
            ocrState = null
            textState = ReaderTextState.Loading(ui.state.currentPage)
            onState(ReaderScreenState.Reading(ui, requireNotNull(textState), searchState, ocrState))
            loadCurrentText(ui.state.currentPage)
            loadCurrentOcrStatus(ui.state.currentPage)
        } else {
            val currentText = textState?.takeIf { it.pageIndex == ui.state.currentPage }
                ?: ReaderTextState.Loading(ui.state.currentPage).also { textState = it }
            onState(ReaderScreenState.Reading(ui, currentText, searchState, currentOcrState(ui.state.currentPage)))
        }
    }

    private fun loadCurrentText(pageIndex: Int) {
        val generation = ++textGeneration
        session?.loadTextPage(pageIndex) { result ->
            if (isDisposed() || textPageIndex != pageIndex || generation != textGeneration) {
                return@loadTextPage
            }
            textState = result.toReaderTextState(pageIndex)
            publishLatest()
        }
    }

    private fun loadCurrentOcrStatus(pageIndex: Int) {
        val generation = ++ocrGeneration
        session?.ocrStatus(pageIndex) { result ->
            if (isDisposed() || textPageIndex != pageIndex || generation != ocrGeneration) {
                return@ocrStatus
            }
            val next = when (result) {
                is OcrCommandResult.Success -> result.value?.let { ReaderOcrState(pageIndex, it) }
                is OcrCommandResult.Failure -> ReaderOcrState(pageIndex, unavailable = true)
            }
            val nextStatus = next?.status
            if (nextStatus == null && ocrState?.status != null) return@ocrStatus
            if (nextStatus != null && !ocrState.accepts(nextStatus)) {
                return@ocrStatus
            }
            ocrState = next
            publishLatest()
        }
    }

    private fun publishOcrStatus(pageIndex: Int, status: OcrPageStatus) {
        if (isDisposed() || textPageIndex != pageIndex) return
        if (!ocrState.accepts(status)) return

        ocrState = ReaderOcrState(pageIndex, status)
        if (status.state != OcrPageState.COMPLETED) {
            searchState = searchState?.withoutOcrPage(pageIndex)
        }
        textState = ReaderTextState.Loading(pageIndex)
        loadCurrentText(pageIndex)
        publishLatest()
    }

    private fun currentOcrState(pageIndex: Int): ReaderOcrState? =
        ocrState?.takeIf { it.pageIndex == pageIndex && it.visible }

    internal fun publishSearch(generation: Long, progress: TextSearchProgress) {
        val update = synchronized(lock) {
            val current = searchState ?: return
            if (disposed || generation != searchGeneration || current.spec != progress.spec) return
            current.mergeWithInitialNavigation(
                progress,
                latestUi?.state?.currentPage ?: request.initialPage
            ).also { searchState = it.state }
        }
        update.navigation?.let(::dispatch)
        publishLatest()
    }

    private fun selectSearchResult(delta: Int) {
        val current = searchState ?: return
        val (updated, targetPage) = current.moveActiveBy(delta)
        if (targetPage == null) return
        searchState = updated
        dispatch(GestureIntent.FlingToPage(targetPage))
        publishLatest()
    }

    private fun publishLatest() {
        val ui = latestUi ?: return
        val text = textState?.takeIf { it.pageIndex == ui.state.currentPage }
            ?: ReaderTextState.Loading(ui.state.currentPage)
        onState(ReaderScreenState.Reading(ui, text, searchState, currentOcrState(ui.state.currentPage)))
    }

    private fun failureState(opened: ReaderSessionResult): ReaderScreenState = when (opened) {
        is ReaderSessionResult.Missing -> ReaderScreenState.Missing
        is ReaderSessionResult.Unreadable -> ReaderScreenState.Unreadable(opened.failure)
        is ReaderSessionResult.Opened -> error("an opened session is not a failure")
    }
}

/**
 * Opens [request]'s stored file and reads it, tearing the session down when it leaves the
 * composition. [onPageChanged] is called on the main thread whenever the current page differs from
 * the last one reported, which is how the activity keeps stored progress in step with reading.
 */
@Composable
fun ReaderHost(request: OpenBookRequest, onPageChanged: (Int) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var screen by remember(request.book.id) { mutableStateOf<ReaderScreenState>(ReaderScreenState.Opening) }

    val controller = remember(request.book.id) {
        ReaderHostController(context, request, onPageChanged, onState = { screen = it })
    }

    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.dispose() }
    }

    // A bound method reference is a fresh, non-equal instance every time it is written, so handing
    // one straight to a composable defeats that composable's skipping on every recomposition.
    val pageAspect = remember(controller) { controller::pageAspect }
    val onIntent = remember(controller) { controller::dispatch }
    val onViewportChanged = remember(controller) { controller::setViewport }

    when (val current = screen) {
        is ReaderScreenState.Opening -> ReaderMessage(
            tag = ReaderHostTestTags.OPENING,
            title = stringResource(R.string.reader_opening),
            body = request.book.title,
            onBack = onBack
        )

        is ReaderScreenState.Reading -> ReaderScreen(
            title = request.book.title,
            state = current.ui,
            pageAspect = pageAspect,
            onIntent = onIntent,
            onViewportChanged = onViewportChanged,
            onBack = onBack,
            outline = controller.outline(),
            textPage = current.text.selectablePage(current.ui.state.currentPage),
            ocr = current.ocr,
            search = current.search,
            onSearch = controller::search,
            onSearchClose = controller::closeSearch,
            onSearchPrevious = controller::previousSearchResult,
            onSearchNext = controller::nextSearchResult,
            onOcrRetry = controller::retryOcr
        )

        is ReaderScreenState.Missing -> ReaderMessage(
            tag = ReaderHostTestTags.FAILURE,
            title = stringResource(R.string.reader_missing_title),
            body = stringResource(R.string.reader_missing_body),
            onBack = onBack
        )

        is ReaderScreenState.Unreadable -> ReaderMessage(
            tag = ReaderHostTestTags.FAILURE,
            title = stringResource(ReaderCopy.title(current.failure)),
            body = stringResource(ReaderCopy.body(current.failure)),
            onBack = onBack
        )
    }
}

@Composable
private fun ReaderMessage(tag: String, title: String, body: String, onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).padding(24.dp).testTag(tag),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(32.dp))

                Button(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(ReaderHostTestTags.FAILURE_ACTION)
                ) {
                    Text(stringResource(R.string.reader_back))
                }
            }
        }
    }
}
