package com.folium.reader.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class InkSampleCodecTest {

    private val quantisationError = 1f / 65536f
    private val pressureError = 1f / 65535f
    private val angleError = (Math.PI / 32768.0).toFloat()

    private fun assertOptionalWithinTolerance(label: String, expected: Float?, actual: Float?, tolerance: Float) {
        assertEquals("$label nullability differs", expected == null, actual == null)
        if (expected != null && actual != null) {
            assertTrue("$label mismatch: expected=$expected actual=$actual", abs(expected - actual) <= tolerance)
        }
    }

    private fun assertSamplesEqualWithinQuantisation(expected: List<InkSample>, actual: List<InkSample>) {
        assertEquals(expected.size, actual.size)
        for (index in expected.indices) {
            val e = expected[index]
            val a = actual[index]
            assertTrue("x mismatch at $index", abs(e.x - a.x) <= quantisationError)
            assertTrue("y mismatch at $index", abs(e.y - a.y) <= quantisationError)
            assertEquals(e.elapsedMillis, a.elapsedMillis)
            assertOptionalWithinTolerance("pressure at $index", e.pressure, a.pressure, pressureError)
            assertOptionalWithinTolerance("tilt at $index", e.tiltRadians, a.tiltRadians, angleError)
            assertOptionalWithinTolerance("orientation at $index", e.orientationRadians, a.orientationRadians, angleError)
        }
    }

    @Test
    fun roundTripsWithoutOptionalChannels() {
        val samples = listOf(
            InkSample(0.1f, 0.2f, 0),
            InkSample(0.15f, 0.25f, 12),
            InkSample(0.2f, 0.3f, 30)
        )
        val decoded = InkSampleCodec.decode(InkSampleCodec.encode(samples))
        assertSamplesEqualWithinQuantisation(samples, decoded)
    }

    @Test
    fun roundTripsWithEveryOptionalChannelPresent() {
        val samples = listOf(
            InkSample(0.1f, 0.2f, 0, pressure = 0.1f, tiltRadians = 0.4f, orientationRadians = -1.2f),
            InkSample(0.11f, 0.22f, 8, pressure = 0.9f, tiltRadians = -0.4f, orientationRadians = 2.5f)
        )
        val decoded = InkSampleCodec.decode(InkSampleCodec.encode(samples))
        assertSamplesEqualWithinQuantisation(samples, decoded)
    }

    @Test
    fun roundTripsWithOnlySomeOptionalChannelsPresent() {
        val samples = listOf(
            InkSample(0.1f, 0.2f, 0, pressure = 0.3f),
            InkSample(0.12f, 0.24f, 5, pressure = 0.6f)
        )
        val decoded = InkSampleCodec.decode(InkSampleCodec.encode(samples))
        assertSamplesEqualWithinQuantisation(samples, decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mixingPresentAndAbsentWithinAChannelIsRejected() {
        val samples = listOf(
            InkSample(0.1f, 0.2f, 0, pressure = 0.3f),
            InkSample(0.12f, 0.24f, 5, pressure = null)
        )
        InkSampleCodec.encode(samples)
    }

    @Test
    fun quantisationErrorNeverExceedsHalfAGridStep() {
        val random = Random(42)
        val samples = (0 until 500).map {
            InkSample(random.nextFloat(), random.nextFloat() * 3f, it * 7)
        }
        val decoded = InkSampleCodec.decode(InkSampleCodec.encode(samples))
        for (index in samples.indices) {
            assertTrue(abs(samples[index].x - decoded[index].x) <= quantisationError)
            assertTrue(abs(samples[index].y - decoded[index].y) <= quantisationError)
        }
    }

    @Test
    fun precisionHoldsAtALargeYFarFromTheOrigin() {
        val samples = listOf(
            InkSample(0.4f, 5000.0f, 0),
            InkSample(0.41f, 5000.01f, 10),
            InkSample(0.42f, 4999.99f, 20)
        )
        val decoded = InkSampleCodec.decode(InkSampleCodec.encode(samples))
        for (index in samples.indices) {
            assertTrue(abs(samples[index].y - decoded[index].y) <= quantisationError)
        }
    }

    @Test
    fun precisionHoldsForNegativeAndLargeMagnitudesOnBothAxes() {
        val samples = listOf(
            InkSample(-3000.5f, 5000.25f, 0),
            InkSample(-3000.49f, 5000.26f, 5),
            InkSample(3000.5f, -5000.25f, 15)
        )
        val decoded = InkSampleCodec.decode(InkSampleCodec.encode(samples))
        for (index in samples.indices) {
            assertTrue(abs(samples[index].x - decoded[index].x) <= quantisationError)
            assertTrue(abs(samples[index].y - decoded[index].y) <= quantisationError)
        }
    }

    @Test
    fun negativeXDeltasRoundTripAcrossTheOrigin() {
        val samples = listOf(
            InkSample(0.5f, 0.5f, 0),
            InkSample(-0.5f, -0.5f, 10),
            InkSample(0.25f, -0.25f, 20)
        )
        val decoded = InkSampleCodec.decode(InkSampleCodec.encode(samples))
        assertSamplesEqualWithinQuantisation(samples, decoded)
    }

    @Test
    fun aSingleSampleRoundTrips() {
        val samples = listOf(InkSample(0.5f, 0.5f, 0, pressure = 0.5f))
        val decoded = InkSampleCodec.decode(InkSampleCodec.encode(samples))
        assertSamplesEqualWithinQuantisation(samples, decoded)
    }

    @Test
    fun tenThousandSamplesRoundTripWithNoAccumulatedDrift() {
        val random = Random(7)
        var x = 0f
        var y = 0f
        val samples = (0 until 10_000).map { index ->
            x = (x + random.nextFloat() * 0.001f).coerceIn(0f, 1f)
            y += random.nextFloat() * 0.001f
            InkSample(x, y, index)
        }
        val decoded = InkSampleCodec.decode(InkSampleCodec.encode(samples))
        assertSamplesEqualWithinQuantisation(samples, decoded)
    }

    @Test
    fun aLargeRepetitiveStrokeTakesTheDeflatePath() {
        val samples = (0 until 5000).map { InkSample(0.5f, 1f, it) }
        val encoded = InkSampleCodec.encode(samples)
        assertTrue(encoded[5].toInt() and 0x08 != 0)
        assertSamplesEqualWithinQuantisation(samples, InkSampleCodec.decode(encoded))
    }

    @Test
    fun aSmallStrokeDoesNotTakeTheDeflatePath() {
        val samples = listOf(InkSample(0.1f, 0.1f, 0), InkSample(0.2f, 0.2f, 10))
        val encoded = InkSampleCodec.encode(samples)
        assertTrue(encoded[5].toInt() and 0x08 == 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encodingAnEmptySampleListIsRejected() {
        InkSampleCodec.encode(emptyList())
    }

    @Test(expected = InkCodecException.InvalidMagic::class)
    fun decodingRejectsAWrongMagic() {
        val encoded = InkSampleCodec.encode(listOf(InkSample(0.1f, 0.1f, 0)))
        encoded[0] = 0
        InkSampleCodec.decode(encoded)
    }

    @Test(expected = InkCodecException.UnsupportedVersion::class)
    fun decodingRejectsAnUnknownVersion() {
        val encoded = InkSampleCodec.encode(listOf(InkSample(0.1f, 0.1f, 0)))
        encoded[4] = 99
        InkSampleCodec.decode(encoded)
    }

    @Test(expected = InkCodecException.Truncated::class)
    fun decodingRejectsInputTruncatedInTheMagic() {
        InkSampleCodec.decode(byteArrayOf(0x46, 0x4F))
    }

    @Test(expected = InkCodecException.Truncated::class)
    fun decodingRejectsInputTruncatedInTheHeader() {
        val encoded = InkSampleCodec.encode(listOf(InkSample(0.1f, 0.1f, 0)))
        InkSampleCodec.decode(encoded.copyOfRange(0, 6))
    }

    @Test(expected = InkCodecException.Truncated::class)
    fun decodingRejectsInputTruncatedInTheBody() {
        val encoded = InkSampleCodec.encode(listOf(InkSample(0.1f, 0.1f, 0), InkSample(0.2f, 0.2f, 5)))
        InkSampleCodec.decode(encoded.copyOfRange(0, encoded.size - 2))
    }

    @Test(expected = InkCodecException.Truncated::class)
    fun decodingRejectsASampleCountTheBodyCannotHoldBeforeAllocatingForIt() {
        val header = byteArrayOf(0x46, 0x4F, 0x4C, 0x49, 1, 0)
        val twoBillionSamples = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07)

        InkSampleCodec.decode(header + twoBillionSamples + byteArrayOf(0, 0, 0))
    }

    @Test(expected = InkCodecException.Corrupt::class)
    fun decodingRejectsAnUncompressedLengthNoSampleCountCouldNeed() {
        val deflatedFlag: Byte = 0x08
        val header = byteArrayOf(0x46, 0x4F, 0x4C, 0x49, 1, deflatedFlag)
        val oneSample = byteArrayOf(1)
        val twoBillionBytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07)

        InkSampleCodec.decode(header + oneSample + twoBillionBytes + byteArrayOf(0, 0, 0))
    }

    @Test
    fun decodingRejectsEmptyInputWithoutIndexOutOfBounds() {
        try {
            InkSampleCodec.decode(ByteArray(0))
            fail("expected a decode failure for an empty array")
        } catch (e: InkCodecException) {
            assertTrue(e is InkCodecException.Truncated)
        }
    }
}
