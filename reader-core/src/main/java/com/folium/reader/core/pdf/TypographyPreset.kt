package com.folium.reader.core.pdf

enum class ReflowFontFamily { PUBLISHER, SERIF, SANS, MONOSPACE }
enum class ReflowTextAlign { PUBLISHER, LEFT, JUSTIFY }

/**
 * A reader's chosen typography for a reflowable document, independent of any one document's own
 * styling. `PUBLISHER` on [fontFamily] or [textAlign], and `null` on [lineHeight] or
 * [paragraphIndentEm], mean the document's own choice is left in place rather than overridden.
 */
data class TypographyPreset(
    val fontFamily: ReflowFontFamily,
    val fontSizePoints: Float,
    val lineHeight: Float?,
    val marginEm: Float,
    val textAlign: ReflowTextAlign,
    val paragraphIndentEm: Float?,
    val pageColors: Boolean
) {
    init {
        require(fontSizePoints in 12f..32f) { "fontSizePoints must be in 12f..32f, was $fontSizePoints" }
        require(marginEm in 0f..4f) { "marginEm must be in 0f..4f, was $marginEm" }
    }

    companion object {
        val DEFAULT = TypographyPreset(
            fontFamily = ReflowFontFamily.PUBLISHER,
            fontSizePoints = 18f,
            lineHeight = null,
            marginEm = 0f,
            textAlign = ReflowTextAlign.PUBLISHER,
            paragraphIndentEm = null,
            pageColors = false
        )
    }
}

/** Foreground, background and accent for one appearance mode, each a six-digit hex color. */
data class ReflowPageColors(val foregroundHex: String, val backgroundHex: String, val accentHex: String) {
    init {
        require(isHexColor(foregroundHex)) { "foregroundHex must be six hex digits, was $foregroundHex" }
        require(isHexColor(backgroundHex)) { "backgroundHex must be six hex digits, was $backgroundHex" }
        require(isHexColor(accentHex)) { "accentHex must be six hex digits, was $accentHex" }
    }

    private companion object {
        val HEX_COLOR_REGEX = Regex("[0-9a-fA-F]{6}")
        fun isHexColor(value: String) = HEX_COLOR_REGEX.matches(value)
    }
}
