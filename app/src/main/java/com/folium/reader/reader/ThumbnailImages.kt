package com.folium.reader.reader

import android.graphics.Bitmap
import com.folium.reader.core.pdf.CachedPage

/** A rasterized page thumbnail, owned by [ThumbnailPipeline]'s own cache. */
class ThumbnailRaster(val bitmap: Bitmap) {
    val byteCount: Long = bitmap.allocationByteCount.toLong()

    /** Safe only for a raster the cache declined to retain — see [ThumbnailRenderer.rasterize]. */
    fun recycle() = bitmap.recycle()
}

/**
 * A thumbnail the grid can draw, in one of two forms depending on whether the thumbnail cache had
 * room to keep it — mirrors [BorrowedPage] exactly, at the smaller scale a nav thumbnail needs.
 */
sealed class BorrowedThumbnail {
    abstract val bitmap: Bitmap
    abstract fun release()

    class Cached internal constructor(private val borrow: CachedPage<ThumbnailRaster>) : BorrowedThumbnail() {
        override val bitmap: Bitmap get() = borrow.value.bitmap
        override fun release() = borrow.release()
    }

    class Uncached internal constructor(private val raster: ThumbnailRaster) : BorrowedThumbnail() {
        override val bitmap: Bitmap get() = raster.bitmap
        override fun release() = raster.recycle()
    }
}
