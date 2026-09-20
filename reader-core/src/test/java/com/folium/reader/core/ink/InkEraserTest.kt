package com.folium.reader.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkEraserTest {

    private fun strokeId(seed: Int) = StrokeId("11111111-1111-1111-1111-11111111111$seed")

    private fun strokeAlongPoints(
        id: StrokeId,
        points: List<Pair<Float, Float>>,
        widthSheetUnits: Float = 0.01f,
        tool: InkTool = InkTool.PEN
    ): InkStroke =
        InkStroke(
            id, tool, InkTip.BALLPOINT, colorArgb = 0,
            widthSheetUnits = widthSheetUnits, inputKind = InkInputKind.STYLUS,
            samples = points.mapIndexed { index, (x, y) -> InkSample(x, y, index * 10) },
            sequence = 0
        )

    @Test
    fun anEraserCrossingAStrokeHitsIt() {
        val stroke = strokeAlongPoints(strokeId(1), listOf(0f to 0f, 1f to 1f))
        val eraserPath = listOf(SheetPoint(0f, 1f), SheetPoint(1f, 0f))
        assertEquals(setOf(stroke.id), strokesHitBy(eraserPath, eraserRadius = 0.01f, strokes = listOf(stroke)))
    }

    @Test
    fun anEraserJustOutsideTheRadiusMissesTheStroke() {
        val stroke = strokeAlongPoints(strokeId(2), listOf(0f to 0f, 0f to 1f), widthSheetUnits = 0.01f)
        val eraserPath = listOf(SheetPoint(0.2f, 0.5f), SheetPoint(0.2f, 0.6f))
        assertTrue(strokesHitBy(eraserPath, eraserRadius = 0.05f, strokes = listOf(stroke)).isEmpty())
    }

    @Test
    fun anEraserJustInsideTheRadiusHitsTheStroke() {
        val stroke = strokeAlongPoints(strokeId(3), listOf(0f to 0f, 0f to 1f), widthSheetUnits = 0.01f)
        val eraserPath = listOf(SheetPoint(0.05f, 0.5f), SheetPoint(0.05f, 0.6f))
        assertEquals(setOf(stroke.id), strokesHitBy(eraserPath, eraserRadius = 0.05f, strokes = listOf(stroke)))
    }

    @Test
    fun aDotStrokeCanBeHitByADraggedEraser() {
        val dot = strokeAlongPoints(strokeId(4), listOf(0.5f to 0.5f), widthSheetUnits = 0.02f)
        val eraserPath = listOf(SheetPoint(0f, 0.5f), SheetPoint(1f, 0.5f))
        assertEquals(setOf(dot.id), strokesHitBy(eraserPath, eraserRadius = 0.02f, strokes = listOf(dot)))
    }

    @Test
    fun aTapEraserCanHitADotStroke() {
        val dot = strokeAlongPoints(strokeId(5), listOf(0.5f to 0.5f), widthSheetUnits = 0.02f)
        val eraserPath = listOf(SheetPoint(0.505f, 0.505f))
        assertEquals(setOf(dot.id), strokesHitBy(eraserPath, eraserRadius = 0.01f, strokes = listOf(dot)))
    }

    @Test
    fun aFarStrokeIsSkippedByTheBoundsPreFilter() {
        val near = strokeAlongPoints(strokeId(6), listOf(0f to 0f, 0.1f to 0.1f))
        val far = strokeAlongPoints(strokeId(7), listOf(50f to 50f, 51f to 51f))
        val eraserPath = listOf(SheetPoint(0f, 0f), SheetPoint(0.1f, 0.1f))
        assertEquals(setOf(near.id), strokesHitBy(eraserPath, eraserRadius = 0.02f, strokes = listOf(near, far)))
    }

    @Test
    fun anEraserHitsAHighlighterStrokeTheSameAsAPenStroke() {
        val stroke = strokeAlongPoints(strokeId(1), listOf(0f to 0f, 1f to 1f), tool = InkTool.HIGHLIGHTER)
        val eraserPath = listOf(SheetPoint(0f, 1f), SheetPoint(1f, 0f))
        assertEquals(setOf(stroke.id), strokesHitBy(eraserPath, eraserRadius = 0.01f, strokes = listOf(stroke)))
    }

    @Test
    fun aThickerStrokeIsEasierToHitThanAThinOneAlongTheSameLine() {
        val thin = strokeAlongPoints(strokeId(8), listOf(0f to 0f, 1f to 0f), widthSheetUnits = 0.001f)
        val thick = strokeAlongPoints(strokeId(9), listOf(0f to 0.05f, 1f to 0.05f), widthSheetUnits = 0.2f)
        val eraserPath = listOf(SheetPoint(0.5f, 0.1f), SheetPoint(0.5f, 0.11f))
        val hit = strokesHitBy(eraserPath, eraserRadius = 0.001f, strokes = listOf(thin, thick))
        assertEquals(setOf(thick.id), hit)
    }
}
