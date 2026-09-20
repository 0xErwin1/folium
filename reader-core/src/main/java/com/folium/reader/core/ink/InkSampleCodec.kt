package com.folium.reader.core.ink

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.PI
import kotlin.math.round

/** A stroke's sample stream failed to decode. Never thrown for a valid encode() output. */
sealed class InkCodecException(message: String) : Exception(message) {
    /** The first four bytes were not [InkSampleCodec]'s magic; the input is not this format at all. */
    class InvalidMagic : InkCodecException("not an ink sample stream: bad magic")

    /** The header names a version newer (or otherwise unrecognised) than this decoder understands. */
    class UnsupportedVersion(val version: Int) : InkCodecException("unsupported ink sample stream version $version")

    /** The input ended before the header or the declared sample count could be fully read. */
    class Truncated : InkCodecException("ink sample stream ended before it was fully read")

    /** The bytes were the right shape but did not decode to sensible data, e.g. a corrupt deflate body. */
    class Corrupt(reason: String) : InkCodecException("corrupt ink sample stream: $reason")
}

/**
 * Encodes and decodes an [InkStroke]'s [InkSample] list to and from a compact binary form.
 *
 * ## Layout
 * ```
 * offset  size  field
 * 0       4     magic, big-endian int 0x464F_4C49 ("FOLI")
 * 4       1     version, currently 1
 * 5       1     flags bitmask:
 *                 bit 0 (0x01) HAS_PRESSURE    -- every sample carries a pressure value
 *                 bit 1 (0x02) HAS_TILT        -- every sample carries a tilt value
 *                 bit 2 (0x04) HAS_ORIENTATION -- every sample carries an orientation value
 *                 bit 3 (0x08) DEFLATED        -- the body below is compressed
 * 6       var   sampleCount, unsigned LEB128 varint
 * ?       var   uncompressedBodyByteCount, unsigned LEB128 varint -- only present when DEFLATED is set
 * ?       rest  body: sampleCount records, DEFLATE-compressed when DEFLATED is set, raw otherwise
 * ```
 * Each optional channel is present for every sample or none of them; a stroke that mixes a
 * digitizer's pressure-reporting and non-reporting samples cannot be encoded as one stream.
 *
 * A body record is, in this exact order:
 * ```
 * dx           zigzag varint (64-bit) -- delta of quantised x from the previous sample, the absolute quantised x for the first
 * dy           zigzag varint (64-bit) -- delta of quantised y from the previous sample, the absolute quantised y for the first
 * dElapsed     zigzag varint (32-bit) -- delta of elapsedMillis from the previous sample, 0 for the first
 * pressure     unsigned 16-bit big-endian -- only when HAS_PRESSURE
 * tilt         signed 16-bit big-endian   -- only when HAS_TILT
 * orientation  signed 16-bit big-endian   -- only when HAS_ORIENTATION
 * ```
 *
 * x and y are both unbounded and signed, so both are quantised to a fixed-point grid of `1/32768`
 * of a sheet unit through the same 64-bit integer before being delta-encoded; there is no narrower
 * representation for x, because [SheetPoint] does not assume x is confined to `0..1` the way a
 * fixed-width column does. The maximum quantisation error on either axis is half a grid step,
 * `1/65536` of a sheet unit (~1.526e-5), independent of how far from the origin a sample sits on
 * either axis, because quantisation is done in `Double` rather than accumulated in `Float`.
 *
 * Pressure is quantised to an unsigned 16-bit fraction of `0..1`. Tilt and orientation are angles
 * in radians, quantised to a signed 16-bit fraction of `-PI..PI`; a value outside that range is
 * clamped into it before encoding, which adds clamping error on top of the usual quantisation error
 * for a digitizer that reports angles outside that range.
 *
 * The body is DEFLATE-compressed only when doing so is worth the extra header field: the raw body
 * is at least 512 bytes and compressing it saves at least 20%. Decoding never throws anything but
 * [InkCodecException] for malformed input, however short or corrupt.
 */
object InkSampleCodec {
    private const val MAGIC = 0x464F_4C49
    private const val VERSION = 1

    private const val FLAG_HAS_PRESSURE = 0x01
    private const val FLAG_HAS_TILT = 0x02
    private const val FLAG_HAS_ORIENTATION = 0x04
    private const val FLAG_DEFLATED = 0x08

    private const val GRID = 32768.0
    private const val PRESSURE_MAX = 65535.0
    private const val ANGLE_SCALE = 32768.0 / PI

    private const val DEFLATE_MIN_BODY_BYTES = 512
    private const val DEFLATE_MIN_SAVINGS_RATIO = 0.8

    fun encode(samples: List<InkSample>): ByteArray {
        require(samples.isNotEmpty()) { "cannot encode an empty sample list" }

        val hasPressure = channelPresence(samples) { it.pressure }
        val hasTilt = channelPresence(samples) { it.tiltRadians }
        val hasOrientation = channelPresence(samples) { it.orientationRadians }

        val body = encodeBody(samples, hasPressure, hasTilt, hasOrientation)
        val (finalBody, deflated) = maybeDeflate(body)

        val flags = (if (hasPressure) FLAG_HAS_PRESSURE else 0) or
            (if (hasTilt) FLAG_HAS_TILT else 0) or
            (if (hasOrientation) FLAG_HAS_ORIENTATION else 0) or
            (if (deflated) FLAG_DEFLATED else 0)

        val out = ByteArrayOutputStream(finalBody.size + 16)
        out.writeInt32(MAGIC)
        out.write(VERSION)
        out.write(flags)
        out.writeVarUInt(samples.size.toLong())
        if (deflated) out.writeVarUInt(body.size.toLong())
        out.write(finalBody)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): List<InkSample> {
        val reader = ByteReader(bytes)

        if (reader.readInt32() != MAGIC) throw InkCodecException.InvalidMagic()

        val version = reader.readByte() and 0xFF
        if (version != VERSION) throw InkCodecException.UnsupportedVersion(version)

        val flags = reader.readByte() and 0xFF
        val hasPressure = flags and FLAG_HAS_PRESSURE != 0
        val hasTilt = flags and FLAG_HAS_TILT != 0
        val hasOrientation = flags and FLAG_HAS_ORIENTATION != 0
        val deflated = flags and FLAG_DEFLATED != 0

        val sampleCount = reader.readVarUInt()
        if (sampleCount !in 0L..Int.MAX_VALUE.toLong()) throw InkCodecException.Corrupt("impossible sample count $sampleCount")

        val largestBody = sampleCount * MAX_SAMPLE_BYTES

        val body = if (deflated) {
            val uncompressedByteCount = reader.readVarUInt()
            if (uncompressedByteCount !in 0L..minOf(largestBody, Int.MAX_VALUE.toLong())) {
                throw InkCodecException.Corrupt("impossible uncompressed body length $uncompressedByteCount")
            }
            inflate(reader.remaining(), uncompressedByteCount.toInt())
        } else {
            reader.remaining()
        }

        if (sampleCount * MIN_SAMPLE_BYTES > body.size) throw InkCodecException.Truncated()

        return decodeBody(ByteReader(body), sampleCount.toInt(), hasPressure, hasTilt, hasOrientation)
    }

    private fun channelPresence(samples: List<InkSample>, selector: (InkSample) -> Any?): Boolean {
        val presentCount = samples.count { selector(it) != null }
        require(presentCount == 0 || presentCount == samples.size) {
            "an optional channel must be present for every sample or none of them"
        }
        return presentCount == samples.size
    }

    private fun encodeBody(
        samples: List<InkSample>,
        hasPressure: Boolean,
        hasTilt: Boolean,
        hasOrientation: Boolean
    ): ByteArray {
        val out = ByteArrayOutputStream(samples.size * 6)

        var previousQx = 0L
        var previousQy = 0L
        var previousElapsed = 0

        for (sample in samples) {
            val qx = quantize(sample.x)
            val qy = quantize(sample.y)

            out.writeVarLong(zigzag64(qx - previousQx))
            out.writeVarLong(zigzag64(qy - previousQy))
            out.writeVarLong(zigzag32(sample.elapsedMillis - previousElapsed))

            previousQx = qx
            previousQy = qy
            previousElapsed = sample.elapsedMillis

            if (hasPressure) out.writeUInt16(quantizePressure(sample.pressure!!))
            if (hasTilt) out.writeInt16(quantizeAngle(sample.tiltRadians!!))
            if (hasOrientation) out.writeInt16(quantizeAngle(sample.orientationRadians!!))
        }

        return out.toByteArray()
    }

    private fun decodeBody(
        reader: ByteReader,
        sampleCount: Int,
        hasPressure: Boolean,
        hasTilt: Boolean,
        hasOrientation: Boolean
    ): List<InkSample> {
        val samples = ArrayList<InkSample>(sampleCount)

        var qx = 0L
        var qy = 0L
        var elapsed = 0

        repeat(sampleCount) {
            qx += unzigzag64(reader.readVarUInt())
            qy += unzigzag64(reader.readVarUInt())
            elapsed += unzigzag32(reader.readVarUInt())

            val pressure = if (hasPressure) dequantizePressure(reader.readUInt16()) else null
            val tilt = if (hasTilt) dequantizeAngle(reader.readInt16()) else null
            val orientation = if (hasOrientation) dequantizeAngle(reader.readInt16()) else null

            samples += InkSample(dequantize(qx), dequantize(qy), elapsed, pressure, tilt, orientation)
        }

        return samples
    }

    /**
     * The fewest and the most bytes one sample can take: three one-byte varints, or two ten-byte and
     * one five-byte varint plus every optional channel. A declared sample count or uncompressed
     * length outside what these allow is rejected before anything is allocated for it, so a corrupt
     * header cannot ask for gigabytes.
     */
    private const val MIN_SAMPLE_BYTES = 3L
    private const val MAX_SAMPLE_BYTES = 31L

    private fun quantize(value: Float): Long = round(value.toDouble() * GRID).toLong()
    private fun dequantize(quantized: Long): Float = (quantized / GRID).toFloat()

    private fun quantizePressure(value: Float): Int =
        round(value.toDouble().coerceIn(0.0, 1.0) * PRESSURE_MAX).toInt()

    private fun dequantizePressure(quantized: Int): Float = (quantized / PRESSURE_MAX).toFloat()

    private fun quantizeAngle(value: Float): Int {
        val clamped = value.toDouble().coerceIn(-PI, PI)
        return round(clamped * ANGLE_SCALE).toInt().coerceIn(-32768, 32767)
    }

    private fun dequantizeAngle(quantized: Int): Float = (quantized / ANGLE_SCALE).toFloat()

    private fun maybeDeflate(body: ByteArray): Pair<ByteArray, Boolean> {
        if (body.size < DEFLATE_MIN_BODY_BYTES) return body to false

        val deflater = Deflater(Deflater.BEST_SPEED)
        val compressed = try {
            deflater.setInput(body)
            deflater.finish()
            val output = ByteArrayOutputStream(body.size / 2)
            val buffer = ByteArray(8 * 1024)
            while (!deflater.finished()) {
                val written = deflater.deflate(buffer)
                output.write(buffer, 0, written)
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }

        return if (compressed.size <= body.size * DEFLATE_MIN_SAVINGS_RATIO) compressed to true else body to false
    }

    private fun inflate(compressed: ByteArray, uncompressedByteCount: Int): ByteArray {
        if (uncompressedByteCount < 0) throw InkCodecException.Corrupt("negative uncompressed body length")

        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val output = ByteArray(uncompressedByteCount)
            var offset = 0
            while (offset < uncompressedByteCount) {
                if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw InkCodecException.Corrupt("deflate body ended early")
                }
                val written = inflater.inflate(output, offset, uncompressedByteCount - offset)
                if (written == 0 && inflater.finished()) {
                    throw InkCodecException.Corrupt("deflate body ended early")
                }
                offset += written
            }
            return output
        } catch (e: java.util.zip.DataFormatException) {
            throw InkCodecException.Corrupt("invalid deflate data: ${e.message}")
        } finally {
            inflater.end()
        }
    }

    private fun zigzag32(value: Int): Long = ((value shl 1) xor (value shr 31)).toLong() and 0xFFFF_FFFFL
    private fun unzigzag32(value: Long): Int {
        val raw = value.toInt()
        return (raw ushr 1) xor -(raw and 1)
    }

    private fun zigzag64(value: Long): Long = (value shl 1) xor (value shr 63)
    private fun unzigzag64(value: Long): Long = (value ushr 1) xor -(value and 1L)

    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        write(value ushr 24)
        write(value ushr 16)
        write(value ushr 8)
        write(value)
    }

    private fun ByteArrayOutputStream.writeUInt16(value: Int) {
        write(value ushr 8)
        write(value)
    }

    private fun ByteArrayOutputStream.writeInt16(value: Int) = writeUInt16(value and 0xFFFF)

    private fun ByteArrayOutputStream.writeVarUInt(value: Long) {
        var remaining = value
        while (true) {
            val sevenBits = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining == 0L) {
                write(sevenBits)
                return
            }
            write(sevenBits or 0x80)
        }
    }

    private fun ByteArrayOutputStream.writeVarLong(zigzagged: Long) = writeVarUInt(zigzagged)

    /**
     * Reads primitives out of a byte array without ever throwing [ArrayIndexOutOfBoundsException]:
     * every read past the end of the array becomes [InkCodecException.Truncated].
     */
    private class ByteReader(private val bytes: ByteArray) {
        private var position = 0

        fun readByte(): Int {
            if (position >= bytes.size) throw InkCodecException.Truncated()
            return bytes[position++].toInt()
        }

        fun readInt32(): Int {
            var value = 0
            repeat(4) { value = (value shl 8) or (readByte() and 0xFF) }
            return value
        }

        fun readUInt16(): Int = ((readByte() and 0xFF) shl 8) or (readByte() and 0xFF)
        fun readInt16(): Int = readUInt16().toShort().toInt()

        fun readVarUInt(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = readByte() and 0xFF
                result = result or ((byte.toLong() and 0x7F) shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (shift >= 64) throw InkCodecException.Corrupt("varint longer than 64 bits")
            }
        }

        fun remaining(): ByteArray = bytes.copyOfRange(position, bytes.size)
    }
}
