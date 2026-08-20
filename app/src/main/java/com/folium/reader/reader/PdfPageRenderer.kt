package com.folium.reader.reader

import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.PageCacheKey
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.ViewportRenderRequest
import com.folium.reader.core.pdf.ViewportRenderer

/**
 * Turns a scheduled page request into a borrowed, cached raster.
 *
 * Every result the reader ever displays comes from [cache]: a request that is already cached is
 * served without touching the engine at all, and one that is not is rasterized, handed to the cache
 * and then borrowed straight back. Nothing here ever hands out a bitmap the cache does not own, so
 * there is exactly one thing that can free a page — the cache — and exactly one way to keep it
 * alive while it is on screen, which is to hold the borrow.
 *
 * A page's display list is built and closed around each render rather than kept: holding one open
 * would pin native memory per cached page for the whole session, and the cost of rebuilding it is
 * paid only when a raster is genuinely missing from [cache].
 */
internal class PdfPageRenderer(
    private val document: PdfDocument,
    private val documentId: String,
    private val generation: Long,
    private val cache: ByteBoundedPageCache<RenderedPage>,
    private val priorityGate: DocumentPriorityGate,
    private val onPageMeasured: (Int, (Int) -> Float) -> Unit
) : ViewportRenderer<BorrowedPage> {

    override fun render(
        request: ViewportRenderRequest,
        cancellationSignal: CancellationSignal
    ): RenderCandidate<BorrowedPage> = priorityGate.foreground {
        val key = PageCacheKey(documentId, request.pageIndex, generation, request.spec)

        cache.acquire(key)?.let { return@foreground cachedCandidate(it) }
        abortIfCancelled(cancellationSignal)

        reportAspect(request.pageIndex)
        rasterize(key, request, cancellationSignal)
    }

    private fun cachedCandidate(borrow: com.folium.reader.core.pdf.CachedPage<RenderedPage>): RenderCandidate<BorrowedPage> =
        RenderCandidate<BorrowedPage>(BorrowedPage.Cached(borrow)) { it.release() }

    private fun uncachedCandidate(page: RenderedPage): RenderCandidate<BorrowedPage> =
        RenderCandidate<BorrowedPage>(BorrowedPage.Uncached(page)) { it.release() }

    /**
     * The layout that produced this request assumed a page shape. Reporting the real one lets the
     * reader correct itself for documents whose pages are not all alike, without every page having
     * to be measured up front before anything can be shown.
     *
     * The measurement is offered rather than taken: whoever holds the shapes decides whether this
     * page still needs one, so a page already measured costs nothing to render again.
     */
    private fun reportAspect(pageIndex: Int) {
        onPageMeasured(pageIndex) { index ->
            val info = document.pageInfo(index)
            info.width / info.height
        }
    }

    /**
     * [cache] is always offered the raster, whether or not it has room to keep it. A raster it
     * retains is then read back out through [cache.acquire], exactly like a cache hit, so the
     * borrow the caller ends up with is always taken out of the cache rather than kept from this
     * side of the handover — see [BorrowedPage.Cached]. One it declines — too large on its own, or
     * crowded out by borrows held elsewhere — is handed back directly instead: see
     * [BorrowedPage.Uncached]. Declining is therefore never treated as a failure here; a page the
     * reader asked for is always something to draw, whether or not it was worth keeping around.
     *
     * The cache's own release of a candidate it retained but later evicts, invalidates, trims or
     * clears deliberately does not recycle [RenderedPage.bitmap]. Once a raster has been shared into
     * the cache, [ReaderScreen] can be holding the derived `ImageBitmap` in a display list a frame
     * away from actually being drawn, on the render thread, independently of when the last borrow
     * drops or the cache decides to evict — so nothing on this side of the handover can prove the
     * bitmap is safe to recycle synchronously. It is left for the garbage collector instead: since
     * API 26 a [android.graphics.Bitmap]'s native pixel storage is reclaimed with the object, so
     * what [cache]'s byte budget bounds is how much is *retained*, not how much is allocated. This is
     * different from the cancellation branch below, and from a declined [BorrowedPage.Uncached],
     * neither of which is ever shared anywhere and both of which are safe to recycle directly.
     */
    private fun rasterize(
        key: PageCacheKey,
        request: ViewportRenderRequest,
        cancellationSignal: CancellationSignal
    ): RenderCandidate<BorrowedPage> {
        val displayList = document.buildDisplayList(request.pageIndex)
        val page = try {
            val raster = displayList.render(request.spec, cancellationSignal)
            RenderedPage(raster.toBitmap(), request.spec.pageSpace)
        } finally {
            displayList.close()
        }

        if (cancellationSignal.isCancelled()) {
            page.recycle()
            throw PdfException(PdfFailure.Resource(retryable = true))
        }

        val retained = cache.put(key, RenderCandidate(page) {}, page.byteCount)
        if (!retained) return uncachedCandidate(page)

        return cache.acquire(key)?.let { cachedCandidate(it) } ?: uncachedCandidate(page)
    }

    private fun abortIfCancelled(cancellationSignal: CancellationSignal) {
        if (cancellationSignal.isCancelled()) throw PdfException(PdfFailure.Resource(retryable = true))
    }
}
