package com.folium.reader.ink

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

private const val EPSILON = 1e-4f

class EraserSettingsTest {

    @Test fun `a stepper step never overshoots either bound`() {
        assertEquals(ERASER_SIZE_MIN_MM, clampEraserSizeMm(ERASER_SIZE_MIN_MM - 1))
        assertEquals(ERASER_SIZE_MAX_MM, clampEraserSizeMm(ERASER_SIZE_MAX_MM + 1))
        assertEquals(10, clampEraserSizeMm(10))
    }

    @Test fun `the value text is a whole millimetre count in the locale's own digits`() {
        assertEquals("4 mm", formatEraserSizeMm(4, Locale.US))
        assertEquals("20 mm", formatEraserSizeMm(20, Locale.US))
    }

    @Test fun `at a comfortable zoom the eraser's own size, not the pixel floor, sets the hit radius`() {
        val radius = eraserHitRadiusSheetUnits(sizeMm = 4f, viewPxPerSheetUnit = 2000f)
        assertEquals(mmToSheetUnits(2f), radius, EPSILON)
    }

    @Test fun `zoomed far out, the pixel floor keeps the eraser hittable`() {
        val radius = eraserHitRadiusSheetUnits(sizeMm = 4f, viewPxPerSheetUnit = 10f)
        assertEquals(ERASER_HIT_MIN_RADIUS_VIEW_PX / 10f, radius, EPSILON)
    }
}
