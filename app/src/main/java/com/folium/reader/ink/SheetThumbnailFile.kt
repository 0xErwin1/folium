package com.folium.reader.ink

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val THUMBNAIL_FILE_NAME = "thumb.png"
private const val THUMBNAIL_TEMP_SUFFIX = ".tmp"

/**
 * Writes a sheet's thumbnail as `thumb.png`, atomically: encoded to a `.tmp` sibling inside
 * [sheetDir], fsynced, then renamed over whatever thumbnail was there before. A reader of
 * `thumb.png` never observes a half-written file, the same crash-safety
 * [com.folium.reader.core.ink.SheetMetaFile] gives a sheet's own metadata.
 *
 * [bitmap] is recycled once this call returns, success or failure: nothing else in the closing path
 * holds onto it, and leaving that to the caller would be one more thing a failure here could skip.
 */
internal object SheetThumbnailFile {

    fun write(bitmap: Bitmap, sheetDir: File): Boolean {
        val destination = File(sheetDir, THUMBNAIL_FILE_NAME)
        val temp = File(sheetDir, THUMBNAIL_FILE_NAME + THUMBNAIL_TEMP_SUFFIX)

        return try {
            FileOutputStream(temp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.fd.sync()
            }
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) {
            temp.delete()
            false
        } finally {
            bitmap.recycle()
        }
    }
}
