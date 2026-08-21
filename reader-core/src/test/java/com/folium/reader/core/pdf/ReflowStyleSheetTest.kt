package com.folium.reader.core.pdf

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReflowStyleSheetTest {

    private val defaultLocale = Locale.getDefault()

    @After fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    private fun preset(
        fontFamily: ReflowFontFamily = ReflowFontFamily.PUBLISHER,
        fontSizePoints: Float = 18f,
        lineHeight: Float? = null,
        marginEm: Float = 0f,
        textAlign: ReflowTextAlign = ReflowTextAlign.PUBLISHER,
        paragraphIndentEm: Float? = null,
        pageColors: Boolean = false
    ): TypographyPreset = TypographyPreset(
        fontFamily, fontSizePoints, lineHeight, marginEm, textAlign, paragraphIndentEm, pageColors
    )

    private val colors = ReflowPageColors(foregroundHex = "111111", backgroundHex = "eeeeee", accentHex = "3366cc")

    @Test fun defaultPresetProducesAnEmptySheetAndTheFrozenBox() {
        assertEquals("", ReflowStyleSheet.build(TypographyPreset.DEFAULT, null))
        assertEquals(ReflowLayoutBox.BOX_1, ReflowStyleSheet.boxFor(TypographyPreset.DEFAULT))
    }

    @Test fun fontFamilyEmitsExactlyItsDeclarationAndPublisherEmitsNothing() {
        assertEquals(
            "body { font-family: serif !important; }",
            ReflowStyleSheet.build(preset(fontFamily = ReflowFontFamily.SERIF), null)
        )
        assertEquals(
            "body { font-family: sans-serif !important; }",
            ReflowStyleSheet.build(preset(fontFamily = ReflowFontFamily.SANS), null)
        )
        assertEquals(
            "body { font-family: monospace !important; }",
            ReflowStyleSheet.build(preset(fontFamily = ReflowFontFamily.MONOSPACE), null)
        )
        assertEquals("", ReflowStyleSheet.build(preset(fontFamily = ReflowFontFamily.PUBLISHER), null))
    }

    @Test fun lineHeightEmitsExactlyItsDeclarationAndNullEmitsNothing() {
        assertEquals(
            "body { line-height: 1.5 !important; }",
            ReflowStyleSheet.build(preset(lineHeight = 1.5f), null)
        )
        assertEquals("", ReflowStyleSheet.build(preset(lineHeight = null), null))
    }

    @Test fun marginEmitsExactlyItsDeclarationAndZeroEmitsNothing() {
        assertEquals(
            "body { margin: 1.5em !important; }",
            ReflowStyleSheet.build(preset(marginEm = 1.5f), null)
        )
        assertEquals("", ReflowStyleSheet.build(preset(marginEm = 0f), null))
    }

    @Test fun colorsEmitBodyForegroundAndBackgroundAndNullEmitsNothingForThem() {
        val sheet = ReflowStyleSheet.build(preset(), colors)
        assertEquals(
            "body { color: #111111 !important; background-color: #eeeeee !important; }",
            sheet.lines().first { it.startsWith("body {") }
        )
        assertEquals("", ReflowStyleSheet.build(preset(), null))
    }

    @Test fun textAlignEmitsOnTheBroadSelectorAndPublisherEmitsNothing() {
        assertEquals(
            "body, p, div, li, td, blockquote { text-align: left !important; }",
            ReflowStyleSheet.build(preset(textAlign = ReflowTextAlign.LEFT), null)
        )
        assertEquals(
            "body, p, div, li, td, blockquote { text-align: justify !important; }",
            ReflowStyleSheet.build(preset(textAlign = ReflowTextAlign.JUSTIFY), null)
        )
        assertEquals("", ReflowStyleSheet.build(preset(textAlign = ReflowTextAlign.PUBLISHER), null))
    }

    @Test fun paragraphIndentEmitsExactlyItsDeclarationAndNullEmitsNothing() {
        assertEquals(
            "p { text-indent: 2em !important; }",
            ReflowStyleSheet.build(preset(paragraphIndentEm = 2f), null)
        )
        assertEquals("", ReflowStyleSheet.build(preset(paragraphIndentEm = null), null))
    }

    @Test fun accentColorEmitsOnLinksOnlyWhenColorsArePresent() {
        assertEquals(
            "a, a:link, a:visited { color: #3366cc !important; }",
            ReflowStyleSheet.build(preset(), colors).lines().first { it.startsWith("a,") }
        )
        assertFalse(ReflowStyleSheet.build(preset(), null).contains("a, a:link"))
    }

    @Test fun imageBackgroundIsForcedTransparentOnlyWhenColorsArePresent() {
        assertEquals(
            "img, svg, image { background-color: transparent !important; }",
            ReflowStyleSheet.build(preset(), colors).lines().first { it.startsWith("img,") }
        )
        assertFalse(ReflowStyleSheet.build(preset(), null).contains("img, svg, image"))
    }

    @Test fun localeIndependenceHoldsForTurkishAndGerman() {
        val comprehensive = preset(
            fontFamily = ReflowFontFamily.SERIF,
            fontSizePoints = 21.5f,
            lineHeight = 1.35f,
            marginEm = 1.25f,
            textAlign = ReflowTextAlign.JUSTIFY,
            paragraphIndentEm = 1.5f,
            pageColors = true
        )
        val reference = ReflowStyleSheet.build(comprehensive, colors)

        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        assertEquals(reference, ReflowStyleSheet.build(comprehensive, colors))

        Locale.setDefault(Locale.GERMANY)
        assertEquals(reference, ReflowStyleSheet.build(comprehensive, colors))
    }

    @Test fun noPresetCanProduceAHyphensDeclaration() {
        val comprehensive = preset(
            fontFamily = ReflowFontFamily.MONOSPACE,
            fontSizePoints = 30f,
            lineHeight = 2f,
            marginEm = 3.5f,
            textAlign = ReflowTextAlign.JUSTIFY,
            paragraphIndentEm = 2.5f,
            pageColors = true
        )
        assertFalse(ReflowStyleSheet.build(comprehensive, colors).contains("hyphens"))
    }

    @Test fun layoutVersionIsNullForTheDefaultPairAndStableOtherwise() {
        assertNull(ReflowStyleSheet.layoutVersion(ReflowLayoutBox.BOX_1, ""))

        val box = ReflowStyleSheet.boxFor(preset(fontSizePoints = 20f))
        val css = ReflowStyleSheet.build(preset(fontSizePoints = 20f, lineHeight = 1.4f), null)
        val version = ReflowStyleSheet.layoutVersion(box, css)
        assertNotNull(version)
        assertEquals(16, version!!.length)
        assertEquals(version, ReflowStyleSheet.layoutVersion(box, css))
    }

    @Test fun layoutVersionDiffersWhenOnlyTheEmDiffers() {
        val css = ReflowStyleSheet.build(preset(lineHeight = 1.4f), null)
        val smallerBox = ReflowStyleSheet.boxFor(preset(fontSizePoints = 16f, lineHeight = 1.4f))
        val largerBox = ReflowStyleSheet.boxFor(preset(fontSizePoints = 24f, lineHeight = 1.4f))
        assertNotEquals(
            ReflowStyleSheet.layoutVersion(smallerBox, css),
            ReflowStyleSheet.layoutVersion(largerBox, css)
        )
    }
}
