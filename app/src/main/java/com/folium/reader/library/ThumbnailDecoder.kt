package com.folium.reader.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Decodes a book's stored thumbnail file into a [Bitmap] sized for a library row, or `null`. */
fun interface ThumbnailDecoder {
    fun decode(file: File): Bitmap?
}

private const val THUMBNAIL_ROW_TARGET_PX = 168

/**
 * Downsamples `thumb.png` toward a row's on-screen size before allocating it, rather than decoding
 * the full 320px-longest-edge file into memory for every row shown. This is the only class in the
 * library package that touches [Bitmap], so the rest of [LibraryController] stays host-testable
 * against a fake [ThumbnailDecoder].
 */
class BitmapFactoryThumbnailDecoder : ThumbnailDecoder {
    override fun decode(file: File): Bitmap? {
        if (!file.isFile) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight))
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun sampleSizeFor(longestEdge: Int): Int {
        var sample = 1
        while (longestEdge / (sample * 2) >= THUMBNAIL_ROW_TARGET_PX) sample *= 2
        return sample
    }
}
