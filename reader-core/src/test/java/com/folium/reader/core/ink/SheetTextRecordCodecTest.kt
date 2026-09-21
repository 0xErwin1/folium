package com.folium.reader.core.ink

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetTextRecordCodecTest {

    private fun textBox(
        text: String,
        sequence: Long = 0,
        font: SheetTextFont = SheetTextFont.SANS,
        sizePt: Float = 19f,
        style: SheetTextStyle = SheetTextStyle.BOLD,
        alignment: SheetTextAlignment = SheetTextAlignment.LEFT
    ) = SheetTextBox(
        StrokeId("33333333-3333-3333-3333-333333333333"),
        topLeft = SheetPoint(0.1f, 0.2f), widthSheetUnits = 0.5f, heightSheetUnits = 0.32f,
        text = text, font = font, sizePt = sizePt, style = style, colorArgb = 0xFF112233.toInt(), sequence = sequence,
        alignment = alignment
    )

    /** [SheetStrokeLog.decodeAndApply] reads and consumes the kind byte before calling [SheetTextRecordCodec.decodeAligned]; this test mirrors that by dropping it too. */
    private fun roundTrip(box: SheetTextBox): SheetTextBox {
        val encoded = SheetTextRecordCodec.encode(box)
        val input = DataInputStream(ByteArrayInputStream(encoded))
        input.readByte()
        return SheetTextRecordCodec.decodeAligned(input)
    }

    @Test fun anOrdinaryTextBoxRoundTrips() {
        val box = textBox("Cut the monolith", sequence = 7)
        val decoded = roundTrip(box)

        assertEquals(box.id, decoded.id)
        assertEquals(box.sequence, decoded.sequence)
        assertEquals(box.topLeft, decoded.topLeft)
        assertEquals(box.widthSheetUnits, decoded.widthSheetUnits, 1e-6f)
        assertEquals(box.heightSheetUnits, decoded.heightSheetUnits, 1e-6f)
        assertEquals(box.font, decoded.font)
        assertEquals(box.sizePt, decoded.sizePt, 1e-6f)
        assertEquals(box.style, decoded.style)
        assertEquals(box.alignment, decoded.alignment)
        assertEquals(box.colorArgb, decoded.colorArgb)
        assertEquals(box.text, decoded.text)
    }

    @Test fun everyFontRoundTrips() {
        for (font in SheetTextFont.entries) {
            assertEquals(font, roundTrip(textBox("hi", font = font)).font)
        }
    }

    @Test fun everyStyleRoundTrips() {
        for (style in SheetTextStyle.entries) {
            assertEquals(style, roundTrip(textBox("hi", style = style)).style)
        }
    }

    @Test fun everyAlignmentRoundTrips() {
        for (alignment in SheetTextAlignment.entries) {
            assertEquals(alignment, roundTrip(textBox("hi", alignment = alignment)).alignment)
        }
    }

    @Test fun aSizeAtTheSanityRangeExtremesRoundTrips() {
        assertEquals(1f, roundTrip(textBox("hi", sizePt = 1f)).sizePt, 1e-6f)
        assertEquals(200f, roundTrip(textBox("hi", sizePt = 200f)).sizePt, 1e-6f)
    }

    @Test fun emptyTextRoundTrips() {
        assertEquals("", roundTrip(textBox("")).text)
    }

    @Test fun multiLineTextRoundTrips() {
        val text = "First line\nSecond line\nThird line"
        assertEquals(text, roundTrip(textBox(text)).text)
    }

    @Test fun nonBmpCharactersRoundTrip() {
        val text = "note 📝 done 😀"
        assertEquals(text, roundTrip(textBox(text)).text)
    }

    @Test fun textAtExactlyTheMaximumByteLengthRoundTrips() {
        val text = "a".repeat(MAX_TEXT_BYTES)
        val box = textBox(text)
        assertEquals(MAX_TEXT_BYTES, text.toByteArray(Charsets.UTF_8).size)
        assertEquals(text, roundTrip(box).text)
    }

    @Test(expected = SheetTextTooLongException::class)
    fun textOverTheMaximumByteLengthIsRejectedOnEncode() {
        SheetTextRecordCodec.encode(textBox("a".repeat(MAX_TEXT_BYTES + 1)))
    }

    @Test(expected = java.io.IOException::class)
    fun aDeclaredTextByteCountOverTheMaximumIsRejectedOnDecode() {
        val encoded = SheetTextRecordCodec.encode(textBox("hi"))

        // The declared text byte count is the last 4-byte int before the text bytes themselves.
        val textByteCountOffset = encoded.size - "hi".toByteArray(Charsets.UTF_8).size - 4
        val corrupted = encoded.copyOf()
        writeIntAt(corrupted, textByteCountOffset, MAX_TEXT_BYTES + 1)

        val input = DataInputStream(ByteArrayInputStream(corrupted))
        input.readByte()
        SheetTextRecordCodec.decodeAligned(input)
    }

    @Test fun encodeAlwaysWritesTheAlignedKind() {
        val encoded = SheetTextRecordCodec.encode(textBox("hi"))
        assertEquals(KIND_ADD_TEXT_ALIGNED, encoded[0])
    }

    /**
     * A record a build before alignment existed wrote, hand-built independently of
     * [SheetTextRecordCodec] itself, decodes with [SheetTextAlignment.LEFT] since no alignment byte was
     * ever recorded for it.
     */
    @Test fun aHandBuiltKind4RecordDecodesWithLeftAlignment() {
        val text = "hi"
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val buffer = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(buffer).use { out ->
            out.writeUTF("legacy-box")
            out.writeLong(0L)
            out.writeFloat(0.1f)
            out.writeFloat(0.2f)
            out.writeFloat(0.5f)
            out.writeFloat(0.32f)
            out.writeByte(SheetTextFont.SANS.ordinal)
            out.writeFloat(19f)
            out.writeByte(SheetTextStyle.BOLD.ordinal)
            out.writeInt(0xFF112233.toInt())
            out.writeInt(textBytes.size)
            out.write(textBytes)
        }

        val decoded = SheetTextRecordCodec.decode(DataInputStream(ByteArrayInputStream(buffer.toByteArray())))
        assertEquals(SheetTextAlignment.LEFT, decoded.alignment)
        assertEquals(SheetTextFont.SANS, decoded.font)
        assertEquals(text, decoded.text)
    }

    private fun writeIntAt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
