package com.folium.reader.ink

import com.folium.reader.core.ink.InkInputKind
import com.folium.reader.core.ink.SheetPoint
import org.junit.Assert.assertEquals
import org.junit.Test

private const val EPSILON = 1e-4f

class StrokeSpaceTest {

    @Test
    fun sheetToStrokeSpaceScalesByUnitsPerSheetUnit() {
        assertEquals(1000f, StrokeSpace.sheetToStrokeSpace(1f), EPSILON)
        assertEquals(4f, StrokeSpace.sheetToStrokeSpace(0.004f), EPSILON)
    }

    @Test
    fun strokeSpaceToSheetIsTheInverseOfSheetToStrokeSpace() {
        val sheetUnits = 0.1234f

        val roundTripped = StrokeSpace.strokeSpaceToSheet(StrokeSpace.sheetToStrokeSpace(sheetUnits))

        assertEquals(sheetUnits, roundTripped, EPSILON)
    }

    @Test
    fun pointConversionScalesBothAxesIndependently() {
        val point = SheetPoint(0.5f, 3.25f)

        val strokeSpacePoint = StrokeSpace.sheetToStrokeSpace(point)

        assertEquals(500f, strokeSpacePoint.x, EPSILON)
        assertEquals(3250f, strokeSpacePoint.y, EPSILON)
        assertEquals(point, StrokeSpace.strokeSpaceToSheet(strokeSpacePoint))
    }

    @Test
    fun motionEventToolTypeMapsToTheMatchingInputKind() {
        assertEquals(InkInputKind.UNKNOWN, inkInputKindOfMotionEventToolType(0))
        assertEquals(InkInputKind.FINGER, inkInputKindOfMotionEventToolType(1))
        assertEquals(InkInputKind.STYLUS, inkInputKindOfMotionEventToolType(2))
        assertEquals(InkInputKind.MOUSE, inkInputKindOfMotionEventToolType(3))
        assertEquals(InkInputKind.UNKNOWN, inkInputKindOfMotionEventToolType(4))
        assertEquals(InkInputKind.UNKNOWN, inkInputKindOfMotionEventToolType(99))
    }
}
