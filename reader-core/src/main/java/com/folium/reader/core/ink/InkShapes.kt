package com.folium.reader.core.ink

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The straightened shape a shape-tool drag commits, alongside [InkTool.PEN]'s freehand strokes.
 * [TRIANGLE] is appended last because the app's own pen settings persist this enum by name, not
 * ordinal, so appending it here never disturbs an already-saved choice; it is also never offered by
 * the SHAPE tool panel, which only lists its own original four shapes, and appears only as a
 * straightened freehand stroke.
 */
enum class InkShape { LINE, ARROW, BOX, ELLIPSE, TRIANGLE }

/** The arrow head's own length, as a multiple of the stroke width, clamped to a legible size regardless of how thin or thick the pen is. */
private const val ARROW_HEAD_LENGTH_WIDTH_MULTIPLIER = 6f
private const val ARROW_HEAD_LENGTH_MIN_SHEET_UNITS = 0.012f
private const val ARROW_HEAD_LENGTH_MAX_SHEET_UNITS = 0.04f

/** The open angle each of the arrow head's two strokes makes with the shaft. */
private const val ARROW_HEAD_HALF_ANGLE_RADIANS = 25f * PI.toFloat() / 180f

/** The ellipse's own segment count bounds: never so coarse it reads as a polygon, never so fine it costs an unbounded draw. */
private const val ELLIPSE_MIN_SEGMENTS = 24
private const val ELLIPSE_MAX_SEGMENTS = 180

/** The largest chord error the ellipse's own polyline may deviate from the true curve by, as a fraction of the stroke width. */
private const val ELLIPSE_MAX_CHORD_ERROR_WIDTH_FRACTION = 0.25f

/**
 * The polylines that draw [shape] between a shape-tool drag's [start] and [end], one polyline per
 * stroke a caller records: [InkShape.LINE] and [InkShape.ELLIPSE] are a single polyline, while
 * [InkShape.ARROW] is its shaft plus the two open strokes of its head and [InkShape.BOX] is its four
 * sides. Every straight part is its own two-point stroke because a stroke renderer that models
 * handwriting smooths a sharp turn inside one stroke as if the pen had swung through it, which
 * rounds a box into a triangle; separate strokes meet at exact corners under their round caps.
 *
 * A drag shorter than [widthSheetUnits] on both axes is indistinguishable from a tap, so it commits
 * nothing at all rather than a shape too small to have been intended.
 *
 * [InkShape.TRIANGLE] is the one shape whose own three sides are not fully determined by [start] and
 * [end] alone: [vertices], when given, are its own three real corners, as recognised from a freehand
 * stroke. Left empty, an isosceles triangle is inscribed in the [start]/[end] box instead, its apex at
 * the middle of that box's own top edge.
 */
fun shapeSamples(
    shape: InkShape,
    start: SheetPoint,
    end: SheetPoint,
    widthSheetUnits: Float,
    vertices: List<SheetPoint> = emptyList()
): List<List<SheetPoint>> {
    require(widthSheetUnits > 0f) { "widthSheetUnits must be positive, was $widthSheetUnits" }

    if (isDegenerateDrag(start, end, widthSheetUnits)) return emptyList()

    return when (shape) {
        InkShape.LINE -> listOf(listOf(start, end))
        InkShape.ARROW -> arrowSamples(start, end, widthSheetUnits)
        InkShape.BOX -> boxSides(start, end)
        InkShape.ELLIPSE -> listOf(ellipsePoints(start, end, widthSheetUnits))
        InkShape.TRIANGLE -> triangleSides(vertices.ifEmpty { isoscelesTriangleVertices(start, end) })
    }
}

private fun isDegenerateDrag(start: SheetPoint, end: SheetPoint, widthSheetUnits: Float): Boolean {
    val dx = abs(end.x - start.x)
    val dy = abs(end.y - start.y)
    return dx < widthSheetUnits && dy < widthSheetUnits
}

/** The shaft, then the head's two wings, each a two-point polyline ending at [end]. */
private fun arrowSamples(start: SheetPoint, end: SheetPoint, widthSheetUnits: Float): List<List<SheetPoint>> {
    val headLength = (ARROW_HEAD_LENGTH_WIDTH_MULTIPLIER * widthSheetUnits)
        .coerceIn(ARROW_HEAD_LENGTH_MIN_SHEET_UNITS, ARROW_HEAD_LENGTH_MAX_SHEET_UNITS)

    val shaftAngle = atan2(end.y - start.y, end.x - start.x)

    val leftWing = wingPoint(end, shaftAngle, headLength, ARROW_HEAD_HALF_ANGLE_RADIANS)
    val rightWing = wingPoint(end, shaftAngle, headLength, -ARROW_HEAD_HALF_ANGLE_RADIANS)

    return listOf(
        listOf(start, end),
        listOf(leftWing, end),
        listOf(rightWing, end)
    )
}

private fun wingPoint(tip: SheetPoint, shaftAngleRadians: Float, headLength: Float, offsetAngleRadians: Float): SheetPoint {
    val wingAngle = shaftAngleRadians - offsetAngleRadians
    return SheetPoint(tip.x - headLength * cos(wingAngle), tip.y - headLength * sin(wingAngle))
}

/** [start] and [end] as opposite corners of a rectangle, closed back to [start] regardless of which corner leads which. */
private fun boxCorners(start: SheetPoint, end: SheetPoint): List<SheetPoint> = listOf(
    SheetPoint(start.x, start.y),
    SheetPoint(end.x, start.y),
    SheetPoint(end.x, end.y),
    SheetPoint(start.x, end.y),
    SheetPoint(start.x, start.y)
)

/** The closed polyline of an ellipse inscribed in the rectangle [start] and [end] mark as opposite corners. */
private fun ellipsePoints(start: SheetPoint, end: SheetPoint, widthSheetUnits: Float): List<SheetPoint> {
    val centerX = (start.x + end.x) / 2f
    val centerY = (start.y + end.y) / 2f
    val radiusX = abs(end.x - start.x) / 2f
    val radiusY = abs(end.y - start.y) / 2f

    val segments = ellipseSegmentCount(radiusX, radiusY, widthSheetUnits)
    val points = (0 until segments).map { index ->
        val angle = 2f * PI.toFloat() * index / segments
        SheetPoint(centerX + radiusX * cos(angle), centerY + radiusY * sin(angle))
    }

    return points + points[0]
}

/**
 * The segment count an ellipse of semi-axes [radiusX] and [radiusY] needs so its polyline's own chord
 * error never exceeds [ELLIPSE_MAX_CHORD_ERROR_WIDTH_FRACTION] of [widthSheetUnits], clamped to
 * [ELLIPSE_MIN_SEGMENTS]..[ELLIPSE_MAX_SEGMENTS]. The chord-to-sagitta relationship is evaluated
 * against the larger semi-axis, the one whose curvature a fixed segment count approximates worst.
 */
private fun ellipseSegmentCount(radiusX: Float, radiusY: Float, widthSheetUnits: Float): Int {
    val maxRadius = maxOf(radiusX, radiusY)
    val maxChordError = ELLIPSE_MAX_CHORD_ERROR_WIDTH_FRACTION * widthSheetUnits
    val chordLength = sqrt(2f * maxRadius * maxChordError)
    val perimeter = ellipsePerimeter(radiusX, radiusY)

    val neededSegments = if (chordLength <= 0f) ELLIPSE_MAX_SEGMENTS else (perimeter / chordLength).toInt() + 1
    return neededSegments.coerceIn(ELLIPSE_MIN_SEGMENTS, ELLIPSE_MAX_SEGMENTS)
}

/** Ramanujan's second approximation of an ellipse's own perimeter, accurate to a fraction of a percent for any aspect ratio. */
private fun ellipsePerimeter(radiusX: Float, radiusY: Float): Float {
    val h = ((radiusX - radiusY) * (radiusX - radiusY)) / ((radiusX + radiusY) * (radiusX + radiusY))
    return (PI.toFloat() * (radiusX + radiusY) * (1f + 3f * h / (10f + sqrt(4f - 3f * h))))
}

/** The four sides of the rectangle whose opposite corners are [start] and [end], in drawing order. */
private fun boxSides(start: SheetPoint, end: SheetPoint): List<List<SheetPoint>> {
    val corners = boxCorners(start, end)

    return corners.zipWithNext { from, to -> listOf(from, to) }
}

/** An isosceles triangle inscribed in the rectangle [start] and [end] mark as opposite corners, its apex at the middle of that box's own top edge. */
private fun isoscelesTriangleVertices(start: SheetPoint, end: SheetPoint): List<SheetPoint> {
    val left = min(start.x, end.x)
    val right = max(start.x, end.x)
    val top = min(start.y, end.y)
    val bottom = max(start.y, end.y)

    return listOf(SheetPoint((left + right) / 2f, top), SheetPoint(right, bottom), SheetPoint(left, bottom))
}

/** The three sides of the triangle whose corners are [vertices], in drawing order, closed back to its own first corner. */
private fun triangleSides(vertices: List<SheetPoint>): List<List<SheetPoint>> {
    require(vertices.size == 3) { "a triangle needs exactly 3 vertices, was ${vertices.size}" }

    val closed = vertices + vertices.first()
    return closed.zipWithNext { from, to -> listOf(from, to) }
}

/** The pen speed a shape's samples are timed at, in sheet units per second: a slow, deliberate stroke. */
private const val SHAPE_PEN_SPEED_SHEET_UNITS_PER_SECOND = 0.25f

/** The shortest time between two samples of a shape, however close together they are. */
private const val SHAPE_MIN_SAMPLE_INTERVAL_MILLIS = 8

/**
 * The elapsed time of each point of [polyline], as if a pen had traced it slowly at a constant speed.
 *
 * A stroke renderer that models handwriting reads sample times as pen speed, and smooths a fast
 * stroke heavily: samples a millisecond apart describe a flick, and a flicked ellipse comes out
 * lopsided, its curve cut short wherever the model is still catching up. Timing the samples as a slow
 * stroke keeps the modelled line on the geometry it was given.
 */
fun shapeSampleTimesMillis(polyline: List<SheetPoint>): List<Int> {
    var elapsed = 0

    return polyline.mapIndexed { index, point ->
        if (index > 0) {
            val previous = polyline[index - 1]
            val distance = hypot((point.x - previous.x).toDouble(), (point.y - previous.y).toDouble()).toFloat()
            val interval = (distance / SHAPE_PEN_SPEED_SHEET_UNITS_PER_SECOND * 1000f).toInt()

            elapsed += maxOf(interval, SHAPE_MIN_SAMPLE_INTERVAL_MILLIS)
        }

        elapsed
    }
}
