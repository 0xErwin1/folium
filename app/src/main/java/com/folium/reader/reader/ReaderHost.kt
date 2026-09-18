package com.folium.reader.reader

import com.folium.reader.library.TypographyPresetStore
import com.folium.reader.library.LibraryPaths
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.LaunchedEffect
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
import com.folium.reader.core.library.AppearanceMode
import com.folium.reader.core.library.AppearanceModes
import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.ReadingPositionToken
import com.folium.reader.core.pdf.ReflowLayoutBox
import com.folium.reader.core.pdf.ReflowPageColors
import com.folium.reader.core.pdf.ReflowSettings
import com.folium.reader.core.pdf.ReflowStyleSheet
import com.folium.reader.core.pdf.TypographyPreset
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSearchError
import com.folium.reader.core.text.TextSearchSpec
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.ocr.OcrCancellationReason
import com.folium.reader.core.ocr.OcrPageState
import com.folium.reader.core.ocr.OcrPageStatus
import com.folium.reader.library.OpenBookRequest
import com.folium.reader.library.documentWork
import com.folium.reader.ui.pageColorsFor
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
        val ocr: ReaderOcrState? = null,
        val thumbnails: ThumbnailGridState<BorrowedThumbnail> = ThumbnailGridState()
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
    val error: Boolean = false,
    val pendingPages: Int = (totalPages - indexedPages - failedPages).coerceAtLeast(0),
    val cancelledPages: Int = 0,
    val incompletePages: Int = (totalPages - indexedPages).coerceAtLeast(0),
    val revision: Long = 0L
) {
    val processedPages: Int get() = indexedPages
}

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
    val truncated: Boolean = false,
    val ocrPlan: SearchOcrPlanState? = null
) {
    constructor(query: String) : this(TextSearchSpec(query))
    val query: String get() = spec.query
    /**
     * Resolved once per state rather than on every read. A search can hold up to
     * [MAX_TEXT_SEARCH_RESULTS] matches, and the reader reads this several times per frame — the
     * result count, both navigation buttons, and once more for every page on screen — so a scan
     * per read is a scan per read per frame over the whole result set.
     */
    val activeIndex: Int? by lazy {
        activeIdentity?.let { identity -> matches.indexOfFirst { it.identity == identity } }?.takeIf { it >= 0 }
    }
    val activeMatch: ReaderSearchMatch? get() = activeIndex?.let(matches::get)
}

internal fun ReaderSearchState?.merge(progress: TextSearchProgress): ReaderSearchState {
    if (this?.spec == progress.spec && !coverage.running && progress.running &&
        progress.coverageRevision <= coverage.revision) return this
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
            progress.error,
            progress.pendingPages,
            progress.cancelledPages,
            progress.incompletePages,
            progress.coverageRevision
        ),
        pending = null,
        error = progress.searchError,
        truncated = progress.truncated,
        ocrPlan = this?.ocrPlan
    )
}

/** Jumps straight to a named result, which is what a list of them is for. */
internal fun ReaderSearchState.moveActiveTo(
    identity: ReaderSearchMatchIdentity
): Pair<ReaderSearchState, Int?> {
    val match = matches.firstOrNull { it.identity == identity } ?: return this to null
    if (identity == activeIdentity) return this to match.pageIndex
    return copy(activeIdentity = identity) to match.pageIndex
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

/**
 * The revision orders publications; it does not decide whether one is worth taking.
 *
 * The pipeline bumps its revision on every internal queue mutation — admitting a page, finishing
 * one, refreshing a plan — and posts each result to the main thread. Most of those carry state that
 * is observably identical to what is already held, and accepting them rebuilds the search state a
 * composable reads, for no visible change. So a newer revision is necessary but not sufficient.
 */
internal fun SearchOcrPlanState?.accepts(next: SearchOcrPlanState): Boolean {
    if (this == null) return true
    if (next.generation != generation) return next.generation > generation
    if (next.revision <= revision) return false
    return next.copy(revision = revision) != this
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
    ) -> ReaderSessionResult = { ctx, req, onChanged -> ReaderSession.open(ctx, req.file, req.book.id, req.initialPage, onChanged) },
    /**
     * Called exactly once, with a page, count and token that all describe the same successful
     * re-pagination, and never at all for [RepaginationResult.Abandoned] or
     * [RepaginationResult.Superseded] — see [repaginate]'s own doc for why a partial record is worse
     * than none.
     */
    private val recordRepagination: (BookId, Int, Int, ReadingPositionToken?) -> Unit = { _, _, _, _ -> },
    /**
     * The appearance-derived page colours already resolved when this controller was created — see
     * [setAppearanceColors] for how a later appearance change reaches an already open document.
     */
    private val initialPageColors: ReflowPageColors? = null,
    /**
     * The typography a book was last read with, resolved off the main thread as the session opens.
     *
     * A reader who set a book's type and closed it expects to find it that way, so the stored preset
     * has to reach the first layout rather than only the settings sheet — which reads the same
     * store, and would otherwise be the only thing in the app that knew.
     */
    private val resolvePreset: (BookId) -> TypographyPreset = { TypographyPreset.DEFAULT }
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
    private var searchOpen = false
    private var searchOcrPaused = false
    private var searchOcrState: SearchOcrPlanState? = null
    private var thumbnailsState: ThumbnailGridState<BorrowedThumbnail> = ThumbnailGridState()
    private var repaginationGeneration = 0L
    private var carriedDuringRepagination: CarriedPreview<BorrowedPage>? = null
    private var lastViewport: ReaderViewport? = null
    private var currentPreset: TypographyPreset = TypographyPreset.DEFAULT
    private var currentPageColors: ReflowPageColors? = initialPageColors

    /** Seeded with the restored page so the initial state — already at that page — is not reported as a change. */
    private var lastReportedPage: Int = request.initialPage

    /**
     * Re-lays out the open document under [settings], driven on [worker] — the same serial executor
     * that already owns [start] and [dispose], so a re-pagination can never overlap the engine's
     * single-session document with an open or a teardown.
     *
     * The half that must run on the presenter thread — detaching the carried preview and closing the
     * outgoing presenter and thumbnail pipeline, per [ReaderPresenter.close]'s own doc — runs here,
     * before [worker] is ever touched; everything else is [ReaderSession.repaginate]'s own job.
     * [onResult] always runs on the main thread.
     */
    fun repaginate(settings: ReflowSettings, onResult: (RepaginationResult) -> Unit = {}) {
        val session = this.session ?: return
        val generation = ++repaginationGeneration
        val token = session.currentPositionToken()

        val carried = session.presenter.detachPreviewForHandover()
        if (carried != null) {
            releaseCarriedPreview()
            carriedDuringRepagination = carried
        }
        session.presenter.close()
        session.thumbnails.close()
        latestUi = latestUi?.copy(pages = emptyMap(), basePages = emptyMap(), carriedPreview = carried)
        publishLatest()

        worker.execute {
            val result = session.repaginate(settings, token) { generation == repaginationGeneration }
            mainPost {
                if (!isDisposed()) {
                    if (result is RepaginationResult.Repaginated) {
                        recordRepagination(request.book.id, result.pageIndex, result.pageCount, result.token)
                        textPageIndex = -1
                        session.presenter.setViewport(lastViewport)
                        publishReading(session.presenter.uiState)
                    } else {
                        releaseCarriedPreview()
                    }
                }
                onResult(result)
            }
        }
    }

    private fun releaseCarriedPreview() {
        carriedDuringRepagination?.value?.release()
        carriedDuringRepagination = null
    }

    /**
     * Adopts [preset] as the typography now in force and re-lays out the open document under it,
     * carrying whatever appearance colours [setAppearanceColors] last resolved.
     */
    fun applyPreset(preset: TypographyPreset, onResult: (RepaginationResult) -> Unit = {}) {
        currentPreset = preset
        applyStylesheet(onResult)
    }

    /**
     * Re-lays out the open document whenever [colors] genuinely differs from what is already
     * applied — recomposition alone, with the same resolved colours, must not cost a re-pagination.
     * A no-op before the document has opened or for a fixed-layout document, exactly like
     * [applyPreset] — see [reflowable].
     */
    fun setAppearanceColors(colors: ReflowPageColors?) {
        if (colors == currentPageColors) return
        currentPageColors = colors
        applyStylesheet()
    }

    /**
     * Skips the request entirely when it would land on exactly the box and stylesheet an unopened
     * document is already laid out under — [ReflowLayoutBox.BOX_1] and no CSS — so an open with no
     * typography override and no appearance colours costs no re-pagination at all.
     */
    private fun applyStylesheet(onResult: (RepaginationResult) -> Unit = {}) {
        if (session == null || !reflowable()) return
        val box = ReflowStyleSheet.boxFor(currentPreset)
        val css = ReflowStyleSheet.build(currentPreset, currentPageColors)
        if (box == ReflowLayoutBox.BOX_1 && css.isEmpty()) return
        repaginate(ReflowSettings(box, css), onResult)
    }

    fun start() {
        worker.execute {
            currentPreset = resolvePreset(request.book.id)

            val opened = openSession(context, request) { ui ->
                publishReading(ui)
            }
            publish(opened)
        }
    }

    fun dispose() {
        cancelPendingSearch?.invoke()
        cancelPendingSearch = null
        releaseCarriedPreview()
        val abandoned = synchronized(lock) {
            disposed = true
            session.also { session = null }
        }

        abandoned?.let { session ->
            session.observeOcrStatus(null)
            session.observeSearchOcrStatus(null)
            session.observeThumbnails(null)
            closeThenScheduleDispose(session::close) { worker.execute { session.dispose() } }
        }
    }

    fun dispatch(intent: GestureIntent) = session?.presenter?.dispatch(intent) ?: Unit

    /** Declares which page indices the open page grid wants a thumbnail for right now. */
    fun setWantedThumbnails(pages: List<Int>) = session?.setWantedThumbnails(pages) ?: Unit

    fun setViewport(viewport: ReaderViewport?) {
        lastViewport = viewport
        session?.presenter?.setViewport(viewport)
    }

    fun pageAspect(pageIndex: Int): Float = session?.pageAspect(pageIndex) ?: 1f

    fun openSearch() {
        searchOpen = true
        if (!searchOcrPaused) {
            session?.openSearch(latestUi?.state?.currentPage ?: request.initialPage)
        }
        publishLatest()
    }

    fun search(spec: TextSearchSpec) {
        searchOpen = true
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
                    coverage = ReaderSearchCoverage(
                        0,
                        0,
                        session?.pageCount ?: 0,
                        running = !searchOcrPaused
                    ),
                    pending = ReaderSearchPending.DEBOUNCE,
                    ocrPlan = searchOcrState
                )
                searchState = state
                SearchStart(generation, cancellation, state)
            }
        }
        val generation = start.generation
        start.cancellation?.invoke()
        if (start.state == null) {
            session?.clearSearchQuery()
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
        searchOpen = false
        searchOcrPaused = true
        synchronized(lock) {
            searchState = searchState?.copy(
                ocrPlan = searchOcrState
            )
        }
        session?.pauseSearchOcr()?.let(::publishSearchOcrStatus)
        publishLatest()
    }

    fun pauseSearchOcr() {
        val current = searchState?.takeIf { it.ocrPlan?.canPause == true } ?: return
        searchOcrPaused = true
        session?.pauseSearchOcr()?.let(::publishSearchOcrStatus)
        searchState = current.copy(ocrPlan = searchOcrState)
        publishLatest()
    }

    fun resumeSearchOcr() {
        val current = searchState?.takeIf { it.ocrPlan?.canResume == true } ?: return
        searchOcrPaused = false
        session?.resumeSearchOcr(latestUi?.state?.currentPage ?: request.initialPage)
            ?.let(::publishSearchOcrStatus)
        searchState = current.copy(ocrPlan = searchOcrState)
        publishLatest()
    }

    fun previousSearchResult() = selectSearchResult(-1)
    fun nextSearchResult() = selectSearchResult(1)

    fun retryOcr() {
        val current = ocrState?.takeIf {
            it.pageIndex == latestUi?.state?.currentPage && it.retryAvailable
        } ?: return
        ocrState = current.copy(retryPending = true, retryFailed = false)
        val retryGeneration = ++ocrGeneration
        textGeneration++
        textState = ReaderTextState.Loading(current.pageIndex)
        searchState = searchState?.withoutOcrPage(current.pageIndex)
        publishLatest()
        session?.retryOcr(current.pageIndex) { result ->
            if (isDisposed() || latestUi?.state?.currentPage != current.pageIndex ||
                retryGeneration != ocrGeneration) return@retryOcr
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

    /** Whether the open document can be re-paginated, or `false` before it has opened. */
    fun reflowable(): Boolean = session?.reflowable ?: false

    private fun reportPage(pageIndex: Int) {
        if (searchOpen) session?.updateSearchDemand(pageIndex)
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
            opened.session.observeSearchOcrStatus(::publishSearchOcrStatus)
            opened.session.observeThumbnails(::publishThumbnails)
            mainPost {
                textPageIndex = -1
                session?.let { publishReading(it.presenter.uiState) }
                applyStylesheet()
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
        if (carriedDuringRepagination != null && (ui.pages.isNotEmpty() || ui.basePages.isNotEmpty())) {
            releaseCarriedPreview()
        }

        if (textPageIndex != ui.state.currentPage) {
            textPageIndex = ui.state.currentPage
            ocrState = null
            textState = ReaderTextState.Loading(ui.state.currentPage)
            onState(ReaderScreenState.Reading(
                ui,
                requireNotNull(textState),
                searchState.takeIf { searchOpen },
                ocrState,
                thumbnailsState
            ))
            loadCurrentText(ui.state.currentPage)
            loadCurrentOcrStatus(ui.state.currentPage)
        } else {
            val currentText = textState?.takeIf { it.pageIndex == ui.state.currentPage }
                ?: ReaderTextState.Loading(ui.state.currentPage).also { textState = it }
            onState(ReaderScreenState.Reading(
                ui,
                currentText,
                searchState.takeIf { searchOpen },
                currentOcrState(ui.state.currentPage),
                thumbnailsState
            ))
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

    /**
     * A reflowable book is never queued for recognition, because it carries its own text. Asking
     * anyway answers that recognition is unavailable, which the reader would be shown as a problem
     * on any page holding no text — a cover, a plate, a chapter break — when nothing is wrong.
     */
    private fun loadCurrentOcrStatus(pageIndex: Int) {
        if (reflowable()) {
            ocrState = null
            return
        }

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

    private fun publishThumbnails(state: ThumbnailGridState<BorrowedThumbnail>) {
        if (isDisposed()) return
        thumbnailsState = state
        publishLatest()
    }

    internal fun publishSearchOcrStatus(state: SearchOcrPlanState) {
        val accepted = synchronized(lock) {
            val current = searchOcrState
            if (disposed || !current.accepts(state)) {
                false
            } else {
                searchOcrState = state
                searchState = searchState?.copy(ocrPlan = state)
                true
            }
        }
        if (accepted) publishLatest()
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
        applySearchSelection(current.moveActiveBy(delta))
    }

    internal fun selectSearchResult(identity: ReaderSearchMatchIdentity) {
        val current = searchState ?: return
        applySearchSelection(current.moveActiveTo(identity))
    }

    private fun applySearchSelection(selection: Pair<ReaderSearchState, Int?>) {
        val (updated, targetPage) = selection
        if (targetPage == null) return
        searchState = updated
        dispatch(GestureIntent.FlingToPage(targetPage))
        publishLatest()
    }

    private fun publishLatest() {
        val ui = latestUi ?: return
        val text = textState?.takeIf { it.pageIndex == ui.state.currentPage }
            ?: ReaderTextState.Loading(ui.state.currentPage)
        onState(ReaderScreenState.Reading(
            ui,
            text,
            searchState.takeIf { searchOpen },
            currentOcrState(ui.state.currentPage),
            thumbnailsState
        ))
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
fun ReaderHost(
    request: OpenBookRequest,
    onPageChanged: (Int) -> Unit,
    onBack: () -> Unit,
    typographySheetOpen: Boolean = false,
    onTypographySheetOpenChange: (Boolean) -> Unit = {},
    onRepaginated: (BookId, Int, Int, ReadingPositionToken?) -> Unit = { _, _, _, _ -> },
    appearanceMode: AppearanceMode = AppearanceModes.DEFAULT
) {
    val context = LocalContext.current.applicationContext
    var screen by remember(request.book.id) { mutableStateOf<ReaderScreenState>(ReaderScreenState.Opening) }

    val systemDark = isSystemInDarkTheme()
    val pageColors = remember(appearanceMode, systemDark) { pageColorsFor(appearanceMode, systemDark) }

    val controller = remember(request.book.id) {
        ReaderHostController(
            context, request, onPageChanged, onState = { screen = it },
            recordRepagination = onRepaginated, initialPageColors = pageColors,
            resolvePreset = { bookId ->
                val paths = LibraryPaths(context.filesDir)
                val store = TypographyPresetStore(paths)
                store.readOverride(bookId) ?: store.readGlobal()
            }
        )
    }

    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.dispose() }
    }

    LaunchedEffect(controller, pageColors) {
        controller.setAppearanceColors(pageColors)
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

        is ReaderScreenState.Reading -> {
            val reflowable = controller.reflowable()

            Box(Modifier.fillMaxSize()) {
                ReaderScreen(
                    title = request.book.title,
                    author = request.book.author,
                    state = current.ui,
                    pageAspect = pageAspect,
                    onIntent = onIntent,
                    onViewportChanged = onViewportChanged,
                    onBack = onBack,
                    outline = controller.outline(),
                    textPage = current.text.selectablePage(current.ui.state.currentPage),
                    ocr = current.ocr,
                    search = current.search,
                    onSearchOpen = controller::openSearch,
                    onSearch = controller::search,
                    onSearchClose = controller::closeSearch,
                    onSearchPrevious = controller::previousSearchResult,
                    onSearchNext = controller::nextSearchResult,
                    onSearchSelect = controller::selectSearchResult,
                    onSearchOcrPause = controller::pauseSearchOcr,
                    onSearchOcrResume = controller::resumeSearchOcr,
                    onOcrRetry = controller::retryOcr,
                    reflowable = reflowable,
                    onTypographyRequested = { onTypographySheetOpenChange(true) },
                    thumbnails = current.thumbnails,
                    onThumbnailsWanted = controller::setWantedThumbnails
                )

                if (reflowable && typographySheetOpen) {
                    TypographySettingsSheet(
                        bookId = request.book.id,
                        applyPreset = controller::applyPreset,
                        onDismissRequest = { onTypographySheetOpenChange(false) },
                        onLeaveReader = onBack
                    )
                }
            }
        }

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
