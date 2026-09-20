package com.folium.reader.ink

import com.folium.reader.core.ink.InkTip
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

private const val EPSILON = 1e-4f

class PenSettingsTest {

    @Test fun `a sheet's nominal 210mm width converts a pen width in millimetres to sheet units and back`() {
        assertEquals(1f, mmToSheetUnits(210f), EPSILON)
        assertEquals(0.5f / 210f, mmToSheetUnits(0.5f), EPSILON)
        assertEquals(0.5f, sheetUnitsToMm(mmToSheetUnits(0.5f)), EPSILON)
    }

    @Test fun `a stepper step never overshoots either bound`() {
        assertEquals(PEN_WIDTH_MIN_TENTHS_MM, clampPenWidthTenthsMm(PEN_WIDTH_MIN_TENTHS_MM - 1))
        assertEquals(PEN_WIDTH_MAX_TENTHS_MM, clampPenWidthTenthsMm(PEN_WIDTH_MAX_TENTHS_MM + 1))
        assertEquals(15, clampPenWidthTenthsMm(15))
    }

    @Test fun `the value text carries one decimal, the locale's own separator, and the mm unit`() {
        assertEquals("0.5 mm", formatPenWidthMm(5, Locale.US))
        assertEquals("3.0 mm", formatPenWidthMm(30, Locale.US))
        assertEquals("0,5 mm", formatPenWidthMm(5, Locale.GERMANY))
    }

    @Test fun `a colour choice resolves to its own fixed hex, except THEME which takes the caller's own ink`() {
        assertEquals(0x11223344, PenColorChoice.THEME.resolveArgb(0x11223344))
        assertEquals(PenColors.RED_ARGB, PenColorChoice.RED.resolveArgb(0x11223344))
        assertEquals(PenColors.BLUE_ARGB, PenColorChoice.BLUE.resolveArgb(0x11223344))
        assertEquals(PenColors.GREEN_ARGB, PenColorChoice.GREEN.resolveArgb(0x11223344))
    }

    @Test fun `a colour choice stores the theme sentinel for THEME and its own fixed hex otherwise`() {
        assertEquals(STROKE_THEME_INK_SENTINEL_ARGB, PenColorChoice.THEME.storedArgb())
        assertEquals(PenColors.RED_ARGB, PenColorChoice.RED.storedArgb())
        assertEquals(PenColors.BLUE_ARGB, PenColorChoice.BLUE.storedArgb())
        assertEquals(PenColors.GREEN_ARGB, PenColorChoice.GREEN.storedArgb())
    }

    @Test fun `default settings are a ballpoint at the default width in the theme's own ink, with the default eraser size`() {
        assertEquals(InkTip.BALLPOINT, PenSettings.DEFAULT.tip)
        assertEquals(PEN_WIDTH_DEFAULT_TENTHS_MM, PenSettings.DEFAULT.widthTenthsMm)
        assertEquals(PenColorChoice.THEME, PenSettings.DEFAULT.colorChoice)
        assertEquals(ERASER_SIZE_DEFAULT_MM, PenSettings.DEFAULT.eraserSizeMm)
    }

    @Test fun `every stored setting round-trips through encode and decode`() {
        val settings = PenSettings(InkTip.FOUNTAIN, 12, PenColorChoice.BLUE, 9)
        assertEquals(settings, PenSettingsCodec.decode(PenSettingsCodec.encode(settings)))
    }

    @Test fun `a missing version marker decodes as the default settings`() {
        assertEquals(PenSettings.DEFAULT, PenSettingsCodec.decode(emptyList()))
        assertEquals(PenSettings.DEFAULT, PenSettingsCodec.decode(listOf("folium-pen 0", "FOUNTAIN", "12", "BLUE", "9")))
    }

    @Test fun `a corrupt field falls back to the default for that field only`() {
        val decoded = PenSettingsCodec.decode(
            listOf(PenSettingsCodec.VERSION_MARKER, "NOT_A_TIP", "not-a-number", "NOT_A_COLOUR", "not-a-number")
        )
        assertEquals(PenSettings.DEFAULT, decoded)
    }

    @Test fun `an out-of-range stored width clamps rather than being rejected outright`() {
        val decoded = PenSettingsCodec.decode(listOf(PenSettingsCodec.VERSION_MARKER, "BALLPOINT", "999", "THEME", "4"))
        assertEquals(PEN_WIDTH_MAX_TENTHS_MM, decoded.widthTenthsMm)
    }

    @Test fun `content written before the eraser panel existed still decodes, with the default eraser size`() {
        val decoded = PenSettingsCodec.decode(listOf(PenSettingsCodec.VERSION_MARKER, "FOUNTAIN", "12", "BLUE"))
        assertEquals(PenSettings(InkTip.FOUNTAIN, 12, PenColorChoice.BLUE, ERASER_SIZE_DEFAULT_MM), decoded)
    }

    @Test fun `an out-of-range stored eraser size clamps rather than being rejected outright`() {
        val decoded = PenSettingsCodec.decode(listOf(PenSettingsCodec.VERSION_MARKER, "BALLPOINT", "5", "THEME", "999"))
        assertEquals(ERASER_SIZE_MAX_MM, decoded.eraserSizeMm)
    }
}
