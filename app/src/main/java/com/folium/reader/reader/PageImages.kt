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
 *
 * This allocates a full-size bitmap per render, and the raster it copies from is itself a fresh
 * array out of the engine, so a viewport-sized page costs roughly twice its pixel data in short-lived
 * memory. Neither half is currently pooled or reused, and both of the obvious ways to change that
 * were considered and rejected:
 *
 * Reusing bitmaps needs proof that nothing still reads the one being reused, and this codebase has
 * established that no such proof is available on this side of the handover — see
 * [PdfPageRenderer.rasterize], which for exactly that reason declines to recycle a bitmap the cache
 * has ever admitted. A borrow being released and a page being evicted are both weaker facts than
 * "no display list references these pixels", so tying reuse to either would trade an allocation for
 * a torn frame or a crash.
 *
 * [android.graphics.Bitmap.Config.RGB_565] would halve this bitmap, and the alpha channel is
 * genuinely unused, but it also quantizes exactly what a reader exists to show: antialiased glyph
 * edges are grey ramps, which 565 bands. It would also not remove the copy it appears to save, since
 * the engine hands over 8-bit RGBA and the conversion would have to be done per pixel here.
 * Legibility is the product; the memory is not worth it.
 */
internal fun Raster.toBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
    bitmap.setHasAlpha(false)
    return bitmap
}
