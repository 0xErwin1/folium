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
 * A live borrow on a cached [RenderedPage]: the cache will not recycle the underlying bitmap while
 * this is unreleased, no matter what is evicted in the meantime. Exactly one [release] per borrow
 * is required — this is the only handle the reader ever holds a rendered page through, so it is
 * also the only place that discipline has to be honoured.
 */
class BorrowedPage internal constructor(private val borrow: CachedPage<RenderedPage>) {
    val bitmap: Bitmap get() = borrow.value.bitmap
    val region: PageSpaceRect get() = borrow.value.region

    fun release() = borrow.release()
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
