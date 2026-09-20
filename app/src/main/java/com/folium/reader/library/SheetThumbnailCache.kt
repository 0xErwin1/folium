package com.folium.reader.library

import android.graphics.Bitmap
import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.ink.SheetSummary
import java.io.File

/**
 * Which decode of a sheet's thumbnail is cached. Unlike a book's cover, a sheet's thumbnail is
 * rewritten every time its screen closes, so the key carries [SheetSummary.updatedAtEpochMillis]
 * rather than just the sheet's id: a fresh stroke or a rename moves that time on, and the decode
 * cached under the old one is dropped rather than served stale.
 */
private data class SheetThumbnailKey(val id: SheetId, val updatedAtEpochMillis: Long)

/**
 * Decodes and caches a handwritten sheet's thumbnail, the same shape [LibraryController] keeps for a
 * book's cover: cheap to call every time the shelf reloads, since [decode] only touches the file
 * system for a sheet this cache has not already decoded under its current key. Blocking I/O; the
 * caller owns whatever thread it runs [decode] on.
 */
internal class SheetThumbnailCache(private val decoder: ThumbnailDecoder = BitmapFactoryThumbnailDecoder()) {
    private val cache = mutableMapOf<SheetThumbnailKey, Bitmap?>()

    /**
     * Decodes whatever of [sheets] this cache has not already decoded under its current key, using
     * [thumbnailFile] to locate each sheet's own `thumb.png`, and returns every entry keyed by id. A
     * sheet no longer present in [sheets] — removed, or renamed to a key this call has not seen
     * before — has its earlier decode dropped rather than kept forever.
     */
    fun decode(sheets: List<SheetSummary>, thumbnailFile: (SheetId) -> File): Map<SheetId, Bitmap?> {
        val liveKeys = sheets.mapTo(mutableSetOf()) { SheetThumbnailKey(it.id, it.updatedAtEpochMillis) }
        cache.keys.retainAll(liveKeys)

        for (sheet in sheets) {
            val key = SheetThumbnailKey(sheet.id, sheet.updatedAtEpochMillis)
            if (key !in cache) cache[key] = decoder.decode(thumbnailFile(sheet.id))
        }

        return sheets.associate { it.id to cache[SheetThumbnailKey(it.id, it.updatedAtEpochMillis)] }
    }
}
