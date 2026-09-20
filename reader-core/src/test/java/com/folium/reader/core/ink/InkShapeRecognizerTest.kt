package com.folium.reader.core.ink

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EPSILON = 1e-3f

class InkShapeRecognizerTest {

    // ---- LINE ----

    @Test fun `a straight line in each of eight directions is recognised`() {
        for (directionIndex in 0 until 8) {
            val angle = directionIndex * PI.toFloat() / 4f
            val start = SheetPoint(0f, 0f)
            val end = SheetPoint(0.3f * cos(angle), 0.3f * sin(angle))

            val recognized = recognizeShape(wobbledLine(start, end, pointCount = 40, wobbleFraction = 0f, random = Random((1).toLong())))

            assertEquals("direction $directionIndex", InkShape.LINE, recognized?.shape)
        }
    }

    @Test fun `a wobbly line in each of eight directions is still recognised`() {
        for (directionIndex in 0 until 8) {
            val angle = directionIndex * PI.toFloat() / 4f
            val start = SheetPoint(0f, 0f)
            val end = SheetPoint(0.3f * cos(angle), 0.3f * sin(angle))

            val recognized = recognizeShape(
                wobbledLine(start, end, pointCount = 50, wobbleFraction = 0.03f, random = Random((100 + directionIndex).toLong()))
            )

            assertEquals("direction $directionIndex", InkShape.LINE, recognized?.shape)
        }
    }

    @Test fun `a near-horizontal line snaps exactly onto the horizontal axis`() {
        val start = SheetPoint(0f, 0f)
        val end = SheetPoint(0.3f, 0.02f)

        val recognized = recognizeShape(wobbledLine(start, end, pointCount = 40, wobbleFraction = 0f, random = Random((1).toLong())))

        assertEquals(InkShape.LINE, recognized?.shape)
        assertEquals(recognized!!.start.y, recognized.end.y, EPSILON)
    }

    @Test fun `a near-vertical line snaps exactly onto the vertical axis`() {
        val start = SheetPoint(0f, 0f)
        val end = SheetPoint(0.02f, 0.3f)

        val recognized = recognizeShape(wobbledLine(start, end, pointCount = 40, wobbleFraction = 0f, random = Random((1).toLong())))

        assertEquals(InkShape.LINE, recognized?.shape)
        assertEquals(recognized!!.start.x, recognized.end.x, EPSILON)
    }

    @Test fun `a line with a small end hook is still a line, not an arrow`() {
        val points = wobbledLine(SheetPoint(0f, 0f), SheetPoint(0.3f, 0f), pointCount = 40, wobbleFraction = 0f, random = Random((1).toLong())) +
            straightSegment(SheetPoint(0.3f, 0f), SheetPoint(0.29f, 0.01f), pointCount = 6)

        assertEquals(InkShape.LINE, recognizeShape(points)?.shape)
    }

    @Test fun `an L shape is not a line`() {
        val points = straightSegment(SheetPoint(0f, 0f), SheetPoint(0.3f, 0f), pointCount = 30) +
            straightSegment(SheetPoint(0.3f, 0f), SheetPoint(0.3f, 0.3f), pointCount = 30)

        assertNull(recognizeShape(points))
    }

    @Test fun `an S curve is not a line`() {
        val points = (0..60).map { i ->
            val t = i / 60f
            SheetPoint(0.3f * t, 0.15f * sin(t * PI.toFloat() * 2f))
        }

        assertNull(recognizeShape(points))
    }

    // ---- ARROW ----

    @Test fun `an arrow with a single wing head is recognised in each of eight directions`() {
        for (directionIndex in 0 until 8) {
            val angle = directionIndex * PI.toFloat() / 4f
            val start = SheetPoint(0f, 0f)
            val tip = SheetPoint(0.3f * cos(angle), 0.3f * sin(angle))

            val points = arrowStroke(start, tip, wingCount = 1, random = Random((200 + directionIndex).toLong()))
            val recognized = recognizeShape(points)

            assertEquals("direction $directionIndex", InkShape.ARROW, recognized?.shape)
            assertPointsClose(start, recognized!!.start)
            assertPointsClose(tip, recognized.end)
        }
    }

    @Test fun `an arrow with a two wing head drawn without lifting the pen is recognised in each of eight directions`() {
        for (directionIndex in 0 until 8) {
            val angle = directionIndex * PI.toFloat() / 4f
            val start = SheetPoint(0f, 0f)
            val tip = SheetPoint(0.3f * cos(angle), 0.3f * sin(angle))

            val points = arrowStroke(start, tip, wingCount = 2, random = Random((300 + directionIndex).toLong()))
            val recognized = recognizeShape(points)

            assertEquals("direction $directionIndex", InkShape.ARROW, recognized?.shape)
            assertPointsClose(tip, recognized!!.end)
        }
    }

    // ---- BOX ----

    @Test fun `a wobbly rectangle drawn from each corner in both directions is recognised as a box`() {
        val corners = listOf(SheetPoint(0f, 0f), SheetPoint(0.4f, 0f), SheetPoint(0.4f, 0.25f), SheetPoint(0f, 0.25f))

        for (startCornerIndex in corners.indices) {
            for (clockwise in listOf(true, false)) {
                val ordered = orderedFrom(corners, startCornerIndex, clockwise)
                val seed = (400 + startCornerIndex * 2 + if (clockwise) 0 else 1).toLong()
                val points = wobbledPolygon(ordered, pointsPerSide = 20, wobbleFraction = 0.02f, random = Random(seed))

                val recognized = recognizeShape(points)
                assertEquals("corner $startCornerIndex clockwise=$clockwise", InkShape.BOX, recognized?.shape)
            }
        }
    }

    @Test fun `a rectangle with a small unclosed gap is still a box`() {
        val corners = listOf(SheetPoint(0f, 0f), SheetPoint(0.4f, 0f), SheetPoint(0.4f, 0.25f), SheetPoint(0f, 0.25f))
        val fullOutline = wobbledPolygon(corners, pointsPerSide = 20, wobbleFraction = 0f, random = Random((1).toLong()))
        val gapCount = (fullOutline.size * 0.1f).toInt()
        val points = fullOutline.dropLast(gapCount)

        assertEquals(InkShape.BOX, recognizeShape(points)?.shape)
    }

    @Test fun `a rectangle with an overshot corner is still a box`() {
        val corners = listOf(SheetPoint(0f, 0f), SheetPoint(0.4f, 0f), SheetPoint(0.4f, 0.25f), SheetPoint(0f, 0.25f))
        val overshoot = SheetPoint(-0.02f, -0.015f)
        val points = wobbledPolygon(corners + corners[0] + overshoot, pointsPerSide = 20, wobbleFraction = 0f, random = Random((1).toLong()))

        assertEquals(InkShape.BOX, recognizeShape(points)?.shape)
    }

    @Test fun `a rectangle rotated 30 degrees is not a box`() {
        val corners = rotatedRectangleCorners(width = 0.4f, height = 0.25f, rotationRadians = 30f * PI.toFloat() / 180f)
        val points = wobbledPolygon(corners, pointsPerSide = 20, wobbleFraction = 0f, random = Random((1).toLong()))

        assertNotEquals(InkShape.BOX, recognizeShape(points)?.shape)
    }

    @Test fun `a box with sides differing by less than twelve percent snaps to an exact square`() {
        val corners = listOf(SheetPoint(0f, 0f), SheetPoint(0.4f, 0f), SheetPoint(0.4f, 0.44f), SheetPoint(0f, 0.44f))
        val points = wobbledPolygon(corners, pointsPerSide = 20, wobbleFraction = 0.02f, random = Random((9).toLong()))

        val recognized = recognizeShape(points)

        assertEquals(InkShape.BOX, recognized?.shape)
        assertEquals(recognized!!.end.x - recognized.start.x, recognized.end.y - recognized.start.y, EPSILON)
    }

    @Test fun `a box with sides differing by thirty percent stays a rectangle`() {
        val corners = listOf(SheetPoint(0f, 0f), SheetPoint(0.4f, 0f), SheetPoint(0.4f, 0.52f), SheetPoint(0f, 0.52f))
        val points = wobbledPolygon(corners, pointsPerSide = 20, wobbleFraction = 0.02f, random = Random((9).toLong()))

        val recognized = recognizeShape(points)

        assertEquals(InkShape.BOX, recognized?.shape)
        assertTrue(abs((recognized!!.end.x - recognized.start.x) - (recognized.end.y - recognized.start.y)) > EPSILON)
    }

    // ---- TRIANGLE ----

    @Test fun `a triangle in each of six rotations is recognised`() {
        for (rotationIndex in 0 until 6) {
            val rotation = rotationIndex * PI.toFloat() / 3f + 0.15f
            val corners = equilateralTriangleCorners(radius = 0.2f, rotationRadians = rotation)
            val points = wobbledPolygon(corners, pointsPerSide = 20, wobbleFraction = 0.02f, random = Random((700 + rotationIndex).toLong()))

            assertEquals("rotation $rotationIndex", InkShape.TRIANGLE, recognizeShape(points)?.shape)
        }
    }

    @Test fun `a scalene triangle is recognised and keeps its own three real corners`() {
        val corners = listOf(SheetPoint(0f, 0f), SheetPoint(0.35f, 0.05f), SheetPoint(0.1f, 0.3f))
        val points = wobbledPolygon(corners, pointsPerSide = 20, wobbleFraction = 0.01f, random = Random((1).toLong()))

        val recognized = recognizeShape(points)

        assertEquals(InkShape.TRIANGLE, recognized?.shape)
        assertEquals(3, recognized!!.vertices.size)
    }

    @Test fun `a triangle with a near-horizontal side snaps that side exactly onto the horizontal axis`() {
        val corners = listOf(SheetPoint(0f, 0.01f), SheetPoint(0.3f, 0f), SheetPoint(0.12f, 0.25f))
        val points = wobbledPolygon(corners, pointsPerSide = 20, wobbleFraction = 0f, random = Random((1).toLong()))

        val recognized = recognizeShape(points)

        assertEquals(InkShape.TRIANGLE, recognized?.shape)
        val ys = recognized!!.vertices.map { it.y }.sorted()
        assertEquals(ys[0], ys[1], EPSILON)
    }

    @Test fun `a needle-thin triangle with a sliver apex angle is not recognised as a triangle`() {
        val corners = listOf(SheetPoint(0f, 0f), SheetPoint(0.02f, 0f), SheetPoint(0.01f, 0.3f))
        val points = wobbledPolygon(corners, pointsPerSide = 20, wobbleFraction = 0f, random = Random((1).toLong()))

        assertNotEquals(InkShape.TRIANGLE, recognizeShape(points)?.shape)
    }

    @Test fun `a well-formed triangle is not mistaken for an ellipse`() {
        val corners = equilateralTriangleCorners(radius = 0.22f, rotationRadians = 0.3f)
        val points = wobbledPolygon(corners, pointsPerSide = 25, wobbleFraction = 0.015f, random = Random((2).toLong()))

        assertEquals(InkShape.TRIANGLE, recognizeShape(points)?.shape)
    }

    @Test fun `a sloppy ellipse is not mistaken for a triangle`() {
        val points = wobbledEllipse(radiusX = 0.25f, radiusY = 0.2f, openFraction = 0f, wobbleFraction = 0.04f, random = Random((3).toLong()))

        assertEquals(InkShape.ELLIPSE, recognizeShape(points)?.shape)
    }

    // ---- ELLIPSE ----

    @Test fun `circles and ellipses of several aspect ratios are recognised`() {
        val aspectRatios = listOf(1f, 0.7f, 0.5f, 0.3f)

        for ((index, aspectRatio) in aspectRatios.withIndex()) {
            val radiusX = 0.25f
            val radiusY = 0.25f * aspectRatio
            val points = wobbledEllipse(radiusX, radiusY, openFraction = 0f, wobbleFraction = 0.02f, random = Random((500 + index).toLong()))

            assertEquals("aspect ratio $aspectRatio", InkShape.ELLIPSE, recognizeShape(points)?.shape)
        }
    }

    @Test fun `an ellipse open by ten percent with overshoot is still recognised`() {
        val points = wobbledEllipse(radiusX = 0.25f, radiusY = 0.18f, openFraction = 0.1f, wobbleFraction = 0.01f, random = Random((1).toLong()))

        assertEquals(InkShape.ELLIPSE, recognizeShape(points)?.shape)
    }

    @Test fun `an ellipse with axes differing by less than twelve percent snaps to an exact circle`() {
        val points = wobbledEllipse(radiusX = 0.25f, radiusY = 0.25f * 0.95f, openFraction = 0f, wobbleFraction = 0.02f, random = Random((11).toLong()))

        val recognized = recognizeShape(points)

        assertEquals(InkShape.ELLIPSE, recognized?.shape)
        assertEquals(recognized!!.end.x - recognized.start.x, recognized.end.y - recognized.start.y, EPSILON)
    }

    @Test fun `an ellipse with axes differing by thirty percent stays an ellipse, not a circle`() {
        val points = wobbledEllipse(radiusX = 0.25f, radiusY = 0.175f, openFraction = 0f, wobbleFraction = 0.02f, random = Random((12).toLong()))

        val recognized = recognizeShape(points)

        assertEquals(InkShape.ELLIPSE, recognized?.shape)
        assertTrue(abs((recognized!!.end.x - recognized.start.x) - (recognized.end.y - recognized.start.y)) > EPSILON)
    }

    // ---- scribbles that must never resolve to a shape ----

    @Test fun `a spiral is not recognised as any shape`() {
        val points = (0..120).map { i ->
            val t = i / 120f
            val radius = 0.05f + 0.2f * t
            val angle = t * PI.toFloat() * 6f
            SheetPoint(radius * cos(angle), radius * sin(angle))
        }

        assertNull(recognizeShape(points))
    }

    @Test fun `a figure eight is not recognised as any shape`() {
        val points = (0..120).map { i ->
            val t = i / 120f * PI.toFloat() * 2f
            SheetPoint(0.25f * sin(t), 0.2f * sin(t) * cos(t))
        }

        assertNull(recognizeShape(points))
    }

    @Test fun `a zigzag is not recognised as any shape`() {
        val points = mutableListOf<SheetPoint>()
        var x = 0f
        for (i in 0 until 8) {
            val y = if (i % 2 == 0) 0f else 0.2f
            points += straightSegment(SheetPoint(x, if (i % 2 == 0) 0.2f else 0f), SheetPoint(x + 0.06f, y), pointCount = 10)
            x += 0.06f
        }

        assertNull(recognizeShape(points))
    }

    @Test fun `a cursive-like scribble with several loops is not recognised as any shape`() {
        val points = (0..150).map { i ->
            val t = i / 150f * PI.toFloat() * 5f
            SheetPoint(0.03f * t, 0.08f * sin(t) + 0.02f * sin(t * 3f))
        }

        assertNull(recognizeShape(points))
    }

    @Test fun `a tiny stroke is never a shape`() {
        val points = wobbledLine(SheetPoint(0f, 0f), SheetPoint(0.005f, 0.001f), pointCount = 10, wobbleFraction = 0f, random = Random((1).toLong()))

        assertNull(recognizeShape(points))
    }

    @Test fun `a stroke with too few samples is never a shape`() {
        val points = listOf(SheetPoint(0f, 0f), SheetPoint(0.1f, 0f), SheetPoint(0.2f, 0f))

        assertNull(recognizeShape(points))
    }

    // ---- scale invariance ----

    @Test fun `the same line shape is recognised at a tenth and ten times its size`() {
        val base = wobbledLine(SheetPoint(0f, 0f), SheetPoint(0.3f, 0.1f), pointCount = 40, wobbleFraction = 0.02f, random = Random((9).toLong()))

        assertEquals(InkShape.LINE, recognizeShape(scaled(base, 0.1f))?.shape)
        assertEquals(InkShape.LINE, recognizeShape(scaled(base, 10f))?.shape)
    }

    @Test fun `the same box shape is recognised at a tenth and ten times its size`() {
        val corners = listOf(SheetPoint(0f, 0f), SheetPoint(0.4f, 0f), SheetPoint(0.4f, 0.25f), SheetPoint(0f, 0.25f))
        val base = wobbledPolygon(corners, pointsPerSide = 20, wobbleFraction = 0.02f, random = Random((9).toLong()))

        assertEquals(InkShape.BOX, recognizeShape(scaled(base, 0.1f))?.shape)
        assertEquals(InkShape.BOX, recognizeShape(scaled(base, 10f))?.shape)
    }

    @Test fun `the same ellipse shape is recognised at a tenth and ten times its size`() {
        val base = wobbledEllipse(radiusX = 0.25f, radiusY = 0.18f, openFraction = 0f, wobbleFraction = 0.02f, random = Random((9).toLong()))

        assertEquals(InkShape.ELLIPSE, recognizeShape(scaled(base, 0.1f))?.shape)
        assertEquals(InkShape.ELLIPSE, recognizeShape(scaled(base, 10f))?.shape)
    }
}

private fun assertNotEquals(expected: InkShape, actual: InkShape?) {
    assertTrue(actual != expected)
}

private fun assertPointsClose(expected: SheetPoint, actual: SheetPoint, tolerance: Float = 0.02f) {
    assertTrue(abs(expected.x - actual.x) < tolerance)
    assertTrue(abs(expected.y - actual.y) < tolerance)
}

private fun scaled(points: List<SheetPoint>, factor: Float): List<SheetPoint> = points.map { SheetPoint(it.x * factor, it.y * factor) }

private fun straightSegment(start: SheetPoint, end: SheetPoint, pointCount: Int): List<SheetPoint> =
    (0 until pointCount).map { i ->
        val t = i / (pointCount - 1f)
        SheetPoint(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t)
    }

/**
 * A straight stroke between [start] and [end], with its interior points nudged sideways by a smooth,
 * low-frequency wave plus light jitter, up to [wobbleFraction] of its own length: a hand's own tremor,
 * not per-sample independent noise, which would zigzag the path far longer than a real stroke ever is.
 */
private fun wobbledLine(start: SheetPoint, end: SheetPoint, pointCount: Int, wobbleFraction: Float, random: Random): List<SheetPoint> {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
    val normalX = if (length > 0f) -dy / length else 0f
    val normalY = if (length > 0f) dx / length else 0f
    val frequency = 2f + random.nextFloat() * 2f
    val phase = random.nextFloat() * 2f * PI.toFloat()

    return (0 until pointCount).map { i ->
        val t = i / (pointCount - 1f)
        val baseX = start.x + dx * t
        val baseY = start.y + dy * t
        val smooth = sin(2f * PI.toFloat() * frequency * t + phase)
        val jitter = (random.nextFloat() * 2f - 1f) * 0.15f
        val wobble = if (i == 0 || i == pointCount - 1) 0f else (smooth + jitter) * wobbleFraction * length
        SheetPoint(baseX + normalX * wobble, baseY + normalY * wobble)
    }
}

/** A shaft from [start] to [tip], followed by one or two head strokes that return toward [tip] without lifting the pen. */
private fun arrowStroke(start: SheetPoint, tip: SheetPoint, wingCount: Int, random: Random): List<SheetPoint> {
    val shaftAngle = kotlin.math.atan2(tip.y - start.y, tip.x - start.x)
    val shaftLength = kotlin.math.hypot((tip.x - start.x).toDouble(), (tip.y - start.y).toDouble()).toFloat()
    val headLength = shaftLength * 0.2f
    val headHalfAngle = 25f * PI.toFloat() / 180f

    val shaft = wobbledLine(start, tip, pointCount = 30, wobbleFraction = 0.01f, random = random)

    val wingAngle1 = shaftAngle - PI.toFloat() + headHalfAngle
    val wing1End = SheetPoint(tip.x + headLength * cos(wingAngle1), tip.y + headLength * sin(wingAngle1))
    val wing1Out = wobbledLine(tip, wing1End, pointCount = 8, wobbleFraction = 0.02f, random = random)

    if (wingCount == 1) return shaft + wing1Out

    val wing1Back = wobbledLine(wing1End, tip, pointCount = 8, wobbleFraction = 0.02f, random = random)
    val wingAngle2 = shaftAngle - PI.toFloat() - headHalfAngle
    val wing2End = SheetPoint(tip.x + headLength * cos(wingAngle2), tip.y + headLength * sin(wingAngle2))
    val wing2Out = wobbledLine(tip, wing2End, pointCount = 8, wobbleFraction = 0.02f, random = random)

    return shaft + wing1Out + wing1Back + wing2Out
}

private fun orderedFrom(corners: List<SheetPoint>, startIndex: Int, clockwise: Boolean): List<SheetPoint> {
    val rotated = corners.drop(startIndex) + corners.take(startIndex)
    return if (clockwise) rotated else listOf(rotated[0]) + rotated.drop(1).reversed()
}

/** A closed polygon outline through [corners] in order, with each side wobbled by up to [wobbleFraction] of the side's own length. */
private fun wobbledPolygon(corners: List<SheetPoint>, pointsPerSide: Int, wobbleFraction: Float, random: Random): List<SheetPoint> {
    val closed = corners + corners.first()
    val points = mutableListOf<SheetPoint>()

    for (i in 0 until closed.size - 1) {
        val side = wobbledLine(closed[i], closed[i + 1], pointCount = pointsPerSide, wobbleFraction = wobbleFraction, random = random)
        points += if (points.isEmpty()) side else side.drop(1)
    }

    return points
}

/** An equilateral triangle's own three corners, centred on the origin, at [radius] from its own centre and rotated by [rotationRadians]. */
private fun equilateralTriangleCorners(radius: Float, rotationRadians: Float): List<SheetPoint> =
    (0 until 3).map { i ->
        val angle = rotationRadians + i * 2f * PI.toFloat() / 3f
        SheetPoint(radius * cos(angle), radius * sin(angle))
    }

private fun rotatedRectangleCorners(width: Float, height: Float, rotationRadians: Float): List<SheetPoint> {
    val halfWidth = width / 2f
    val halfHeight = height / 2f
    val localCorners = listOf(
        SheetPoint(-halfWidth, -halfHeight),
        SheetPoint(halfWidth, -halfHeight),
        SheetPoint(halfWidth, halfHeight),
        SheetPoint(-halfWidth, halfHeight)
    )

    return localCorners.map { corner ->
        SheetPoint(
            corner.x * cos(rotationRadians) - corner.y * sin(rotationRadians),
            corner.x * sin(rotationRadians) + corner.y * cos(rotationRadians)
        )
    }
}

/** An ellipse of semi-axes [radiusX] and [radiusY], left open by [openFraction] of its own perimeter, wobbled by [wobbleFraction]. */
private fun wobbledEllipse(radiusX: Float, radiusY: Float, openFraction: Float, wobbleFraction: Float, random: Random): List<SheetPoint> {
    val pointCount = 100
    val sweep = 2f * PI.toFloat() * (1f - openFraction)
    val overshootCount = if (openFraction == 0f) (pointCount * 0.05f).toInt() else 0

    val mainPoints = (0..pointCount).map { i ->
        val angle = sweep * i / pointCount
        val radius = if (i == 0 || i == pointCount) 1f else 1f + (random.nextFloat() * 2f - 1f) * wobbleFraction
        SheetPoint(radiusX * radius * cos(angle), radiusY * radius * sin(angle))
    }

    val overshoot = (1..overshootCount).map { i ->
        val angle = sweep * i / pointCount
        SheetPoint(radiusX * cos(angle), radiusY * sin(angle))
    }

    return mainPoints + overshoot
}
