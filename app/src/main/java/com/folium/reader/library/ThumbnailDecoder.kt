package com.folium.reader.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Decodes a book's stored thumbnail file into a [Bitmap] sized for a library row, or `null`. */
fun interface ThumbnailDecoder {
    fun decode(file: File): Bitmap?
}

/**
 * The size a row's thumbnail is drawn at, as its longest edge in pixels: the thumbnail slot is
 * taller than it is wide, so it is [ThumbnailHeight] that has to be compared against the stored
 * file's longest edge, at the densest screen this ships to.
 */
internal const val THUMBNAIL_ROW_TARGET_PX = 252

/**
 * The largest power of two that still leaves the decoded longest edge at or above
 * [THUMBNAIL_ROW_TARGET_PX]. Sampling is what keeps a row's bitmap from being allocated at the
 * stored file's full size, and never sampling past the target is what keeps it from being allocated
 * below the size it is drawn at: a source that is already at or under the target is decoded whole.
 */
internal fun thumbnailSampleSize(longestEdge: Int): Int {
    var sample = 1
    while (longestEdge / (sample * 2) >= THUMBNAIL_ROW_TARGET_PX) sample *= 2
    return sample
}

/**
 * Downsamples `thumb.png` toward a row's on-screen size before allocating it, rather than decoding
 * the full 320px-longest-edge file into memory for every row shown. Decoding is confined to this
 * class — [LibraryController] only ever holds and hands out the results — so the rest of the
 * library stays host-testable against a fake [ThumbnailDecoder].
 */
class BitmapFactoryThumbnailDecoder : ThumbnailDecoder {
    override fun decode(file: File): Bitmap? {
        if (!file.isFile) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = thumbnailSampleSize(maxOf(bounds.outWidth, bounds.outHeight))
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }
}
