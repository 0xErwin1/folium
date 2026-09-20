package com.folium.reader.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val EPSILON = 1e-4f

class ViewSelectorTest {

    @Test fun `a zoom of one reads as 100 percent`() {
        assertEquals(100, zoomPercentOf(1f))
    }

    @Test fun `a percent of one hundred is a zoom of one`() {
        assertEquals(1f, zoomFractionOf(100), EPSILON)
    }

    @Test fun `plus snaps a pinched zoom up to the next multiple of the step`() {
        assertEquals(140, nextZoomStep(137, ZoomStepDirection.INCREASE))
    }

    @Test fun `minus snaps a pinched zoom down to the next multiple of the step`() {
        assertEquals(120, nextZoomStep(137, ZoomStepDirection.DECREASE))
    }

    @Test fun `plus from an exact multiple still advances by a full step`() {
        assertEquals(160, nextZoomStep(140, ZoomStepDirection.INCREASE))
    }

    @Test fun `minus from an exact multiple still retreats by a full step`() {
        assertEquals(100, nextZoomStep(120, ZoomStepDirection.DECREASE))
    }

    @Test fun `plus is clamped at the maximum`() {
        assertEquals(ZOOM_MAX_PERCENT, nextZoomStep(ZOOM_MAX_PERCENT, ZoomStepDirection.INCREASE))
    }

    @Test fun `minus is clamped at the minimum`() {
        assertEquals(ZOOM_MIN_PERCENT, nextZoomStep(ZOOM_MIN_PERCENT, ZoomStepDirection.DECREASE))
    }

    @Test fun `actual size zoom fits the sheet's nominal width to its physical width on screen`() {
        // A 400dpi screen: 210mm is 210 / 25.4 * 400 =~ 3307px wide; a 1000px-wide view needs zoom ~3.307.
        val zoom = actualSizeZoom(xdpi = 400f, viewWidthPx = 1000f)

        assertEquals(3.3070867f, zoom, 1e-3f)
    }

    @Test fun `actual size zoom is clamped to the zoom range`() {
        assertEquals(SheetViewport.MAX_ZOOM, actualSizeZoom(xdpi = 4000f, viewWidthPx = 100f), EPSILON)
        assertEquals(SheetViewport.MIN_ZOOM, actualSizeZoom(xdpi = 10f, viewWidthPx = 1000f), EPSILON)
    }

    @Test fun `width is selected once the zoom is back within one percent of 100`() {
        assertEquals(FitToOption.WIDTH, selectedFitToOption(currentZoomPercent = 100, actualSizeZoomPercent = 331))
        assertEquals(FitToOption.WIDTH, selectedFitToOption(currentZoomPercent = 101, actualSizeZoomPercent = 331))
    }

    @Test fun `actual size is selected once the zoom is within one percent of its own target`() {
        assertEquals(FitToOption.ACTUAL_SIZE, selectedFitToOption(currentZoomPercent = 330, actualSizeZoomPercent = 331))
    }

    @Test fun `neither fit option is selected once a pinch has moved the zoom away from both targets`() {
        assertNull(selectedFitToOption(currentZoomPercent = 200, actualSizeZoomPercent = 331))
    }

    @Test fun `a track position snaps to the nearest reachable step`() {
        assertEquals(100, snapToStep(100, 800, 20, 0f))
        assertEquals(800, snapToStep(100, 800, 20, 1f))
        assertEquals(460, snapToStep(100, 800, 20, 0.51f))
        assertEquals(1, snapToStep(1, 30, 1, -0.4f))
        assertEquals(30, snapToStep(1, 30, 1, 7f))
    }
}
