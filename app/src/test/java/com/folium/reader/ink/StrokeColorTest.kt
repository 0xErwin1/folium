package com.folium.reader.ink

import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeColorTest {

    @Test fun `opaque black resolves to the theme's own ink`() {
        assertEquals(0xFF101010.toInt(), resolveStrokeColor(0xFF000000.toInt(), 0xFF101010.toInt()))
    }

    @Test fun `near-black is stored colour, not the theme's sentinel`() {
        assertEquals(0xFF000001.toInt(), resolveStrokeColor(0xFF000001.toInt(), 0xFF101010.toInt()))
    }

    @Test fun `transparent black is stored colour, not the opaque sentinel`() {
        assertEquals(0x00000000, resolveStrokeColor(0x00000000, 0xFF101010.toInt()))
    }

    @Test fun `a fixed colour such as red renders exactly as stored`() {
        assertEquals(PenColors.RED_ARGB, resolveStrokeColor(PenColors.RED_ARGB, 0xFF101010.toInt()))
    }

    @Test fun `the sentinel constant is exactly opaque black`() {
        assertEquals(0xFF000000.toInt(), STROKE_THEME_INK_SENTINEL_ARGB)
    }
}
