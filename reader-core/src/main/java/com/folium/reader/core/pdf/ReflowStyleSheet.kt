package com.folium.reader.core.pdf

import java.security.MessageDigest
import java.util.Locale

/**
 * Turns a [TypographyPreset] into the [ReflowLayoutBox] and CSS the engine lays a reflowable
 * document out against. Every declaration was measured against the shipped engine build: each
 * carries `!important`, which was measured to be honoured, and `hyphens` is never emitted because
 * it was measured to have no effect there.
 *
 * Output is deterministic byte for byte, because [layoutVersion] fingerprints it: one selector per
 * line, declarations space-separated inside the braces, no trailing newline, and every float
 * formatted under [Locale.ROOT] with trailing zeros trimmed — a default-locale format would emit a
 * comma decimal separator on some devices, which is not valid CSS and would silently repaginate
 * only those devices.
 */
object ReflowStyleSheet {
    private const val BOX_WIDTH_POINTS = 450f
    private const val BOX_HEIGHT_POINTS = 675f
    private const val LAYOUT_VERSION_LENGTH = 16

    /** Width and height are fixed so the page shape never changes; only the em, [preset]'s font size, varies. */
    fun boxFor(preset: TypographyPreset): ReflowLayoutBox =
        ReflowLayoutBox(BOX_WIDTH_POINTS, BOX_HEIGHT_POINTS, preset.fontSizePoints)

    fun build(preset: TypographyPreset, colors: ReflowPageColors?): String {
        val rules = mutableListOf<String>()

        val bodyDeclarations = buildList {
            fontFamilyDeclaration(preset.fontFamily)?.let(::add)
            preset.lineHeight?.let { add("line-height: ${formatFloat(it)}") }
            if (preset.marginEm != 0f) add("margin: ${formatFloat(preset.marginEm)}em")
            colors?.let {
                add("color: #${it.foregroundHex.lowercase(Locale.ROOT)}")
                add("background-color: #${it.backgroundHex.lowercase(Locale.ROOT)}")
            }
        }
        addRule(rules, "body", bodyDeclarations)

        val textAlignDeclaration = textAlignDeclaration(preset.textAlign)
        if (textAlignDeclaration != null) {
            addRule(rules, "body, p, div, li, td, blockquote", listOf(textAlignDeclaration))
        }

        preset.paragraphIndentEm?.let { indent ->
            addRule(rules, "p", listOf("text-indent: ${formatFloat(indent)}em"))
        }

        colors?.let {
            addRule(rules, "a, a:link, a:visited", listOf("color: #${it.accentHex.lowercase(Locale.ROOT)}"))
            addRule(rules, "img, svg, image", listOf("background-color: transparent"))
        }

        return rules.joinToString("\n")
    }

    /**
     * Null exactly for [ReflowLayoutBox.BOX_1] paired with an empty sheet — the state every
     * unconfigured reflowable document is in. Box-inclusive because the em changes pagination
     * without changing a byte of CSS, so the box's own numbers must feed the fingerprint too.
     */
    fun layoutVersion(box: ReflowLayoutBox, css: String): String? {
        if (box == ReflowLayoutBox.BOX_1 && css.isEmpty()) return null

        val fingerprint = "${formatFloat(box.widthPoints)}|${formatFloat(box.heightPoints)}|" +
            "${formatFloat(box.emPoints)}|$css"
        val digest = MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(LAYOUT_VERSION_LENGTH)
    }

    private fun addRule(rules: MutableList<String>, selector: String, declarations: List<String>) {
        if (declarations.isEmpty()) return
        val body = declarations.joinToString(" ") { "$it !important;" }
        rules += "$selector { $body }"
    }

    private fun fontFamilyDeclaration(family: ReflowFontFamily): String? = when (family) {
        ReflowFontFamily.PUBLISHER -> null
        ReflowFontFamily.SERIF -> "font-family: serif"
        ReflowFontFamily.SANS -> "font-family: sans-serif"
        ReflowFontFamily.MONOSPACE -> "font-family: monospace"
    }

    private fun textAlignDeclaration(align: ReflowTextAlign): String? = when (align) {
        ReflowTextAlign.PUBLISHER -> null
        ReflowTextAlign.LEFT -> "text-align: left"
        ReflowTextAlign.JUSTIFY -> "text-align: justify"
    }

    private fun formatFloat(value: Float): String {
        val formatted = String.format(Locale.ROOT, "%.6f", value).trimEnd('0').trimEnd('.')
        return formatted.ifEmpty { "0" }
    }
}
