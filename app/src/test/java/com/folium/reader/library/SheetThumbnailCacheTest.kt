package com.folium.reader.library

import android.graphics.Bitmap
import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.ink.SheetSummary
import com.folium.reader.core.ink.SheetTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private class SheetThumbnailRecordingDecoder : ThumbnailDecoder {
    val decoded = mutableListOf<File>()
    override fun decode(file: File): Bitmap? {
        decoded += file
        return null
    }
}

class SheetThumbnailCacheTest {

    private fun sheet(id: String, updatedAt: Long) = SheetSummary(
        id = SheetId(id),
        title = "Untitled",
        createdAtEpochMillis = updatedAt,
        updatedAtEpochMillis = updatedAt,
        template = SheetTemplate.BLANK,
        anchor = null
    )

    private fun file(id: SheetId) = File("/sheets/${id.value}/thumb.png")

    @Test
    fun `decodes every sheet the first time it is seen`() {
        val decoder = SheetThumbnailRecordingDecoder()
        val cache = SheetThumbnailCache(decoder)

        val result = cache.decode(listOf(sheet("a", 1L), sheet("b", 2L)), ::file)

        assertEquals(setOf(SheetId("a"), SheetId("b")), result.keys)
        assertEquals(2, decoder.decoded.size)
    }

    @Test
    fun `a second call with the same updated time does not decode again`() {
        val decoder = SheetThumbnailRecordingDecoder()
        val cache = SheetThumbnailCache(decoder)
        val sheets = listOf(sheet("a", 1L))

        cache.decode(sheets, ::file)
        cache.decode(sheets, ::file)

        assertEquals(1, decoder.decoded.size)
    }

    @Test
    fun `a moved updated time decodes again`() {
        val decoder = SheetThumbnailRecordingDecoder()
        val cache = SheetThumbnailCache(decoder)

        cache.decode(listOf(sheet("a", 1L)), ::file)
        cache.decode(listOf(sheet("a", 2L)), ::file)

        assertEquals(2, decoder.decoded.size)
    }

    @Test
    fun `a sheet no longer listed is dropped from the cache`() {
        val decoder = SheetThumbnailRecordingDecoder()
        val cache = SheetThumbnailCache(decoder)

        cache.decode(listOf(sheet("a", 1L), sheet("b", 1L)), ::file)
        cache.decode(listOf(sheet("a", 1L)), ::file)
        cache.decode(listOf(sheet("a", 1L), sheet("b", 1L)), ::file)

        assertTrue("re-adding a dropped sheet decodes it again rather than serving a stale cache entry", decoder.decoded.count { it == file(SheetId("b")) } == 2)
    }
}
