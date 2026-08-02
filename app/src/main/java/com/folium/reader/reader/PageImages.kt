package com.folium.reader.reader

import android.graphics.Bitmap
import com.folium.reader.core.pdf.CachedPage
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.Raster
import java.nio.ByteBuffer

/**
 * A rasterized region of one page, owned by the page cache.
 *
 * [region] is the part of the page [bitmap] covers, which is what lets it be drawn correctly under
 * a viewport it was not rendered for — see [ReaderGeometry.destination].
 */
class RenderedPage(val bitmap: Bitmap, val region: PageSpaceRect) {
    val byteCount: Long = bitmap.allocationByteCount.toLong()

    /**
     * Safe only for a page that was never shared into a cache — see
     * [PdfPageRenderer.rasterizeInto]'s cancellation branch, its only caller. A cache-held page's
     * bitmap is never recycled through this, or any other, path — see that same function's doc for
     * why.
     */
    fun recycle() = bitmap.recycle()
}

/**
 * A rendered page the reader can draw, in one of two forms depending on whether [ByteBoundedPageCache]
 * had room to keep it.
 *
 * Exactly one [release] per borrow is required — this is the only handle the reader ever holds a
 * rendered page through, so it is also the only place that discipline has to be honoured. Which
 * subtype a caller receives determines what [release] actually does, which is deliberately made a
 * type-level distinction rather than a flag or a bookkeeping entry the presenter has to remember to
 * check: a [Cached] borrow's resource is owned and freed by the cache on its own schedule, while an
 * [Uncached] one is owned outright by whoever holds this handle and is freed the moment they release
 * it.
 */
sealed class BorrowedPage {
    abstract val bitmap: Bitmap
    abstract val region: PageSpaceRect
    abstract fun release()

    /**
     * A raster [ByteBoundedPageCache] is holding on this borrow's behalf: [release] only lifts the
     * pin, and the cache itself decides independently whether, and when, the underlying bitmap is
     * actually freed.
     */
    class Cached internal constructor(private val borrow: CachedPage<RenderedPage>) : BorrowedPage() {
        override val bitmap: Bitmap get() = borrow.value.bitmap
        override val region: PageSpaceRect get() = borrow.value.region
        override fun release() = borrow.release()
    }

    /**
     * A raster [ByteBoundedPageCache] declined to retain — too large on its own, or crowded out by
     * borrows already pinned elsewhere — handed back directly instead of being reported as a render
     * failure: a page the reader asked for is always something to draw, whether or not there was
     * room to keep it around for next time. Nothing else ever references this bitmap, since the
     * cache never admitted it, so [release] recycles it directly rather than going through the
     * cache's own release path.
     */
    class Uncached internal constructor(private val page: RenderedPage) : BorrowedPage() {
        override val bitmap: Bitmap get() = page.bitmap
        override val region: PageSpaceRect get() = page.region
        override fun release() = page.recycle()
    }
}

/**
 * Converts a raster into a bitmap.
 *
 * The engine rasterizes onto an opaque white ground, so the alpha channel it produces is uniformly
 * saturated and carries no information. Marking the bitmap as having no alpha lets the platform
 * skip blending it, and sidesteps the premultiplication question entirely.
 */
internal fun Raster.toBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
    bitmap.setHasAlpha(false)
    return bitmap
}
