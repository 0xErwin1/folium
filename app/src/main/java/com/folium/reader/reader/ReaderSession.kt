package com.folium.reader.reader

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PdfFailure
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
import com.folium.reader.index.TextPageDatabase
import com.folium.reader.index.TextPageIndex
import com.folium.reader.index.TransientTextPageIndex
import com.folium.reader.index.sha256
import com.folium.reader.pdf.PageCacheMemoryCallbacks
import java.io.File

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

private const val MIN_CACHE_BYTES = 16L * 1024 * 1024
private const val MAX_CACHE_BYTES = 96L * 1024 * 1024
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
    private val textLoader: SessionTextLoader,
    private val lifecycle: ReaderSessionLifecycle,
    private val ocrEngineFactory: (() -> OcrEngine)?,
    private val ocrDispatch: OcrPipelineDispatch,
    private val ocrStatusDispatch: OcrStatusDispatch,
    private val priorityGate: DocumentPriorityGate,
    val presenter: ReaderPresenter<BorrowedPage>
) {
    private val ocrPipeline = ocrEngineFactory?.let { factory ->
        createSessionOcrPipeline(
            document.pdf,
            document.pageCount,
            factory,
            textLoader,
            priorityGate,
            OcrRasterPolicy.forHeap(Runtime.getRuntime().maxMemory()),
            onStopped = textLoader::close
        )
    }

    init {
        ocrDispatch.attach(ocrPipeline)
    }

    val pageCount: Int get() = document.pageCount
    val outline: List<OutlineEntry> get() = document.outline

    fun pageAspect(pageIndex: Int): Float = document.aspect(pageIndex)

    internal fun loadTextPage(pageIndex: Int, callback: (TextPageLoadResult) -> Unit) =
        textLoader.load(pageIndex, callback)

    internal fun searchText(spec: TextSearchSpec, callback: (TextSearchProgress) -> Unit) =
        textLoader.search(spec, callback).also { ocrPipeline?.resume() }

    internal fun closeSearch() {
        textLoader.closeSearch()
        ocrPipeline?.cancel(com.folium.reader.core.ocr.OcrCancellationReason.SEARCH_PAUSE)
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
                ocrPipeline?.resume()
                ocrPipeline?.enqueue(pageIndex)
            }
            callback(result)
        }
    }

    internal fun observeOcrStatus(observer: ((Int, OcrPageStatus) -> Unit)?) {
        ocrStatusDispatch.observe(observer)
    }

    fun close() {
        textLoader.beginOcrDrain()
        if (ocrPipeline == null) textLoader.close() else ocrPipeline.close()
        lifecycle.close()
    }

    /**
     * Blocking: drains the scheduler, frees every cached raster and closes the document. Must run
     * off the main thread, and only after [close].
     */
    fun dispose() {
        ocrPipeline?.dispose()
        lifecycle.dispose()
    }

    companion object {
        /**
         * Blocking: opens the document and builds the session around it. Must run off the main
         * thread. The returned presenter has no viewport yet and has requested nothing.
         */
        fun open(
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
            val cache = scope.acquire(
                factory = { ByteBoundedPageCache<RenderedPage>(cacheBudgetBytes()) },
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
                onPageMeasured = { pageIndex, aspect ->
                    if (document.record(pageIndex, aspect)) {
                        main.post { presenterRef.dispatch(GestureIntent.ViewportResized) }
                    }
                }
            )

            val presenter = scope.acquire(
                factory = {
                    try {
                        ReaderPresenter(
                            pageCount = document.pageCount,
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

            val memoryCallbacks = PageCacheMemoryCallbacks(cache)
            applicationContext.registerComponentCallbacks(memoryCallbacks)
            scope.onCleanup { applicationContext.unregisterComponentCallbacks(memoryCallbacks) }

            val ocrPlan = ocrSessionPlan(applicationContext)
            val ocrDispatch = OcrPipelineDispatch()
            val ocrStatusDispatch = OcrStatusDispatch()
            val textResources = acquireReaderTextResources(
                applicationContext, document, textIndexPlan, ocrPlan, ocrDispatch,
                ocrStatusDispatch, main, scope
            )

            val lifecycle = ReaderSessionLifecycle(
                unregisterCallbacks = { applicationContext.unregisterComponentCallbacks(memoryCallbacks) },
                closeTextLoader = {},
                closePresenter = presenter::close,
                shutdownPresenter = presenter::shutdown,
                disposeTextLoader = textResources.loader::dispose,
                closeTextIndex = textResources.index::close,
                clearPageCache = cache::clear,
                closeDocument = document::close
            )
            return ReaderSession(
                document,
                textResources.loader,
                lifecycle,
                ocrPlan.engineFactory,
                ocrDispatch,
                ocrStatusDispatch,
                priorityGate,
                presenter
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

        /**
         * A quarter of the heap, bounded at both ends: enough for the requested window at full
         * viewport size on a phone, and never so much that the reader competes with the rest of the
         * app for the heap. [ComponentCallbacks2] trims it further whenever the system asks.
         */
        private fun cacheBudgetBytes(): Long =
            (Runtime.getRuntime().maxMemory() / 4).coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES)

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

internal fun createSessionOcrPipeline(
    document: com.folium.reader.core.pdf.PdfDocument,
    pageCount: Int,
    engineFactory: () -> OcrEngine,
    textLoader: SessionTextLoader,
    priorityGate: DocumentPriorityGate,
    policy: OcrRasterPolicy,
    onStopped: () -> Unit
): OcrPagePipeline = OcrPagePipeline(
    document,
    pageCount,
    engineFactory,
    ReaderSessionOcrClaimReporter(
        textLoader::resumePausedOcr,
        textLoader::claimOcr,
        textLoader::completeOcr,
        textLoader::failOcr,
        textLoader::cancelOcr
    ),
    priorityGate,
    policy,
    onStopped
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

internal class OcrStatusDispatch {
    @Volatile private var observer: ((Int, OcrPageStatus) -> Unit)? = null

    fun observe(observer: ((Int, OcrPageStatus) -> Unit)?) {
        this.observer = observer
    }

    fun publish(pageIndex: Int, status: OcrPageStatus) {
        observer?.invoke(pageIndex, status)
    }
}

sealed class ReaderSessionResult {
    data class Opened(val session: ReaderSession) : ReaderSessionResult()
    data object Missing : ReaderSessionResult()
    data class Unreadable(val failure: PdfFailure) : ReaderSessionResult()
}
