package com.folium.reader.core.ink

import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EPSILON = 1e-4f
private const val WIDTH = 0.01f

class InkShapesTest {

    @Test fun `a line is one polyline through exactly its two endpoints`() {
        val start = SheetPoint(0f, 0f)
        val end = SheetPoint(0.5f, 0.2f)

        val polylines = shapeSamples(InkShape.LINE, start, end, WIDTH)

        assertEquals(listOf(listOf(start, end)), polylines)
    }

    @Test fun `a box is four straight sides that meet at its corners`() {
        val start = SheetPoint(0.1f, 0.1f)
        val end = SheetPoint(0.4f, 0.3f)

        val sides = shapeSamples(InkShape.BOX, start, end, WIDTH)

        assertEquals(4, sides.size)
        assertTrue(sides.all { it.size == 2 })
        assertEquals(sides.first().first(), sides.last().last())
        sides.zipWithNext { side, next -> assertEquals(side.last(), next.first()) }

        val corners = sides.map { it.first() }.toSet()
        assertEquals(setOf(start, end, SheetPoint(end.x, start.y), SheetPoint(start.x, end.y)), corners)
    }

    @Test fun `a box drawn in any direction still has the same four corners`() {
        val start = SheetPoint(0.4f, 0.3f)
        val end = SheetPoint(0.1f, 0.1f)

        val corners = shapeSamples(InkShape.BOX, start, end, WIDTH).map { it.first() }.toSet()

        assertEquals(setOf(start, end, SheetPoint(end.x, start.y), SheetPoint(start.x, end.y)), corners)
    }

    @Test fun `an arrow is a shaft and two head wings as separate polylines ending at the tip`() {
        val start = SheetPoint(0f, 0f)
        val end = SheetPoint(0.2f, 0f)

        val polylines = shapeSamples(InkShape.ARROW, start, end, WIDTH)

        assertEquals(3, polylines.size)
        assertEquals(listOf(start, end), polylines[0])
        assertEquals(end, polylines[1].last())
        assertEquals(end, polylines[2].last())
    }

    @Test fun `the arrow head's own length is clamped to a legible range regardless of the pen width`() {
        val start = SheetPoint(0f, 0f)
        val end = SheetPoint(1f, 0f)

        val thinHeadLength = headLengthOf(shapeSamples(InkShape.ARROW, start, end, widthSheetUnits = 0.0001f), end)
        val thickHeadLength = headLengthOf(shapeSamples(InkShape.ARROW, start, end, widthSheetUnits = 0.5f), end)

        assertEquals(0.012f, thinHeadLength, EPSILON)
        assertEquals(0.04f, thickHeadLength, EPSILON)
    }

    @Test fun `each arrow head wing opens at the design's own 25 degree half-angle from the shaft`() {
        val start = SheetPoint(0f, 0f)
        val end = SheetPoint(0.3f, 0.1f)
        val shaftAngle = atan2(end.y - start.y, end.x - start.x)

        val polylines = shapeSamples(InkShape.ARROW, start, end, WIDTH)
        val leftWing = polylines[1].first()
        val rightWing = polylines[2].first()

        val leftAngle = atan2(end.y - leftWing.y, end.x - leftWing.x)
        val rightAngle = atan2(end.y - rightWing.y, end.x - rightWing.x)

        val expectedHalfAngle = 25f * Math.PI.toFloat() / 180f
        assertEquals(expectedHalfAngle, angleBetween(shaftAngle, leftAngle), EPSILON)
        assertEquals(expectedHalfAngle, angleBetween(shaftAngle, rightAngle), EPSILON)
    }

    @Test fun `an ellipse is one closed polyline whose every point satisfies the ellipse equation`() {
        val start = SheetPoint(0f, 0f)
        val end = SheetPoint(0.4f, 0.2f)
        val centerX = 0.2f
        val centerY = 0.1f
        val radiusX = 0.2f
        val radiusY = 0.1f

        val polylines = shapeSamples(InkShape.ELLIPSE, start, end, WIDTH)

        assertEquals(1, polylines.size)
        val points = polylines[0]
        assertEquals(points.first(), points.last())

        for (point in points) {
            val dx = (point.x - centerX) / radiusX
            val dy = (point.y - centerY) / radiusY
            assertEquals(1f, dx * dx + dy * dy, EPSILON)
        }
    }

    @Test fun `an ellipse's own segment count stays within the design's own bounds`() {
        val tinyEllipse = shapeSamples(InkShape.ELLIPSE, SheetPoint(0f, 0f), SheetPoint(0.02f, 0.02f), WIDTH)[0]
        val hugeEllipse = shapeSamples(InkShape.ELLIPSE, SheetPoint(0f, 0f), SheetPoint(20f, 20f), WIDTH)[0]

        assertTrue(tinyEllipse.size - 1 in 24..180)
        assertTrue(hugeEllipse.size - 1 in 24..180)
    }

    @Test fun `every shape yields nothing for a drag shorter than the stroke width on both axes`() {
        val start = SheetPoint(0.5f, 0.5f)
        val end = SheetPoint(0.5f + WIDTH / 2f, 0.5f + WIDTH / 2f)

        for (shape in InkShape.entries) {
            assertTrue(shapeSamples(shape, start, end, WIDTH).isEmpty())
        }
    }

    @Test fun `a zero-length drag yields nothing for every shape`() {
        val point = SheetPoint(0.3f, 0.3f)

        for (shape in InkShape.entries) {
            assertTrue(shapeSamples(shape, point, point, WIDTH).isEmpty())
        }
    }

    @Test fun `a drag wide enough on only one axis is not degenerate`() {
        val start = SheetPoint(0f, 0f)
        val end = SheetPoint(0.5f, 0f)

        assertTrue(shapeSamples(InkShape.LINE, start, end, WIDTH).isNotEmpty())
    }

    private fun headLengthOf(polylines: List<List<SheetPoint>>, tip: SheetPoint): Float {
        val wing = polylines[1].first()
        return hypot((tip.x - wing.x).toDouble(), (tip.y - wing.y).toDouble()).toFloat()
    }

    private fun angleBetween(a: Float, b: Float): Float {
        var diff = a - b
        while (diff > Math.PI) diff -= (2 * Math.PI).toFloat()
        while (diff < -Math.PI) diff += (2 * Math.PI).toFloat()
        return kotlin.math.abs(diff)
    }

    @Test fun `shape samples are timed as a slow stroke, never as a flick`() {
        val polyline = listOf(SheetPoint(0f, 0f), SheetPoint(0.25f, 0f), SheetPoint(0.25f, 0.0001f))

        val times = shapeSampleTimesMillis(polyline)

        assertEquals(listOf(0, 1000, 1008), times)
    }

    @Test fun `an ellipse's sample times strictly increase`() {
        val ellipse = shapeSamples(InkShape.ELLIPSE, SheetPoint(0.1f, 0.1f), SheetPoint(0.6f, 0.4f), WIDTH)[0]

        val times = shapeSampleTimesMillis(ellipse)

        assertTrue(times.zipWithNext().all { (earlier, later) -> later > earlier })
    }
}
