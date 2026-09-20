package com.folium.reader.ink

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class HighlighterSettingsTest {

    @Test fun `a stepper step never overshoots either bound`() {
        assertEquals(HIGHLIGHTER_WIDTH_MIN_MM, clampHighlighterWidthMm(HIGHLIGHTER_WIDTH_MIN_MM - 1))
        assertEquals(HIGHLIGHTER_WIDTH_MAX_MM, clampHighlighterWidthMm(HIGHLIGHTER_WIDTH_MAX_MM + 1))
        assertEquals(10, clampHighlighterWidthMm(10))
    }

    @Test fun `the value text is a whole millimetre count in the locale's own digits`() {
        assertEquals("8 mm", formatHighlighterWidthMm(8, Locale.US))
        assertEquals("20 mm", formatHighlighterWidthMm(20, Locale.US))
    }

    @Test fun `every colour choice on offer resolves to its own fixed, opaque hex`() {
        assertEquals(HighlighterColors.YELLOW_ARGB, HighlighterColorChoice.YELLOW.storedArgb)
        assertEquals(HighlighterColors.GREEN_ARGB, HighlighterColorChoice.GREEN.storedArgb)
        assertEquals(HighlighterColors.PINK_ARGB, HighlighterColorChoice.PINK.storedArgb)
        assertEquals(HighlighterColors.BLUE_ARGB, HighlighterColorChoice.BLUE.storedArgb)
        assertEquals(HighlighterColors.GREY_ARGB, HighlighterColorChoice.GREY.storedArgb)
    }

    @Test fun `every stored colour is fully opaque`() {
        for (choice in HighlighterColorChoice.entries) {
            assertEquals(0xFF, (choice.storedArgb ushr 24) and 0xFF)
        }
    }

    @Test fun `the brush colour carries the alpha wash over the stored opaque colour`() {
        val washed = highlighterBrushColor(HighlighterColors.YELLOW_ARGB)
        assertEquals((HIGHLIGHTER_ALPHA * 255f).toInt(), (washed ushr 24) and 0xFF)
        assertEquals(HighlighterColors.YELLOW_ARGB and 0x00FFFFFF, washed and 0x00FFFFFF)
    }

    @Test fun `a colour appearance offers every colour`() {
        assertEquals(HighlighterColorChoice.entries, highlightColourOptions(eInk = false))
    }

    @Test fun `a monochrome appearance offers only grey`() {
        assertEquals(listOf(HighlighterColorChoice.GREY), highlightColourOptions(eInk = true))
    }

    @Test fun `a colour appearance shows a stored choice exactly as chosen`() {
        assertEquals(HighlighterColorChoice.PINK, effectiveHighlightColour(HighlighterColorChoice.PINK, eInk = false))
    }

    @Test fun `a monochrome appearance shows a stored non-grey choice as grey without overwriting it`() {
        assertEquals(HighlighterColorChoice.GREY, effectiveHighlightColour(HighlighterColorChoice.YELLOW, eInk = true))
    }
}
