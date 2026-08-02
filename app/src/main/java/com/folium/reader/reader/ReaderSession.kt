package com.folium.reader.reader

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.folium.reader.core.library.ProviderDocumentIdentity
import com.folium.reader.core.library.RecoveryState
import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.ViewportScheduler
import com.folium.reader.pdf.PageCacheMemoryCallbacks

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
class ReaderSession private constructor(
    private val applicationContext: Context,
    private val document: ReaderDocument,
    private val cache: ByteBoundedPageCache<RenderedPage>,
    private val memoryCallbacks: PageCacheMemoryCallbacks,
    val presenter: ReaderPresenter<BorrowedPage>
) {
    val pageCount: Int get() = document.pageCount

    fun pageAspect(pageIndex: Int): Float = document.aspect(pageIndex)

    fun close() {
        applicationContext.unregisterComponentCallbacks(memoryCallbacks)
        presenter.close()
    }

    /**
     * Blocking: drains the scheduler, frees every cached raster and closes the document. Must run
     * off the main thread, and only after [close].
     */
    fun dispose() {
        presenter.shutdown()
        cache.clear()
        document.close()
    }

    companion object {
        /**
         * Blocking: opens the document and builds the session around it. Must run off the main
         * thread. The returned presenter has no viewport yet and has requested nothing.
         */
        fun open(
            context: Context,
            identity: ProviderDocumentIdentity,
            onChanged: (ReaderUiState<BorrowedPage>) -> Unit
        ): ReaderSessionResult {
            val opened = ReaderDocument.open(context, identity)
            val document = when (opened) {
                is ReaderDocumentResult.Opened -> opened.document
                is ReaderDocumentResult.Unavailable -> return ReaderSessionResult.Unavailable(opened.recovery)
                is ReaderDocumentResult.Unreadable -> return ReaderSessionResult.Unreadable(opened.failure)
            }

            return ReaderSessionResult.Opened(build(context.applicationContext, document, onChanged))
        }

        private fun build(
            applicationContext: Context,
            document: ReaderDocument,
            onChanged: (ReaderUiState<BorrowedPage>) -> Unit
        ): ReaderSession {
            val cache = ByteBoundedPageCache<RenderedPage>(cacheBudgetBytes())
            val main = Handler(Looper.getMainLooper())

            lateinit var presenterRef: ReaderPresenter<BorrowedPage>
            val renderer = PdfPageRenderer(
                document = document.pdf,
                documentId = document.documentId,
                generation = 0L,
                cache = cache,
                onPageMeasured = { pageIndex, aspect ->
                    if (document.record(pageIndex, aspect)) {
                        main.post { presenterRef.dispatch(GestureIntent.ViewportResized) }
                    }
                }
            )

            val presenter = ReaderPresenter(
                pageCount = document.pageCount,
                releaseValue = BorrowedPage::release,
                pageAspect = document::aspect,
                scheduleRetry = { delayMillis, action -> main.postDelayed(action, delayMillis) },
                deliverToPresenter = { action -> main.post(action) },
                onChanged = onChanged,
                baseSchedulerFactory = { onOutcome -> ViewportScheduler(BASE_TIER_RENDER_WORKERS, renderer, onOutcome = onOutcome) }
            ) { onOutcome -> ViewportScheduler(RENDER_WORKERS, renderer, onOutcome = onOutcome) }
            presenterRef = presenter

            val memoryCallbacks = PageCacheMemoryCallbacks(cache)
            applicationContext.registerComponentCallbacks(memoryCallbacks)

            return ReaderSession(applicationContext, document, cache, memoryCallbacks, presenter)
        }

        /**
         * A quarter of the heap, bounded at both ends: enough for the requested window at full
         * viewport size on a phone, and never so much that the reader competes with the rest of the
         * app for the heap. [ComponentCallbacks2] trims it further whenever the system asks.
         */
        private fun cacheBudgetBytes(): Long =
            (Runtime.getRuntime().maxMemory() / 4).coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES)
    }
}

sealed class ReaderSessionResult {
    data class Opened(val session: ReaderSession) : ReaderSessionResult()
    data class Unavailable(val recovery: RecoveryState) : ReaderSessionResult()
    data class Unreadable(val failure: PdfFailure) : ReaderSessionResult()
}
