package com.folium.reader.core.diskcache

import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Whole-page rasters are large, flat fields of near-identical pixels — page background, then text —
 * so a fast, general-purpose compressor already removes most of the redundancy a slower one would
 * spend much more time chasing. Level 1 measured the same size as lossless WebP on the target
 * device, three times faster to encode, with decode speed on par; there is nothing left to trade
 * decode speed for by going slower here.
 */
private const val DEFLATE_LEVEL = Deflater.BEST_SPEED

/** Compresses [rgba] with [Deflater] at [DEFLATE_LEVEL]. */
fun deflateRaster(rgba: ByteArray): ByteArray {
    val deflater = Deflater(DEFLATE_LEVEL)
    try {
        deflater.setInput(rgba)
        deflater.finish()
        val output = java.io.ByteArrayOutputStream(rgba.size / 2)
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            val written = deflater.deflate(buffer)
            output.write(buffer, 0, written)
        }
        return output.toByteArray()
    } finally {
        deflater.end()
    }
}

/**
 * Inflates [compressed] back into exactly [uncompressedByteCount] bytes, or returns null for any
 * malformed input: a short read, a stream that produces the wrong number of bytes, or corrupt
 * deflate data. Never throws — a corrupt payload is a cache miss to every caller, not an exception.
 */
fun inflateRaster(compressed: ByteArray, uncompressedByteCount: Int): ByteArray? {
    val inflater = Inflater()
    return try {
        inflater.setInput(compressed)
        val output = ByteArray(uncompressedByteCount)
        var offset = 0
        while (offset < uncompressedByteCount) {
            if (inflater.needsInput() || inflater.needsDictionary()) return null
            val written = inflater.inflate(output, offset, uncompressedByteCount - offset)
            if (written == 0 && inflater.finished()) return null
            offset += written
        }
        if (!inflater.finished() && inflater.inflate(ByteArray(1)) > 0) return null
        output
    } catch (_: java.util.zip.DataFormatException) {
        null
    } finally {
        inflater.end()
    }
}
