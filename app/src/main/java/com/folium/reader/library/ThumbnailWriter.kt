package com.folium.reader.library

import android.graphics.Bitmap
import com.folium.reader.core.pdf.Raster
import com.folium.reader.reader.toBitmap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writes one thumbnail raster to disk, returning whether it succeeded.
 *
 * Isolated as a seam purely for testability: [BookImporter] never needs to know how the encoding
 * happens, and a host test can fake this to avoid touching [Bitmap] entirely.
 */
fun interface ThumbnailWriter {
    fun write(raster: Raster, destination: File): Boolean
}

/** Encodes a raster as PNG using the platform bitmap APIs. */
class BitmapThumbnailWriter : ThumbnailWriter {
    override fun write(raster: Raster, destination: File): Boolean {
        val bitmap = raster.toBitmap()
        return try {
            FileOutputStream(destination).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        } catch (_: IOException) {
            false
        } finally {
            bitmap.recycle()
        }
    }
}
