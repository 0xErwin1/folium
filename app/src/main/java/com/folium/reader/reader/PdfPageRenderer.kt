package com.folium.reader.reader

import androidx.tracing.Trace
import com.folium.reader.core.diskcache.DiskPageCacheKey
import com.folium.reader.core.diskcache.DiskPageCacheStore
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
 * What identifies a document's persisted rasters on disk, independent of the session-local
 * [PdfPageRenderer.documentId]/[PdfPageRenderer.generation] pair used for the in-memory cache.
 *
 * [contentId] is the document's content-derived identity — see
 * [com.folium.reader.index.DocumentContentVersion] — never a file path or a [documentId], so the
 * same disk entries are reused across sessions over the same file. [layoutVersion] is the stable,
 * content-derived layout fingerprint for a reflowable document — see
 * [com.folium.reader.core.pdf.ReflowStyleSheet.layoutVersion] — and always null for a fixed layout.
 *
 * [measuredAspect] is the page shape the engine reported, which is what an entry stores so a later
 * session can lay the page out without the engine. A raster's own width over height is that shape
 * rounded to whole pixels, and a layout built from it asks for specs a pixel away from the ones
 * already stored.
 */
internal class PersistentPageCacheContext(
    val contentId: String,
    val engineId: String,
    val layoutVersion: String?,
    val store: DiskPageCacheStore,
    val measuredAspect: ((Int) -> Float)? = null
)

/**
 * Names how pages are rasterized, next to the engine's own version, so rasters stored before a
 * change to what a page looks like are never served after it.
 */
internal const val PAGE_RASTER_RENDERING_VERSION = "raster-v1"

/**
 * Turns a scheduled page request into a borrowed, cached raster.
 *
 * Every result the reader ever displays comes from [cache]: a request that is already cached is
 * served without touching the engine at all, and one that is not is rasterized, handed to the cache
 * and then borrowed straight back. Nothing here ever hands out a bitmap the cache does not own, so
 * there is exactly one thing that can free a page — the cache — and exactly one way to keep it
 * alive while it is on screen, which is to hold the borrow.
 *
 * A page's display list is not closed around each render: an engine may keep a small, fixed number
 * of them built across renders, evicting the least recently used once that bound is reached, since
 * a scanned page turn renders the same page several times at different sizes and rebuilding a
 * display list is the dominant cost of a render that is genuinely missing from [cache].
 * [PdfDocument.renderPage] looks the display list up or builds it and renders it in one call, so
 * the engine can do all of it without letting go of the document.
 */
internal class PdfPageRenderer(
    private val document: PdfDocument,
    private val documentId: String,
    private val generation: Long,
    private val cache: ByteBoundedPageCache<RenderedPage>,
    private val priorityGate: DocumentPriorityGate,
    private val onPageMeasured: (Int, (Int) -> Float) -> Unit,
    private val persistentCache: PersistentPageCacheContext? = null
) : ViewportRenderer<BorrowedPage> {

    override fun render(
        request: ViewportRenderRequest,
        cancellationSignal: CancellationSignal
    ): RenderCandidate<BorrowedPage> = traced({ "folium:render:page:${request.pageIndex}:${request.priority}" }) {
        // The wait ends where the gate lets the block in, which is not where it began, so it cannot
        // be a lambda section. It is closed from here as well if the gate never runs the block:
        // a section left open would be closed by the enclosing one and leave that one dangling.
        var waiting = Trace.isEnabled()
        if (waiting) Trace.beginSection("folium:render:wait:foreground:${request.pageIndex}")

        try {
            priorityGate.foreground {
                if (waiting) {
                    Trace.endSection()
                    waiting = false
                }

                renderInForeground(request, cancellationSignal)
            }
        } finally {
            if (waiting) Trace.endSection()
        }
    }

    private fun renderInForeground(
        request: ViewportRenderRequest,
        cancellationSignal: CancellationSignal
    ): RenderCandidate<BorrowedPage> = traced({ "folium:render:hold:foreground:${request.pageIndex}" }) {
        val key = PageCacheKey(documentId, request.pageIndex, generation, request.spec)

        traced({ "folium:render:cache:${request.pageIndex}" }) { cache.acquire(key) }
            ?.let { return cachedCandidate(it) }
        abortIfCancelled(cancellationSignal)

        diskCandidate(key, request, cancellationSignal)?.let { return it }
        abortIfCancelled(cancellationSignal)

        traced({ "folium:render:rasterize:${request.pageIndex}" }) {
            rasterize(key, request, cancellationSignal)
        }
    }

    /**
     * Reads a whole-page raster back from [persistentCache] without ever touching [document]: no
     * [PdfDocument.pageInfo], no [PdfDocument.renderPage]. Returns null for anything that is not
     * eligible — no [persistentCache] configured, [request]'s spec is not whole-page, or nothing is
     * stored under the resulting key — in which case the caller falls through to [rasterize] exactly
     * as it would on a plain cache miss.
     *
     * A hit is folded into [cache] the same way a fresh render is, through the same [cache.put] /
     * [cache.acquire] pair [rasterize] uses, so the borrow this returns is indistinguishable from one
     * that came from the engine.
     */
    private fun diskCandidate(
        key: PageCacheKey,
        request: ViewportRenderRequest,
        cancellationSignal: CancellationSignal
    ): RenderCandidate<BorrowedPage>? {
        val persistent = persistentCache ?: return null
        val diskKey = DiskPageCacheKey.forWholePageSpec(
            persistent.engineId, persistent.contentId, persistent.layoutVersion, request.pageIndex, request.spec
        ) ?: return null

        abortIfCancelled(cancellationSignal)
        val entry = traced({ "folium:disk:read:${request.pageIndex}" }) { persistent.store.read(diskKey) }
        if (entry == null) {
            traced({ "folium:disk:miss:${request.pageIndex}" }) {}
            return null
        }
        traced({ "folium:disk:hit:${request.pageIndex}" }) {}
        abortIfCancelled(cancellationSignal)

        onPageMeasured(request.pageIndex) { entry.pageAspect }

        val page = RenderedPage(entry.toBitmap(), entry.pageSpace)
        val retained = cache.put(key, RenderCandidate(page) {}, page.byteCount)
        return if (retained) cache.acquire(key)?.let { cachedCandidate(it) } ?: uncachedCandidate(page)
        else uncachedCandidate(page)
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
     * different from a declined [BorrowedPage.Uncached], which is never shared anywhere and is safe
     * to recycle directly.
     *
     * A request cancelled by the time the engine returns never reaches this bitmap conversion at
     * all — see the cancellation check this function starts with — so nothing here is ever built
     * only to be thrown away.
     */
    private fun rasterize(
        key: PageCacheKey,
        request: ViewportRenderRequest,
        cancellationSignal: CancellationSignal
    ): RenderCandidate<BorrowedPage> {
        val raster = document.renderPage(
            index = request.pageIndex,
            spec = request.spec,
            cancellationSignal = cancellationSignal,
            beforeRender = { traced({ "folium:render:pageinfo:${request.pageIndex}" }) { reportAspect(request.pageIndex) } }
        )

        if (cancellationSignal.isCancelled()) throw PdfException(PdfFailure.Resource(retryable = true))

        enqueueDiskWrite(request.pageIndex, request.spec, raster.rgba)

        val page = RenderedPage(raster.toBitmap(), request.spec.pageSpace)
        val retained = cache.put(key, RenderCandidate(page) {}, page.byteCount)
        if (!retained) return uncachedCandidate(page)

        return cache.acquire(key)?.let { cachedCandidate(it) } ?: uncachedCandidate(page)
    }

    /**
     * Offers a freshly rendered whole-page raster to [persistentCache] so the next reader of this
     * page, in this session or a later one, can read it back from disk instead of the engine. Only
     * the encode and the write themselves run off this call — see
     * [com.folium.reader.core.diskcache.DiskPageCacheStore.enqueueWrite] — so nothing here waits on
     * disk I/O. [spec]'s own pixel aspect stands in for the page's real one: for a whole-page raster
     * the two are identical by construction, and no engine lookup is spent confirming it.
     */
    private fun enqueueDiskWrite(pageIndex: Int, spec: com.folium.reader.core.pdf.RenderSpec, rgba: ByteArray) {
        val persistent = persistentCache ?: return
        val diskKey = DiskPageCacheKey.forWholePageSpec(
            persistent.engineId, persistent.contentId, persistent.layoutVersion, pageIndex, spec
        ) ?: return

        traced({ "folium:disk:write:$pageIndex" }) {
            val aspect = persistent.measuredAspect?.invoke(pageIndex) ?: (spec.width.toFloat() / spec.height.toFloat())

            persistent.store.enqueueWrite(diskKey, rgba, aspect)
        }
    }

    private fun abortIfCancelled(cancellationSignal: CancellationSignal) {
        if (cancellationSignal.isCancelled()) throw PdfException(PdfFailure.Resource(retryable = true))
    }
}
