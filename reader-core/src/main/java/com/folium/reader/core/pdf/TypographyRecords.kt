package com.folium.reader.core.pdf

import java.util.Locale

/** First line of an app-managed typography file; a file whose first line differs is treated as empty. */
const val TYPOGRAPHY_VERSION_MARKER = "folium-typography 1"

/** First line of an app-managed typography re-pagination cost file. */
const val TYPOGRAPHY_COST_VERSION_MARKER = "folium-typography-cost 1"

private const val FIELD_SEPARATOR = ''

private const val FAMILY_PUBLISHER = "publisher"
private const val FAMILY_SERIF = "serif"
private const val FAMILY_SANS = "sans"
private const val FAMILY_MONOSPACE = "monospace"

private const val ALIGN_PUBLISHER = "publisher"
private const val ALIGN_LEFT = "left"
private const val ALIGN_JUSTIFY = "justify"

private const val PAGE_COLORS_ON = "1"
private const val PAGE_COLORS_OFF = "0"

/**
 * Pure line codec for a [TypographyPreset], following the same field-per-line discipline as
 * [com.folium.reader.core.library.LibraryRecords]. `null` from [decode] means the line is
 * malformed; the caller falls back rather than treating it as fatal, exactly as an unparseable
 * catalog or progress line already does.
 */
object TypographyRecords {
    fun encode(preset: TypographyPreset): String = listOf(
        encodeFamily(preset.fontFamily),
        formatFloat(preset.fontSizePoints),
        preset.lineHeight?.let(::formatFloat).orEmpty(),
        formatFloat(preset.marginEm),
        encodeAlign(preset.textAlign),
        preset.paragraphIndentEm?.let(::formatFloat).orEmpty(),
        if (preset.pageColors) PAGE_COLORS_ON else PAGE_COLORS_OFF
    ).joinToString(FIELD_SEPARATOR.toString())

    /**
     * Rejects on wrong arity, an unparseable number, an unrecognized token, or a value outside
     * [TypographyPreset]'s own documented range — never throws, never partially fills.
     */
    fun decode(line: String): TypographyPreset? {
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size != 7) return null

        val family = decodeFamily(fields[0]) ?: return null
        val fontSizePoints = fields[1].toFloatOrNull() ?: return null
        if (fontSizePoints !in 12f..32f) return null

        val lineHeight = fields[2].takeIf { it.isNotEmpty() }?.let { it.toFloatOrNull() ?: return null }

        val marginEm = fields[3].toFloatOrNull() ?: return null
        if (marginEm !in 0f..4f) return null

        val textAlign = decodeAlign(fields[4]) ?: return null
        val paragraphIndentEm = fields[5].takeIf { it.isNotEmpty() }?.let { it.toFloatOrNull() ?: return null }
        val pageColors = when (fields[6]) {
            PAGE_COLORS_ON -> true
            PAGE_COLORS_OFF -> false
            else -> return null
        }

        return runCatching {
            TypographyPreset(family, fontSizePoints, lineHeight, marginEm, textAlign, paragraphIndentEm, pageColors)
        }.getOrNull()
    }

    private fun encodeFamily(family: ReflowFontFamily): String = when (family) {
        ReflowFontFamily.PUBLISHER -> FAMILY_PUBLISHER
        ReflowFontFamily.SERIF -> FAMILY_SERIF
        ReflowFontFamily.SANS -> FAMILY_SANS
        ReflowFontFamily.MONOSPACE -> FAMILY_MONOSPACE
    }

    private fun decodeFamily(value: String): ReflowFontFamily? = when (value) {
        FAMILY_PUBLISHER -> ReflowFontFamily.PUBLISHER
        FAMILY_SERIF -> ReflowFontFamily.SERIF
        FAMILY_SANS -> ReflowFontFamily.SANS
        FAMILY_MONOSPACE -> ReflowFontFamily.MONOSPACE
        else -> null
    }

    private fun encodeAlign(align: ReflowTextAlign): String = when (align) {
        ReflowTextAlign.PUBLISHER -> ALIGN_PUBLISHER
        ReflowTextAlign.LEFT -> ALIGN_LEFT
        ReflowTextAlign.JUSTIFY -> ALIGN_JUSTIFY
    }

    private fun decodeAlign(value: String): ReflowTextAlign? = when (value) {
        ALIGN_PUBLISHER -> ReflowTextAlign.PUBLISHER
        ALIGN_LEFT -> ReflowTextAlign.LEFT
        ALIGN_JUSTIFY -> ReflowTextAlign.JUSTIFY
        else -> null
    }

    /** Same formatting [ReflowStyleSheet] uses: [Locale.ROOT], trailing zeros trimmed. */
    private fun formatFloat(value: Float): String {
        val formatted = String.format(Locale.ROOT, "%.6f", value).trimEnd('0').trimEnd('.')
        return formatted.ifEmpty { "0" }
    }
}
