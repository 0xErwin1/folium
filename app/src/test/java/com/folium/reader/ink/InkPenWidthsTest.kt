package com.folium.reader.ink

import org.junit.Assert.assertEquals
import org.junit.Test

private const val EPSILON = 1e-4f

class InkPenWidthsTest {

    @Test
    fun eachWidthReproducesItsReferencePixelWidthInStrokeSpace() {
        assertEquals(2f, StrokeSpace.sheetToStrokeSpace(InkPenWidths.THIN_SHEET_UNITS), EPSILON)
        assertEquals(4f, StrokeSpace.sheetToStrokeSpace(InkPenWidths.MEDIUM_SHEET_UNITS), EPSILON)
        assertEquals(7f, StrokeSpace.sheetToStrokeSpace(InkPenWidths.THICK_SHEET_UNITS), EPSILON)
    }

    @Test
    fun widthsAreOrderedThinToThick() {
        assertEquals(true, InkPenWidths.THIN_SHEET_UNITS < InkPenWidths.MEDIUM_SHEET_UNITS)
        assertEquals(true, InkPenWidths.MEDIUM_SHEET_UNITS < InkPenWidths.THICK_SHEET_UNITS)
    }
}
