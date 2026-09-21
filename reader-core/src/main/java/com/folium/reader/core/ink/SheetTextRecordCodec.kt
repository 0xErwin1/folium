package com.folium.reader.core.ink

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * [SheetStrokeLog] record kind for one [SheetTextBox], written by every current build. Numbered 4
 * rather than 3 because [KIND_ADD_TEXT_LEGACY] already occupies 3 on real sheets; see that constant
 * for why. Only ever written once a log has reached [STROKE_LOG_VERSION_2].
 */
internal const val KIND_ADD_TEXT: Byte = 4

/**
 * The record kind development builds wrote for a [SheetTextBox] before this record's `font`, `sizePt`
 * and `style` fields were designed, back when it stored a single `style` byte holding a now-removed
 * `SheetTextStyle { BODY, TITLE }` ordinal. Those builds shipped to a real tablet before the layout was
 * finalised, so real sheets exist with this kind next to ordinary strokes; unlike the assumption the
 * layout change itself was made under, there is no test-only universe here where it is safe to drop.
 * [SheetTextRecordCodec.decodeLegacy] keeps mapping it onto the current [SheetTextBox] model
 * ([LegacyTextStyle.BODY] to [SheetTextFont.SERIF] at 16pt [SheetTextStyle.NORMAL],
 * [LegacyTextStyle.TITLE] to [SheetTextFont.SANS] at 19pt [SheetTextStyle.BOLD]) so those sheets keep
 * opening, but [SheetTextRecordCodec.encode] never writes it again, and [SheetStrokeLog.compact]
 * rewrites any live box holding one under [KIND_ADD_TEXT] instead.
 */
internal const val KIND_ADD_TEXT_LEGACY: Byte = 3

/** The single `style` byte [KIND_ADD_TEXT_LEGACY] stored before `font` and `sizePt` existed. */
private enum class LegacyTextStyle { BODY, TITLE }

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
 * ## Payload layout ([KIND_ADD_TEXT])
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
 *
 * ## Payload layout ([KIND_ADD_TEXT_LEGACY], read-only)
 * ```
 * kind              1 byte,   KIND_ADD_TEXT_LEGACY
 * id                UTF       StrokeId.value, modified-UTF-8 short string
 * sequence          8 bytes   Long
 * topLeft.x         4 bytes   Float
 * topLeft.y         4 bytes   Float
 * widthSheetUnits   4 bytes   Float
 * heightSheetUnits  4 bytes   Float
 * style             1 byte    LegacyTextStyle ordinal
 * colorArgb         4 bytes   Int
 * textByteCount     4 bytes   Int, at most MAX_TEXT_BYTES
 * text              textByteCount bytes, UTF-8
 * ```
 * `kind` itself is read by [SheetStrokeLog.decodeAndApply] before [decode] or [decodeLegacy] is
 * called, exactly as `KIND_ADD_STROKE`'s own payload is decoded.
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

    /** [input] has already had its leading [KIND_ADD_TEXT] byte consumed by the caller. */
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
        val text = readText(input)

        return SheetTextBox(id, topLeft, widthSheetUnits, heightSheetUnits, text, font, sizePt, style, colorArgb, sequence)
    }

    /**
     * [input] has already had its leading [KIND_ADD_TEXT_LEGACY] byte consumed by the caller. Maps
     * the old single `style` byte onto the current model, as documented on [KIND_ADD_TEXT_LEGACY].
     */
    fun decodeLegacy(input: DataInputStream): SheetTextBox {
        val id = StrokeId(input.readUTF())
        val sequence = input.readLong()
        val topLeft = SheetPoint(input.readFloat(), input.readFloat())
        val widthSheetUnits = input.readFloat()
        val heightSheetUnits = input.readFloat()
        val legacyStyle = LegacyTextStyle.entries[input.readByte().toInt() and 0xFF]
        val colorArgb = input.readInt()
        val text = readText(input)

        val (font, sizePt, style) = when (legacyStyle) {
            LegacyTextStyle.BODY -> Triple(SheetTextFont.SERIF, 16f, SheetTextStyle.NORMAL)
            LegacyTextStyle.TITLE -> Triple(SheetTextFont.SANS, 19f, SheetTextStyle.BOLD)
        }

        return SheetTextBox(id, topLeft, widthSheetUnits, heightSheetUnits, text, font, sizePt, style, colorArgb, sequence)
    }

    private fun readText(input: DataInputStream): String {
        val textByteCount = input.readInt()
        if (textByteCount < 0 || textByteCount > MAX_TEXT_BYTES) {
            throw IOException("impossible text byte count $textByteCount")
        }
        val textBytes = ByteArray(textByteCount)
        input.readFully(textBytes)
        return String(textBytes, StandardCharsets.UTF_8)
    }
}
