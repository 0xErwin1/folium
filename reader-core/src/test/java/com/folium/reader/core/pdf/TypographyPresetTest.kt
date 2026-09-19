package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TypographyPresetTest {

    private fun preset(fontSizePoints: Float = 18f, marginEm: Float = 0f): TypographyPreset =
        TypographyPreset(
            fontFamily = ReflowFontFamily.PUBLISHER,
            fontSizePoints = fontSizePoints,
            lineHeight = null,
            marginEm = marginEm,
            textAlign = ReflowTextAlign.PUBLISHER,
            paragraphIndentEm = null,
            pageBackground = ReflowPageBackground.MATCH_APP_THEME
        )

    @Test fun fontSizeAcceptsBothBoundariesAndRejectsJustOutsideThem() {
        preset(fontSizePoints = 12f)
        preset(fontSizePoints = 32f)
        assertThrows(IllegalArgumentException::class.java) { preset(fontSizePoints = 11.999f) }
        assertThrows(IllegalArgumentException::class.java) { preset(fontSizePoints = 32.001f) }
    }

    @Test fun marginEmAcceptsBothBoundariesAndRejectsJustOutsideThem() {
        preset(marginEm = 0f)
        preset(marginEm = 4f)
        assertThrows(IllegalArgumentException::class.java) { preset(marginEm = -0.001f) }
        assertThrows(IllegalArgumentException::class.java) { preset(marginEm = 4.001f) }
    }

    @Test fun defaultIsTheOmissionCaseInEveryField() {
        assertEquals(ReflowFontFamily.PUBLISHER, TypographyPreset.DEFAULT.fontFamily)
        assertEquals(18f, TypographyPreset.DEFAULT.fontSizePoints, 0f)
        assertEquals(null, TypographyPreset.DEFAULT.lineHeight)
        assertEquals(0f, TypographyPreset.DEFAULT.marginEm, 0f)
        assertEquals(ReflowTextAlign.PUBLISHER, TypographyPreset.DEFAULT.textAlign)
        assertEquals(null, TypographyPreset.DEFAULT.paragraphIndentEm)
        assertEquals(ReflowPageBackground.MATCH_APP_THEME, TypographyPreset.DEFAULT.pageBackground)
    }
}
