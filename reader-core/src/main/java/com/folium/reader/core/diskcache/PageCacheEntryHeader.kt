package com.folium.reader.core.diskcache

import com.folium.reader.core.pdf.PageSpaceRect
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/** Marks the start of an entry file, so a file from an unrelated format is rejected outright. */
private const val MAGIC: Int = 0x464F_4C43 // "FOLC"

/** A raster read back from disk, together with everything [PdfPageRenderer] needs without the engine. */
data class DiskPageCacheEntry(
    val rgba: ByteArray,
    val width: Int,
    val height: Int,
    val pageSpace: PageSpaceRect,
    /**
     * The page's own shape — what [com.folium.reader.core.pdf.PageInfo.width] divided by
     * [com.folium.reader.core.pdf.PageInfo.height] would report — carried alongside the raster so a
     * disk hit can report it without asking the engine. For a whole-page raster this is exactly
     * [width] divided by [height] at write time, so no separate page lookup is needed to produce it.
     */
    val pageAspect: Float
) {
    override fun equals(other: Any?): Boolean = other is DiskPageCacheEntry &&
        rgba.contentEquals(other.rgba) && width == other.width && height == other.height &&
        pageSpace == other.pageSpace && pageAspect == other.pageAspect

    override fun hashCode(): Int {
        var result = rgba.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + pageSpace.hashCode()
        result = 31 * result + pageAspect.hashCode()
        return result
    }
}

/**
 * Writes [key], [pageAspect] and [rgba] (compressed with [deflateRaster]) as a single
 * self-describing entry, so a later read can validate what it opened against the key it expected
 * without consulting anything outside the file itself.
 */
fun writePageCacheEntry(
    out: DataOutputStream,
    key: DiskPageCacheKey,
    rgba: ByteArray,
    pageAspect: Float
) {
    val payload = deflateRaster(rgba)
    out.writeInt(MAGIC)
    out.write(key.canonicalBytes())
    out.writeFloat(pageAspect)
    out.writeInt(rgba.size)
    out.writeInt(payload.size)
    out.write(payload)
}

/**
 * Reads an entry back and validates it against [expectedKey] field for field, and validates that its
 * payload inflates to exactly the byte count the header declares. Returns null for anything that
 * does not match — a foreign or truncated file, a stale key, a payload that fails to decompress —
 * rather than throwing: every one of those is a plain cache miss to [DiskPageCacheStore].
 */
fun readPageCacheEntry(input: DataInputStream, expectedKey: DiskPageCacheKey): DiskPageCacheEntry? {
    return try {
        if (input.readInt() != MAGIC) return null

        val formatVersion = input.readInt()
        val engineId = input.readUTF()
        val contentId = input.readUTF()
        val hasLayoutVersion = input.readBoolean()
        val layoutVersion = if (hasLayoutVersion) input.readUTF() else null
        val pageIndex = input.readInt()
        val width = input.readInt()
        val height = input.readInt()
        val pageSpace = PageSpaceRect(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())

        val decodedKey = DiskPageCacheKey(formatVersion, engineId, contentId, layoutVersion, pageIndex, width, height, pageSpace)
        if (decodedKey != expectedKey) return null

        val pageAspect = input.readFloat()
        val uncompressedByteCount = input.readInt()
        val payloadLength = input.readInt()
        if (uncompressedByteCount <= 0 || payloadLength <= 0) return null

        val payload = ByteArray(payloadLength)
        input.readFully(payload)

        val rgba = inflateRaster(payload, uncompressedByteCount) ?: return null
        DiskPageCacheEntry(rgba, width, height, pageSpace, pageAspect)
    } catch (_: EOFException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
