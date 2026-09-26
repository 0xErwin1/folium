package com.folium.reader.ink

import org.junit.Assert.assertEquals
import org.junit.Test

private const val MM_PER_POINT = 25.4f / 72f

class TextSizeScaleTest {

    private fun textSizeUnits(mode: InkSurfaceMode, sizePt: Float): Float =
        StrokeSpace.strokeSpaceToSheet(sizePt * mode.textDesignPxPerPoint)

    @Test fun `12pt text on an A4 page spans 4_233mm of the page's own printed width`() {
        val a4 = InkSurfaceMode.Page(pageWidthPt = 595.28f, pageHeightPt = 841.89f)

        assertEquals(a4.mmToUnits(4.2333f), textSizeUnits(a4, 12f), 1e-5f)
        assertEquals(a4.mmToUnits(12f * MM_PER_POINT), textSizeUnits(a4, 12f), 1e-6f)
    }

    @Test fun `the same point size spans proportionally more of a narrower page`() {
        val a4 = InkSurfaceMode.Page(pageWidthPt = 595.28f, pageHeightPt = 841.89f)
        val pocket = InkSurfaceMode.Page(pageWidthPt = 360f, pageHeightPt = 576f)

        assertEquals(pocket.mmToUnits(12f * MM_PER_POINT), textSizeUnits(pocket, 12f), 1e-6f)
        assertEquals(595.28f / 360f, textSizeUnits(pocket, 12f) / textSizeUnits(a4, 12f), 1e-4f)
    }

    @Test fun `a sheet keeps one point per design pixel`() {
        assertEquals(1f, InkSurfaceMode.Sheet.textDesignPxPerPoint, 0f)
    }
}
