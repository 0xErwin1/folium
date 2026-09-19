package com.folium.reader.core.diskcache

import com.folium.reader.core.pdf.PageSpaceRect
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageCacheEntryHeaderTest {

    private val key = DiskPageCacheKey(
        formatVersion = 1,
        engineId = "engine-1",
        contentId = "content-1",
        layoutVersion = "layout-1",
        pageIndex = 3,
        width = 20,
        height = 10,
        pageSpace = PageSpaceRect(0f, 0f, 1f, 1f)
    )

    private fun rgba(width: Int, height: Int, seed: Long = 1L): ByteArray {
        val bytes = ByteArray(width * height * 4)
        Random(seed).nextBytes(bytes)
        return bytes
    }

    private fun encode(key: DiskPageCacheKey, rgba: ByteArray, pageAspect: Float = 612f / 792f): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out -> writePageCacheEntry(out, key, rgba, pageAspect) }
        return buffer.toByteArray()
    }

    private fun decode(bytes: ByteArray, expectedKey: DiskPageCacheKey): DiskPageCacheEntry? =
        DataInputStream(ByteArrayInputStream(bytes)).use { readPageCacheEntry(it, expectedKey) }

    @Test fun anEntryRoundTripsExactly() {
        val rgba = rgba(key.width, key.height)
        val entry = decode(encode(key, rgba, pageAspect = 612f / 792f), key)

        requireNotNull(entry)
        assertEquals(key.width, entry.width)
        assertEquals(key.height, entry.height)
        assertEquals(key.pageSpace, entry.pageSpace)
        assertEquals(612f / 792f, entry.pageAspect)
        org.junit.Assert.assertArrayEquals(rgba, entry.rgba)
    }

    @Test fun aDifferentFormatVersionIsAMiss() {
        val encoded = encode(key, rgba(key.width, key.height))
        assertNull(decode(encoded, key.copy(formatVersion = 2)))
    }

    @Test fun aDifferentEngineIdIsAMiss() {
        val encoded = encode(key, rgba(key.width, key.height))
        assertNull(decode(encoded, key.copy(engineId = "engine-2")))
    }

    @Test fun aDifferentContentIdIsAMiss() {
        val encoded = encode(key, rgba(key.width, key.height))
        assertNull(decode(encoded, key.copy(contentId = "content-2")))
    }

    @Test fun aDifferentLayoutVersionIsAMiss() {
        val encoded = encode(key, rgba(key.width, key.height))
        assertNull(decode(encoded, key.copy(layoutVersion = "layout-2")))
    }

    @Test fun aDifferentSpecIsAMiss() {
        val encoded = encode(key, rgba(key.width, key.height))
        assertNull(decode(encoded, key.copy(width = key.width + 1)))
    }

    @Test fun aDifferentPageIsAMiss() {
        val encoded = encode(key, rgba(key.width, key.height))
        assertNull(decode(encoded, key.copy(pageIndex = key.pageIndex + 1)))
    }

    @Test fun aTruncatedFileIsAMissNotAnException() {
        val encoded = encode(key, rgba(key.width, key.height))
        val truncated = encoded.copyOf(encoded.size / 2)
        assertNull(decode(truncated, key))
    }

    @Test fun aCorruptHeaderIsAMissNotAnException() {
        val encoded = encode(key, rgba(key.width, key.height)).copyOf()
        encoded[0] = 0
        assertNull(decode(encoded, key))
    }

    @Test fun aPayloadShorterThanItsDeclaredLengthIsAMiss() {
        val encoded = encode(key, rgba(key.width, key.height))
        val corrupted = encoded.copyOf(encoded.size - 4)
        assertNull(decode(corrupted, key))
    }
}
