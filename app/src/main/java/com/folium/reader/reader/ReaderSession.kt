package com.folium.reader.reader

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.ReadingPositionToken
import com.folium.reader.core.pdf.ReadingPositionTokens
import com.folium.reader.core.pdf.ReflowSettings
import com.folium.reader.core.pdf.ReflowStyleSheet
import com.folium.reader.core.pdf.SchedulerOutcome
import com.folium.reader.core.pdf.SchedulerCloseTimeoutException
import com.folium.reader.core.pdf.ViewportScheduler
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.NATIVE_TEXT_USABILITY_POLICY_VERSION
import com.folium.reader.index.OcrPageKey
import com.folium.reader.index.OcrAttempt
import com.folium.reader.index.OcrTransition
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSearchSpec
import com.folium.reader.core.ocr.OcrPageStatus
import com.folium.reader.core.ocr.OcrRequest
import com.folium.reader.core.ocr.OcrEngine
import com.folium.reader.core.ocr.OcrEngineEnvironment
import com.folium.reader.core.ocr.OcrLanguage
import com.folium.reader.index.DocumentContentVersion
import com.folium.reader.index.RoomTextPageIndex
import com.folium.reader.index.TEXT_PAGE_SCHEMA_VERSION
import com.folium.reader.index.TextPageDatabase
import com.folium.reader.index.TextPageIndex
import com.folium.reader.index.TextPageIndexKey
import com.folium.reader.index.TransientTextPageIndex
import com.folium.reader.index.sha256
import com.folium.reader.pdf.PageCacheMemoryCallbacks
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * How many pages may be rasterizing at once. The engine serializes work on a document anyway, so a
 * larger bound would only queue threads behind that lock; two is enough for a cancelled prefetch to
 * hand over to the page the reader is actually looking at without waiting for it to finish.
 */
private const val RENDER_WORKERS = 2

/**
 * The base tier's own worker bound — deliberately one, not [RENDER_WORKERS]: it runs on a scheduler
 * dedicated to it (see [ReaderPresenter]'s own doc for why it cannot share [RENDER_WORKERS]'s
 * scheduler), and a base tier raster is small and requested once per page for the life of the
 * session, so a single worker never meaningfully falls behind.
 */
private const val BASE_TIER_RENDER_WORKERS = 1

internal const val MIN_CACHE_BYTES = 16L * 1024 * 1024
internal const val MAX_CACHE_BYTES = 256L * 1024 * 1024

/**
 * How much of what the device has to spare the reader is willing to hold rasters in.
 *
 * A share rather than the lot: the rest of the phone is still running, and whatever is taken here
 * is given back proportionally the moment the system asks — see [PageCacheMemoryCallbacks].
 */
private const val SPARE_MEMORY_DIVISOR = 4L

/**
 * What the reader may hold, given what the device reports free and the level at which it starts
 * reclaiming from somebody.
 *
 * This used to be a quarter of the Java heap, which measured the wrong thing: a raster's pixels have
 * not been allocated on the Java heap since Android 8, so the heap ceiling never constrained this
 * cache and the heap's own pressure was never relieved by trimming it. Measured with a full window
 * of A3 plans on a Pixel 8, the heap sat at 27MB while the cache was entitled to 64MB of rasters.
 *
 * Anchored to free memory instead, which is what a native allocation actually competes for, and
 * bounded at both ends: never so little that a page cannot be held, never so much that the reader
 * is the reason something else on the phone is killed.
 */
internal fun readerShareOf(availableBytes: Long, lowMemoryThresholdBytes: Long): Long {
    val spare = (availableBytes - lowMemoryThresholdBytes).coerceAtLeast(0)

    return (spare / SPARE_MEMORY_DIVISOR).coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES)
}
private val TRANSIENT_DOCUMENT_VERSION = DocumentContentVersion("0".repeat(64))

internal data class TextIndexSessionPlan(
    val documentVersion: DocumentContentVersion,
    val persistent: Boolean,
    val fallbackFailure: Throwable? = null
)

private data class OcrSessionPlan(
    val version: com.folium.reader.core.text.TextEngineVersion?,
    val failure: Throwable?,
    val engineFactory: (() -> OcrEngine)?
)

internal fun textIndexSessionPlan(
    file: File,
    versioner: (File) -> DocumentContentVersion = ::sha256
): TextIndexSessionPlan = try {
    TextIndexSessionPlan(versioner(file), persistent = true)
} catch (failure: Throwable) {
    TextIndexSessionPlan(TRANSIENT_DOCUMENT_VERSION, persistent = false, fallbackFailure = failure)
}

/**
 * How much of the stored file's SHA-256 names the document for [ReadingPositionToken] scoping.
 * Sixteen hex characters (64 bits) is already far past any plausible collision risk for a single
 * reader's library, and matches the length [ReflowStyleSheet.layoutVersion] already commits to for
 * the same reason.
 */
private const val DOCUMENT_SCOPE_LENGTH = 16

/** Outcome of [ReaderSession.repaginate]. See that method's own doc for what each case means. */
sealed class RepaginationResult {
    /**
     * [resolved] is false when [ReaderSession.repaginate]'s token either did not belong to this
     * document or no longer named a place in it, in which case [pageIndex] is the previous current
     * page clamped into the new page count rather than a position the token actually resolved to.
     */
    data class Repaginated(
        val pageIndex: Int,
        val pageCount: Int,
        val token: ReadingPositionToken?,
        val resolved: Boolean,
        val elapsedMillis: Long
    ) : RepaginationResult()

    /**
     * A worker was still draining when the close timeout elapsed. Nothing was laid out, the
     * document keeps the layout it had, and the caller's presenter — already given up by [ReaderSession]'s
     * caller before this was invoked — is not rebuilt: see [ReaderSession.repaginate]'s own doc for
     * why no recovery here is safer than doing nothing.
     */
    data object Abandoned : RepaginationResult()

    /** A newer repagination request already superseded this one before it reached the layout. */
    data object Superseded : RepaginationResult()
}

/**
 * Everything [ReaderSession.repaginate] needs to rebuild the pipeline that only
 * [ReaderSession.Companion.build] otherwise knows how to construct. A session over a document that
 * is not [com.folium.reader.core.pdf.PdfDocument.reflowable] carries none of this, since
 * repagination is never offered for one.
 */
internal class RepaginationRig(
    val cache: ByteBoundedPageCache<RenderedPage>,
    val priorityGate: DocumentPriorityGate,
    val cacheBudgetBytes: Long,
    val mainPost: (() -> Unit) -> Unit,
    val scheduleRetry: (Long, () -> Unit) -> Unit,
    val onChanged: (ReaderUiState<BorrowedPage>) -> Unit,
    val textIndex: TextPageIndex,
    val documentVersion: DocumentContentVersion,
    val nativeEngineVersion: com.folium.reader.core.text.TextEngineVersion,
    val thumbnailCache: ByteBoundedPageCache<ThumbnailRaster> = ByteBoundedPageCache(THUMBNAIL_CACHE_BYTES),
    val onThumbnailsChanged: (ThumbnailGridState<BorrowedThumbnail>) -> Unit = {}
)

/**
 * A live reading session: an open document, the cache its rasters live in, and the presenter that
 * decides what to request and what to show.
 *
 * Teardown is deliberately two-phase, because the two halves have opposite constraints. [close]
 * runs on the main thread and gives up every borrow currently on screen immediately, so nothing
 * keeps a bitmap alive past the moment the reader leaves. [dispose] then blocks until every worker
 * has finished publishing and releasing, which is exactly why it must not run on the main thread —
 * and by then the presenter is already refusing new values, so anything those workers publish on
 * the way out is released rather than shown.
 */
class ReaderSession internal constructor(
    private val document: ReaderDocument,
    initialTextLoader: SessionTextLoader,
    private val lifecycle: ReaderSessionLifecycle,
    private val ocrEngineFactory: (() -> OcrEngine)?,
    private val ocrDispatch: OcrPipelineDispatch,
    private val ocrStatusDispatch: OcrStatusDispatch,
    private val searchOcrStatusDispatch: SearchOcrStatusDispatch,
    private val priorityGate: DocumentPriorityGate,
    initialPresenter: ReaderPresenter<BorrowedPage>,
    initialThumbnails: ThumbnailPipeline<BorrowedThumbnail>,
    private val documentScope: String? = null,
    private val repaginationRig: RepaginationRig? = null,
    private val thumbnailStatusDispatch: ThumbnailStatusDispatch = ThumbnailStatusDispatch()
) {
    /**
     * Both mutable because [repaginate] rebuilds them from scratch rather than mutating them in
     * place — see that method's own doc. [swapLock] guards only the swap itself, so a reader of
     * either field on another thread always sees a fully constructed value, never a half-built one.
     */
    private val swapLock = Any()
    @Volatile private var textLoader: SessionTextLoader = initialTextLoader
    @Volatile private var presenterField: ReaderPresenter<BorrowedPage> = initialPresenter
    @Volatile private var thumbnailsField: ThumbnailPipeline<BorrowedThumbnail> = initialThumbnails
    private val nextGeneration = AtomicLong(1)

    val presenter: ReaderPresenter<BorrowedPage> get() = presenterField

    /** Owns the page-grid thumbnail pipeline for as long as this session's current layout generation lasts — see [repaginate]. */
    internal val thumbnails: ThumbnailPipeline<BorrowedThumbnail> get() = thumbnailsField

    /**
     * Never started for a reflowable document: it carries real, structured text already, so OCR has
     * nothing to contribute, and this removes the only other component that renders through
     * [document]'s engine session from threads [repaginate]'s drain does not cover.
     */
    private val ocrPipeline = ocrEngineFactory?.takeUnless { document.pdf.reflowable }?.let { factory ->
        createSessionOcrPipeline(
            document.pdf,
            document.pageCount,
            factory,
            textLoader,
            priorityGate,
            OcrRasterPolicy.forHeap(Runtime.getRuntime().maxMemory()),
            onStopped = textLoader::close,
            onSearchStateChanged = searchOcrStatusDispatch::publish
        )
    }

    init {
        ocrDispatch.attach(ocrPipeline)
    }

    val pageCount: Int get() = document.pageCount
    val outline: List<OutlineEntry> get() = document.outline

    /** Whether the engine can re-paginate this document — see [PdfDocument.reflowable]. */
    val reflowable: Boolean get() = document.pdf.reflowable

    fun pageAspect(pageIndex: Int): Float = document.aspect(pageIndex)

    /**
     * Mints a token for [pageIndex] under the document's current layout, scoped with this session's
     * own document identity so [repaginate] can tell a token minted here apart from one minted
     * against a different file that happens to share the same chapter/offset shape. Null for a
     * fixed-layout document, or for a session whose document identity could not be established at
     * open (see [textIndexSessionPlan]).
     */
    fun currentPositionToken(): ReadingPositionToken? {
        val scope = documentScope ?: return null
        val minted = document.pdf.makePositionToken(presenterField.uiState.state.currentPage) ?: return null
        return ReadingPositionTokens.rescope(minted, scope)
    }

    internal fun loadTextPage(pageIndex: Int, callback: (TextPageLoadResult) -> Unit) =
        textLoader.load(pageIndex, callback)

    internal fun searchText(spec: TextSearchSpec, callback: (TextSearchProgress) -> Unit) =
        textLoader.search(spec, callback)

    internal fun openSearch(visiblePage: Int) {
        textLoader.setProgressiveOcrActive(true)
        ocrPipeline?.openSearch(visiblePage)
    }

    internal fun updateSearchDemand(visiblePage: Int) {
        ocrPipeline?.updateSearchDemand(visiblePage)
    }

    internal fun clearSearchQuery() {
        textLoader.closeSearch()
    }

    internal fun pauseSearchOcr(): SearchOcrPlanState? {
        textLoader.setProgressiveOcrActive(false)
        ocrPipeline?.closeSearch()
        return ocrPipeline?.searchState()
    }

    internal fun resumeSearchOcr(visiblePage: Int): SearchOcrPlanState? {
        textLoader.setProgressiveOcrActive(true)
        ocrPipeline?.openSearch(visiblePage)
        return ocrPipeline?.searchState()
    }

    internal fun closeSearch() {
        pauseSearchOcr()
        textLoader.closeSearch()
    }

    /** FOL-7 handoff: eligibility, ownership and retry policy remain inside the repository. */
    internal fun ocrStatus(pageIndex: Int, callback: (OcrCommandResult<OcrPageStatus?>) -> Unit) {
        textLoader.ocrStatus(pageIndex, callback)
    }

    internal fun claimOcr(pageIndex: Int, callback: (OcrCommandResult<OcrTransition>) -> Unit) {
        textLoader.claimOcr(pageIndex, callback)
    }

    internal fun completeOcr(
        attempt: OcrAttempt,
        page: TextPage,
        callback: (OcrCommandResult<OcrTransition>) -> Unit
    ) = textLoader.completeOcr(attempt, page, callback)

    internal fun failOcr(
        attempt: OcrAttempt,
        failureKind: String,
        retryable: Boolean,
        callback: (OcrCommandResult<OcrTransition>) -> Unit
    ) = textLoader.failOcr(attempt, failureKind, retryable, callback)

    internal fun cancelOcr(attempt: OcrAttempt, callback: (OcrCommandResult<OcrTransition>) -> Unit) =
        textLoader.cancelOcr(attempt, com.folium.reader.core.ocr.OcrCancellationReason.USER, callback)

    internal fun retryOcr(pageIndex: Int, callback: (OcrCommandResult<OcrTransition>) -> Unit) {
        textLoader.retryOcr(pageIndex) { result ->
            if (result is OcrCommandResult.Success && result.value.outcome ==
                com.folium.reader.index.OcrTransitionOutcome.APPLIED) {
                ocrPipeline?.enqueueExplicit(pageIndex)
            }
            callback(result)
        }
    }

    internal fun observeOcrStatus(observer: ((Int, OcrPageStatus) -> Unit)?) {
        ocrStatusDispatch.observe(observer)
    }

    internal fun observeSearchOcrStatus(observer: ((SearchOcrPlanState) -> Unit)?) {
        searchOcrStatusDispatch.observe(observer)
        ocrPipeline?.searchState()?.let(searchOcrStatusDispatch::publish)
    }

    internal fun observeThumbnails(observer: ((ThumbnailGridState<BorrowedThumbnail>) -> Unit)?) {
        thumbnailStatusDispatch.observe(observer)
    }

    /**
     * Declares [pages] the only page indices worth a thumbnail right now — see
     * [ThumbnailPipeline.setWanted]. Sized off [ReaderDocument.aspect] so a thumbnail always matches
     * the page it stands for, exactly like the base tier's own raster.
     */
    internal fun setWantedThumbnails(pages: List<Int>) {
        thumbnailsField.setWanted(pages) { pageIndex ->
            ReaderGeometry.baseTierSpec(document.aspect(pageIndex), THUMBNAIL_LONGEST_EDGE_PX)
        }
    }

    fun close() {
        textLoader.beginOcrDrain()
        if (ocrPipeline == null) textLoader.close() else ocrPipeline.close()
        presenterField.close()
        thumbnailsField.close()
        lifecycle.close()
    }

    /**
     * Blocking: drains the scheduler, frees every cached raster and closes the document. Must run
     * off the main thread, and only after [close].
     */
    fun dispose() {
        ocrPipeline?.dispose()
        presenterField.shutdown()
        thumbnailsField.shutdown()
        textLoader.dispose()
        lifecycle.dispose()
    }

    /**
     * Re-lays out [document] under [settings], preserving the reader's place as best it can.
     *
     * Must run off the main thread; the caller is responsible for everything that must run before
     * this is called and cannot run here — closing the outgoing [presenter] on the presenter thread,
     * so its drain below does not answer its own cancellations by resubmitting into a scheduler that
     * is about to close (see [ReaderPresenter.close]'s own doc) — and for [isCurrent], re-checked
     * immediately before the layout so a request already superseded by a newer one never pays for a
     * relayout whose result nothing will use.
     *
     * [token] is resolved against the document's *new* layout only after it has been applied: this
     * session's own scope is unwrapped from it first, so a token minted by a different document is
     * treated exactly like one that fails to resolve — see [RepaginationResult.Repaginated]'s own
     * doc. A null or unresolvable token, or no [documentScope] at all, falls back to the reader's
     * previous current page, clamped into the new page count.
     *
     * On [SchedulerCloseTimeoutException] this returns [RepaginationResult.Abandoned] and does
     * nothing else: [document] is never relaid out, and [presenter] is left exactly as the caller's
     * own [ReaderPresenter.close] already left it. A worker still inside fitz when the drain timed
     * out is exactly the case that could reach a page about to change out from under it, and there
     * is no recovery here safer than doing nothing — rebuilding a presenter now would submit new
     * render requests against the same single-session engine a wedged worker may still be holding.
     */
    fun repaginate(
        settings: ReflowSettings,
        token: ReadingPositionToken?,
        isCurrent: () -> Boolean = { true }
    ): RepaginationResult {
        val rig = repaginationRig ?: return RepaginationResult.Abandoned
        if (!document.pdf.reflowable) return RepaginationResult.Abandoned

        val startedAtNanos = System.nanoTime()
        val fallbackPage = presenterField.uiState.state.currentPage

        try {
            presenterField.shutdown()
        } catch (timeout: SchedulerCloseTimeoutException) {
            return RepaginationResult.Abandoned
        }
        thumbnailsField.close()
        thumbnailsField.shutdown()

        rig.cache.clear()
        rig.thumbnailCache.clear()

        if (!isCurrent()) return RepaginationResult.Superseded

        document.pdf.relayout(settings)
        val newPageCount = document.pdf.pageCount
        val newOutline = try {
            document.pdf.outline()
        } catch (_: PdfException) {
            emptyList()
        }
        val newFirstPageAspect = document.pdf.pageInfo(0).let { it.width / it.height }

        val innerToken = documentScope?.let { scope -> token?.let { ReadingPositionTokens.unscope(it, scope) } }
        val resolvedFromToken = innerToken?.let(document.pdf::resolvePositionToken)
        val resolved = resolvedFromToken != null
        val resolvedPage = (resolvedFromToken ?: fallbackPage).coerceIn(0, newPageCount - 1)
        val resolvedPageAspect = if (resolvedPage != 0) {
            runCatching { document.pdf.pageInfo(resolvedPage) }.getOrNull()?.let { it.width / it.height }
        } else null

        document.applyRelayout(newPageCount, newOutline, newFirstPageAspect, resolvedPage, resolvedPageAspect)

        val generation = nextGeneration.getAndIncrement()
        val newPresenter = buildRepaginatedPresenter(document, rig, generation, resolvedPage)
        val newThumbnails = buildThumbnailPipeline(
            document = document.pdf,
            documentId = document.bookId.value,
            generation = generation,
            priorityGate = rig.priorityGate,
            cache = rig.thumbnailCache,
            deliverToPresenter = rig.mainPost,
            onChanged = rig.onThumbnailsChanged
        )

        val newLayoutVersion = ReflowStyleSheet.layoutVersion(settings.box, settings.userCss)
        val newTextLoader = TextPageLoader(
            document = document.pdf,
            pageCount = newPageCount,
            deliver = rig.mainPost,
            index = rig.textIndex,
            indexKey = { pageIndex ->
                TextPageIndexKey(
                    document.bookId, rig.documentVersion, pageIndex, TextSource.NATIVE_PDF,
                    TEXT_PAGE_SCHEMA_VERSION, rig.nativeEngineVersion, newLayoutVersion
                )
            }
        )

        val previousTextLoader = synchronized(swapLock) {
            val previous = textLoader
            textLoader = newTextLoader
            presenterField = newPresenter
            thumbnailsField = newThumbnails
            previous
        }
        previousTextLoader.dispose()

        val outerToken = documentScope?.let { scope ->
            document.pdf.makePositionToken(resolvedPage)?.let { ReadingPositionTokens.rescope(it, scope) }
        }

        val elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000
        return RepaginationResult.Repaginated(resolvedPage, newPageCount, outerToken, resolved, elapsedMillis)
    }

    companion object {
        /**
         * Blocking: opens the document and builds the session around it. Must run off the main
         * thread. The returned presenter has no viewport yet and has requested nothing.
         */
        internal fun open(
            context: Context,
            file: File,
            bookId: BookId,
            initialPage: Int,
            onChanged: (ReaderUiState<BorrowedPage>) -> Unit
        ): ReaderSessionResult {
            val opened = ReaderDocument.open(file, bookId, initialPage)
            val document = when (opened) {
                is ReaderDocumentResult.Opened -> opened.document
                is ReaderDocumentResult.Missing -> return ReaderSessionResult.Missing
                is ReaderDocumentResult.Unreadable -> return ReaderSessionResult.Unreadable(opened.failure)
            }

            val scope = SessionConstructionScope()
            return scope.construct {
                acquire({ document }, ReaderDocument::close)
                val clampedInitial = initialPage.coerceIn(0, document.pageCount - 1)
                val textIndexPlan = textIndexSessionPlan(file)
                ReaderSessionResult.Opened(
                    build(context.applicationContext, document, textIndexPlan, clampedInitial, onChanged, this)
                )
            }
        }

        private fun build(
            applicationContext: Context,
            document: ReaderDocument,
            textIndexPlan: TextIndexSessionPlan,
            initialPage: Int,
            onChanged: (ReaderUiState<BorrowedPage>) -> Unit,
            scope: SessionConstructionScope
        ): ReaderSession {
            val budgetBytes = cacheBudgetBytes(applicationContext)
            val cache = scope.acquire(
                factory = { ByteBoundedPageCache<RenderedPage>(budgetBytes) },
                cleanup = ByteBoundedPageCache<RenderedPage>::clear
            )
            val main = Handler(Looper.getMainLooper())
            val priorityGate = DocumentPriorityGate()

            lateinit var presenterRef: ReaderPresenter<BorrowedPage>
            val createdSchedulers = mutableListOf<ViewportScheduler<BorrowedPage>>()
            val renderer = PdfPageRenderer(
                document = document.pdf,
                documentId = document.bookId.value,
                generation = 0L,
                cache = cache,
                priorityGate = priorityGate,
                onPageMeasured = { pageIndex, measure ->
                    if (document.measureIfUnknown(pageIndex, measure)) {
                        main.post { presenterRef.dispatch(GestureIntent.ViewportResized) }
                    }
                }
            )

            val presenter = scope.acquire(
                factory = {
                    try {
                        ReaderPresenter(
                            pageCount = document.pageCount,
                            cacheBudgetBytes = budgetBytes,
                            releaseValue = BorrowedPage::release,
                            pageAspect = document::aspect,
                            scheduleRetry = { delayMillis, action -> main.postDelayed(action, delayMillis) },
                            deliverToPresenter = { action -> main.post(action) },
                            onChanged = onChanged,
                            initialPage = initialPage,
                            baseSchedulerFactory = { onOutcome ->
                                ViewportScheduler(
                                    BASE_TIER_RENDER_WORKERS,
                                    renderer,
                                    workerPoolName = "render-base",
                                    onOutcome = onOutcome
                                ).also(createdSchedulers::add)
                            }
                        ) { onOutcome ->
                            ViewportScheduler(
                                RENDER_WORKERS,
                                renderer,
                                workerPoolName = "render-detail",
                                onOutcome = onOutcome
                            ).also(createdSchedulers::add)
                        }
                    } catch (failure: Throwable) {
                        closeSchedulersAfterFailure(createdSchedulers, failure)
                        throw failure
                    }
                },
                cleanup = { acquired ->
                    try {
                        acquired.close()
                    } finally {
                        acquired.shutdown()
                    }
                }
            )
            presenterRef = presenter

            val thumbnailStatusDispatch = ThumbnailStatusDispatch()
            val thumbnailCache = scope.acquire(
                factory = { ByteBoundedPageCache<ThumbnailRaster>(THUMBNAIL_CACHE_BYTES) },
                cleanup = ByteBoundedPageCache<ThumbnailRaster>::clear
            )
            val thumbnails = scope.acquire(
                factory = {
                    buildThumbnailPipeline(
                        document = document.pdf,
                        documentId = document.bookId.value,
                        generation = 0L,
                        priorityGate = priorityGate,
                        cache = thumbnailCache,
                        deliverToPresenter = { action -> main.post(action) },
                        onChanged = thumbnailStatusDispatch::publish
                    )
                },
                cleanup = { acquired ->
                    try {
                        acquired.close()
                    } finally {
                        acquired.shutdown()
                    }
                }
            )

            val memoryCallbacks = PageCacheMemoryCallbacks(cache)
            applicationContext.registerComponentCallbacks(memoryCallbacks)
            scope.onCleanup { applicationContext.unregisterComponentCallbacks(memoryCallbacks) }

            val ocrPlan = ocrSessionPlan(applicationContext)
            val ocrDispatch = OcrPipelineDispatch()
            val ocrStatusDispatch = OcrStatusDispatch()
            val searchOcrStatusDispatch = SearchOcrStatusDispatch { action -> main.post(action) }
            val textResources = acquireReaderTextResources(
                applicationContext, document, textIndexPlan, ocrPlan, ocrDispatch,
                ocrStatusDispatch, main, scope
            )

            val lifecycle = ReaderSessionLifecycle(
                unregisterCallbacks = { applicationContext.unregisterComponentCallbacks(memoryCallbacks) },
                closeTextLoader = {},
                // Presenter and text loader are handled by ReaderSession's own close()/dispose()
                // instead, which always resolve against the *current* presenter/loader — see
                // [ReaderSession.repaginate], which rebuilds and swaps both.
                closePresenter = {},
                shutdownPresenter = {},
                disposeTextLoader = {},
                closeTextIndex = textResources.index::close,
                clearPageCache = cache::clear,
                clearThumbnailCache = thumbnailCache::clear,
                closeDocument = document::close
            )
            val repaginationRig = if (document.pdf.reflowable) {
                RepaginationRig(
                    cache = cache,
                    thumbnailCache = thumbnailCache,
                    priorityGate = priorityGate,
                    cacheBudgetBytes = budgetBytes,
                    mainPost = { action -> main.post(action) },
                    scheduleRetry = { delayMillis, action -> main.postDelayed(action, delayMillis) },
                    onChanged = onChanged,
                    onThumbnailsChanged = thumbnailStatusDispatch::publish,
                    textIndex = textResources.index,
                    documentVersion = textIndexPlan.documentVersion,
                    nativeEngineVersion = document.textEngineVersion
                )
            } else null
            return ReaderSession(
                document,
                textResources.loader,
                lifecycle,
                ocrPlan.engineFactory,
                ocrDispatch,
                ocrStatusDispatch,
                searchOcrStatusDispatch,
                priorityGate,
                presenter,
                thumbnails,
                documentScope = textIndexPlan.documentVersion.value.take(DOCUMENT_SCOPE_LENGTH),
                repaginationRig = repaginationRig,
                thumbnailStatusDispatch = thumbnailStatusDispatch
            )
        }

        private fun acquireReaderTextResources(
            context: Context,
            document: ReaderDocument,
            textIndexPlan: TextIndexSessionPlan,
            ocrPlan: OcrSessionPlan,
            ocrDispatch: OcrPipelineDispatch,
            ocrStatusDispatch: OcrStatusDispatch,
            main: Handler,
            scope: SessionConstructionScope
        ): TextSessionResources = acquireTextSessionResources(
            scope = scope,
            request = TextSessionRequest(
                document.bookId,
                textIndexPlan.documentVersion,
                document.pageCount,
                TextSource.NATIVE_PDF,
                document.textEngineVersion
            ),
            openIndex = {
                if (textIndexPlan.persistent) openTextIndex(context)
                else TransientTextPageIndex(textIndexPlan.fallbackFailure)
            },
            createLoader = SessionTextLoaderFactory { textIndex, keyFactory ->
                TextPageLoader(
                    document = document.pdf,
                    pageCount = document.pageCount,
                    deliver = { action -> main.post(action) },
                    index = textIndex,
                    indexKey = keyFactory,
                    ocrKey = ocrPlan.version?.let { version ->
                        { pageIndex: Int -> ocrKey(keyFactory(pageIndex), pageIndex, version) }
                    },
                    initialOcrFailure = ocrPlan.failure,
                    onOcrEligible = ocrDispatch::enqueue,
                    onOcrStatusChanged = ocrStatusDispatch::publish
                )
            }
        )

        private fun ocrSessionPlan(context: Context): OcrSessionPlan {
            val descriptor = runCatching { OcrEngines.loadDescriptor() }
            val identity = descriptor.mapCatching { it.textEngineVersion(OcrRequest.DEFAULT) }
            val factory: (() -> OcrEngine)? = if (identity.isSuccess) {
                val loaded = requireNotNull(descriptor.getOrNull())
                val createEngine: () -> OcrEngine = {
                    loaded.create(
                        OcrEngineEnvironment(File(context.filesDir, "folium-ocr")) { language: OcrLanguage ->
                            context.assets.open("tessdata/${language.code}.traineddata")
                        }
                    )
                }
                createEngine
            } else null
            return OcrSessionPlan(identity.getOrNull(), identity.exceptionOrNull(), factory)
        }

        private fun ocrKey(
            native: com.folium.reader.index.TextPageIndexKey,
            pageIndex: Int,
            version: com.folium.reader.core.text.TextEngineVersion
        ) = OcrPageKey(
            native.bookId,
            native.documentVersion,
            pageIndex,
            native.textSchemaVersion,
            native.engineVersion,
            NATIVE_TEXT_USABILITY_POLICY_VERSION,
            version
        )

        /** Asks the system what it has spare right now; see [readerShareOf] for what is done with it. */
        private fun cacheBudgetBytes(context: Context): Long {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)

            return readerShareOf(memory.availMem, memory.threshold)
        }

        private fun openTextIndex(context: Context): TextPageIndex {
            val database = TextPageDatabase.open(context)
            return try {
                RoomTextPageIndex.named(database, TextPageDatabase.identity(context))
            } catch (failure: Throwable) {
                try {
                    database.close()
                } catch (cleanupFailure: Throwable) {
                    if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }

        private fun closeSchedulersAfterFailure(
            schedulers: List<ViewportScheduler<BorrowedPage>>,
            failure: Throwable
        ) {
            schedulers.asReversed().forEach { scheduler ->
                try {
                    scheduler.close()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
        }
    }
}

/**
 * Builds a fresh presenter over [document]'s current layout, exactly as [ReaderSession.build] builds
 * the first one — a new [PdfPageRenderer] at [generation] so cached rasters from every earlier
 * generation are never served for what is now different page content, and new detail/base schedulers
 * through the same recipe [ReaderSession.build] uses. On construction failure, every scheduler this
 * call created is closed before the failure propagates, so a caller never leaks a worker pool.
 */
private fun buildRepaginatedPresenter(
    document: ReaderDocument,
    rig: RepaginationRig,
    generation: Long,
    initialPage: Int
): ReaderPresenter<BorrowedPage> {
    lateinit var presenterRef: ReaderPresenter<BorrowedPage>
    val createdSchedulers = mutableListOf<ViewportScheduler<BorrowedPage>>()
    val renderer = PdfPageRenderer(
        document = document.pdf,
        documentId = document.bookId.value,
        generation = generation,
        cache = rig.cache,
        priorityGate = rig.priorityGate,
        onPageMeasured = { pageIndex, measure ->
            if (document.measureIfUnknown(pageIndex, measure)) {
                rig.mainPost { presenterRef.dispatch(GestureIntent.ViewportResized) }
            }
        }
    )

    return try {
        ReaderPresenter(
            pageCount = document.pageCount,
            cacheBudgetBytes = rig.cacheBudgetBytes,
            releaseValue = BorrowedPage::release,
            pageAspect = document::aspect,
            scheduleRetry = rig.scheduleRetry,
            deliverToPresenter = rig.mainPost,
            onChanged = rig.onChanged,
            initialPage = initialPage,
            baseSchedulerFactory = { onOutcome ->
                ViewportScheduler(
                    BASE_TIER_RENDER_WORKERS,
                    renderer,
                    workerPoolName = "render-base",
                    onOutcome = onOutcome
                ).also(createdSchedulers::add)
            }
        ) { onOutcome ->
            ViewportScheduler(
                RENDER_WORKERS,
                renderer,
                workerPoolName = "render-detail",
                onOutcome = onOutcome
            ).also(createdSchedulers::add)
        }.also { presenterRef = it }
    } catch (failure: Throwable) {
        createdSchedulers.asReversed().forEach { scheduler ->
            try {
                scheduler.close()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
        }
        throw failure
    }
}

internal fun createSessionOcrPipeline(
    document: com.folium.reader.core.pdf.PdfDocument,
    pageCount: Int,
    engineFactory: () -> OcrEngine,
    textLoader: SessionTextLoader,
    priorityGate: DocumentPriorityGate,
    policy: OcrRasterPolicy,
    onStopped: () -> Unit,
    onSearchStateChanged: (SearchOcrPlanState) -> Unit = {}
): OcrPagePipeline = OcrPagePipeline(
    document,
    pageCount,
    engineFactory,
    ReaderSessionOcrClaimReporter(
        textLoader::planOcr,
        textLoader::resumePausedOcr,
        textLoader::claimOcr,
        textLoader::completeOcr,
        textLoader::failOcr,
        textLoader::cancelOcr
    ),
    priorityGate,
    policy,
    onStopped,
    onSearchStateChanged = onSearchStateChanged
)

internal class OcrPipelineDispatch {
    private val pending = LinkedHashSet<Int>()
    private var pipeline: OcrPagePipeline? = null

    @Synchronized fun enqueue(pageIndex: Int) {
        val target = pipeline
        if (target == null) pending += pageIndex else target.enqueue(pageIndex)
    }

    @Synchronized fun attach(pipeline: OcrPagePipeline?) {
        this.pipeline = pipeline
        if (pipeline != null) pending.forEach(pipeline::enqueue)
        pending.clear()
    }
}

/** Mirrors [OcrStatusDispatch]: a swappable observer over the pipeline's own [ThumbnailGridState] publications, so it survives every rebuild [repaginate] does to the pipeline behind it. */
internal class ThumbnailStatusDispatch {
    @Volatile private var observer: ((ThumbnailGridState<BorrowedThumbnail>) -> Unit)? = null

    fun observe(observer: ((ThumbnailGridState<BorrowedThumbnail>) -> Unit)?) {
        this.observer = observer
    }

    fun publish(state: ThumbnailGridState<BorrowedThumbnail>) {
        observer?.invoke(state)
    }
}

internal class OcrStatusDispatch {
    @Volatile private var observer: ((Int, OcrPageStatus) -> Unit)? = null

    fun observe(observer: ((Int, OcrPageStatus) -> Unit)?) {
        this.observer = observer
    }

    fun publish(pageIndex: Int, status: OcrPageStatus) {
        observer?.invoke(pageIndex, status)
    }
}

internal class SearchOcrStatusDispatch(
    private val deliver: ((() -> Unit) -> Unit) = { it() }
) {
    @Volatile private var observer: ((SearchOcrPlanState) -> Unit)? = null

    fun observe(observer: ((SearchOcrPlanState) -> Unit)?) {
        this.observer = observer
    }

    fun publish(state: SearchOcrPlanState) {
        deliver { observer?.invoke(state) }
    }
}

sealed class ReaderSessionResult {
    data class Opened(val session: ReaderSession) : ReaderSessionResult()
    data object Missing : ReaderSessionResult()
    data class Unreadable(val failure: PdfFailure) : ReaderSessionResult()
}
