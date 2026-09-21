package com.folium.reader.core.ink

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/** [SheetStrokeLog] record kind for one [SheetTextBox], only ever written once a log has reached [STROKE_LOG_VERSION_2]. */
internal const val KIND_ADD_TEXT: Byte = 3

/**
 * The largest a [SheetTextBox.text]'s UTF-8 encoding may be: generous enough for many pages of typed
 * text, while still bounding how much [SheetTextRecordCodec.decode] will ever allocate from a single
 * record's declared length, the same defensive shape [InkSampleCodec] already applies to its own
 * declared sample counts.
 */
internal const val MAX_TEXT_BYTES: Int = 64 * 1024

/** [SheetTextBox.text] encodes to more UTF-8 bytes than [MAX_TEXT_BYTES]; the box cannot be written. */
class SheetTextTooLongException(val byteCount: Int) :
    IOException("text box text is $byteCount UTF-8 bytes, over the $MAX_TEXT_BYTES limit")

/**
 * Encodes and decodes [SheetTextBox]'s [SheetStrokeLog] record payload. Kept out of
 * [SheetStrokeLog.kt] because the string framing needs its own careful bounds-checking, the same
 * reason [InkSampleCodec] is its own file rather than living inside the log that calls it.
 *
 * ## Payload layout
 * ```
 * kind              1 byte,   KIND_ADD_TEXT
 * id                UTF       StrokeId.value, modified-UTF-8 short string
 * sequence          8 bytes   Long
 * topLeft.x         4 bytes   Float
 * topLeft.y         4 bytes   Float
 * widthSheetUnits   4 bytes   Float
 * heightSheetUnits  4 bytes   Float
 * font              1 byte    SheetTextFont ordinal
 * sizePt            4 bytes   Float
 * style             1 byte    SheetTextStyle ordinal
 * colorArgb         4 bytes   Int
 * textByteCount     4 bytes   Int, at most MAX_TEXT_BYTES
 * text              textByteCount bytes, UTF-8 (not modified-UTF-8, so it is not length-limited to 65535 bytes)
 * ```
 * `kind` itself is read by [SheetStrokeLog.decodeAndApply] before [decode] is called, exactly as
 * `KIND_ADD_STROKE`'s own payload is decoded.
 *
 * `font`, `sizePt` and `style` replace this record's own earlier `style` byte
 * (`SheetTextStyle { BODY, TITLE }`) in place, rather than through a versioned migration: no build has
 * ever shipped a `KIND_ADD_TEXT` record, so there is no persisted layout to carry forward.
 */
internal object SheetTextRecordCodec {

    fun encode(textBox: SheetTextBox): ByteArray {
        val textBytes = textBox.text.toByteArray(StandardCharsets.UTF_8)
        if (textBytes.size > MAX_TEXT_BYTES) throw SheetTextTooLongException(textBytes.size)

        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(KIND_ADD_TEXT.toInt())
            out.writeUTF(textBox.id.value)
            out.writeLong(textBox.sequence)
            out.writeFloat(textBox.topLeft.x)
            out.writeFloat(textBox.topLeft.y)
            out.writeFloat(textBox.widthSheetUnits)
            out.writeFloat(textBox.heightSheetUnits)
            out.writeByte(textBox.font.ordinal)
            out.writeFloat(textBox.sizePt)
            out.writeByte(textBox.style.ordinal)
            out.writeInt(textBox.colorArgb)
            out.writeInt(textBytes.size)
            out.write(textBytes)
        }
        return buffer.toByteArray()
    }

    /** [input] has already had its leading kind byte consumed by the caller. */
    fun decode(input: DataInputStream): SheetTextBox {
        val id = StrokeId(input.readUTF())
        val sequence = input.readLong()
        val topLeft = SheetPoint(input.readFloat(), input.readFloat())
        val widthSheetUnits = input.readFloat()
        val heightSheetUnits = input.readFloat()
        val font = SheetTextFont.entries[input.readByte().toInt() and 0xFF]
        val sizePt = input.readFloat()
        val style = SheetTextStyle.entries[input.readByte().toInt() and 0xFF]
        val colorArgb = input.readInt()

        val textByteCount = input.readInt()
        if (textByteCount < 0 || textByteCount > MAX_TEXT_BYTES) {
            throw IOException("impossible text byte count $textByteCount")
        }
        val textBytes = ByteArray(textByteCount)
        input.readFully(textBytes)
        val text = String(textBytes, StandardCharsets.UTF_8)

        return SheetTextBox(id, topLeft, widthSheetUnits, heightSheetUnits, text, font, sizePt, style, colorArgb, sequence)
    }
}
