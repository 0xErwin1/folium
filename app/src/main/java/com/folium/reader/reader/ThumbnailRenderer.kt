package com.folium.reader.reader

import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.CachedPage
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.PageCacheKey
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.ViewportRenderRequest
import com.folium.reader.core.pdf.ViewportRenderer

/** How many bytes the dedicated thumbnail cache is allowed to hold — see [ThumbnailPipeline]'s own doc for why this is not shared with the page-raster cache. */
internal const val THUMBNAIL_CACHE_BYTES = 24L * 1024 * 1024

/** The longest edge a page thumbnail is ever rasterized at, regardless of the grid's column count — see [ThumbnailPipeline]'s own doc. */
internal const val THUMBNAIL_LONGEST_EDGE_PX = 220

/**
 * Rasterizes a page thumbnail, gated behind every request the reader itself is making.
 *
 * [priorityGate] is the same gate [OcrPagePipeline] yields to the reader through: a thumbnail
 * render never starts while a page the reader actually asked for is in flight, and one already
 * running is aborted the moment such a request begins — see [DocumentPriorityGate.awaitOcrPermit]
 * and [DocumentPriorityGate.isPreempted]. Sharing this gate, rather than relying only on this
 * renderer's own single-worker scheduler, is what keeps a burst of grid scrolling from ever
 * delaying the page under the reader's finger: without it, a thumbnail already dispatched to the
 * engine would hold the document's own render path for the length of its own render regardless of
 * what the reader does next.
 */
internal class ThumbnailRenderer(
    private val document: PdfDocument,
    private val documentId: String,
    private val generation: Long,
    private val cache: ByteBoundedPageCache<ThumbnailRaster>,
    private val priorityGate: DocumentPriorityGate
) : ViewportRenderer<BorrowedThumbnail> {

    override fun render(
        request: ViewportRenderRequest,
        cancellationSignal: CancellationSignal
    ): RenderCandidate<BorrowedThumbnail> = traced({ "folium:thumb:render:${request.pageIndex}" }) {
        val key = PageCacheKey(documentId, request.pageIndex, generation, request.spec)
        traced({ "folium:thumb:cache:${request.pageIndex}" }) { cache.acquire(key) }
            ?.let { return@traced cachedCandidate(it) }

        val permit = traced({ "folium:thumb:wait:ocr-permit:${request.pageIndex}" }) {
            priorityGate.awaitOcrPermit(cancellationSignal::isCancelled)
        } ?: throw PdfException(PdfFailure.Resource(retryable = true))
        val gated = CancellationSignal { cancellationSignal.isCancelled() || priorityGate.isPreempted(permit) }

        rasterize(key, request, gated)
    }

    private fun cachedCandidate(borrow: CachedPage<ThumbnailRaster>): RenderCandidate<BorrowedThumbnail> =
        RenderCandidate<BorrowedThumbnail>(BorrowedThumbnail.Cached(borrow)) { it.release() }

    private fun uncachedCandidate(raster: ThumbnailRaster): RenderCandidate<BorrowedThumbnail> =
        RenderCandidate<BorrowedThumbnail>(BorrowedThumbnail.Uncached(raster)) { it.release() }

    /**
     * [cache] is always offered the raster, exactly like [PdfPageRenderer.rasterize] — see that
     * function's own doc for why a raster it declines to retain is handed back directly instead of
     * being treated as a failure, and why the bitmap behind a candidate it does retain but later
     * evicts is never recycled from this side of the handover.
     */
    private fun rasterize(
        key: PageCacheKey,
        request: ViewportRenderRequest,
        cancellationSignal: CancellationSignal
    ): RenderCandidate<BorrowedThumbnail> {
        if (cancellationSignal.isCancelled()) throw PdfException(PdfFailure.Resource(retryable = true))

        val displayList = traced({ "folium:thumb:displaylist:${request.pageIndex}" }) {
            document.buildDisplayList(request.pageIndex)
        }
        val raster = try {
            traced({
                "folium:thumb:raster:${request.pageIndex}:${request.spec.width}x${request.spec.height}"
            }) {
                ThumbnailRaster(displayList.render(request.spec, cancellationSignal).toBitmap())
            }
        } finally {
            traced({ "folium:thumb:close:${request.pageIndex}" }) { displayList.close() }
        }

        if (cancellationSignal.isCancelled()) {
            raster.recycle()
            throw PdfException(PdfFailure.Resource(retryable = true))
        }

        val retained = cache.put(key, RenderCandidate(raster) {}, raster.byteCount)
        if (!retained) return uncachedCandidate(raster)

        return cache.acquire(key)?.let { cachedCandidate(it) } ?: uncachedCandidate(raster)
    }
}
