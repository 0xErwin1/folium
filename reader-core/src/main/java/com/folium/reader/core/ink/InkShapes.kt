package com.folium.reader.core.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The straightened shape a shape-tool drag commits, alongside [InkTool.PEN]'s freehand strokes. */
enum class InkShape { LINE, ARROW, BOX, ELLIPSE }

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
 */
fun shapeSamples(shape: InkShape, start: SheetPoint, end: SheetPoint, widthSheetUnits: Float): List<List<SheetPoint>> {
    require(widthSheetUnits > 0f) { "widthSheetUnits must be positive, was $widthSheetUnits" }

    if (isDegenerateDrag(start, end, widthSheetUnits)) return emptyList()

    return when (shape) {
        InkShape.LINE -> listOf(listOf(start, end))
        InkShape.ARROW -> arrowSamples(start, end, widthSheetUnits)
        InkShape.BOX -> boxSides(start, end)
        InkShape.ELLIPSE -> listOf(ellipsePoints(start, end, widthSheetUnits))
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
