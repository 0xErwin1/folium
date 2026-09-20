package com.folium.reader.core.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * The shape [recognizeShape] believes a freehand stroke was meant as, with the two points
 * [shapeSamples] needs to draw it in [start]'s and [end]'s place. [vertices] carries
 * [InkShape.TRIANGLE]'s own three real corners, in drawing order, and is empty for every other shape;
 * [start] and [end] remain that triangle's own bounding-box corners, exactly as for [InkShape.BOX].
 */
data class RecognizedShape(
    val shape: InkShape,
    val start: SheetPoint,
    val end: SheetPoint,
    val vertices: List<SheetPoint> = emptyList()
)

/** The stroke is resampled to this many evenly-spaced points before any shape test runs on it. */
private const val RESAMPLE_POINT_COUNT = 64

/** Fewer raw samples than this is a tap or a flick, never a deliberate shape. */
private const val MIN_RAW_POINTS = 8

/** A stroke whose bounding box is smaller than this, in sheet units, is a dot or a tick, never a shape. */
private const val MIN_DIAGONAL_SHEET_UNITS = 0.02f

/** Above this fraction of the stroke's own length, its two ends are far apart: an open stroke. */
private const val OPEN_MIN_CLOSURE = 0.7f

/** Below this fraction, the stroke's own ends have returned to each other: a closed stroke. */
private const val CLOSED_MAX_CLOSURE = 0.2f

/** A line's own points may wander this fraction of its chord's length away from that chord. */
private const val LINE_MAX_CHORD_DEVIATION_CHORD_FRACTION = 0.06f

/** A chord within this angle of horizontal or vertical is drawn as if it were exactly that axis. */
private const val AXIS_SNAP_TOLERANCE_RADIANS = 5f * PI.toFloat() / 180f

/** Ramer-Douglas-Peucker's own tolerance, scaled to the stroke's own size so it works at any scale. */
private const val SIMPLIFY_EPSILON_DIAGONAL_FRACTION = 0.04f

/** An arrow's shaft is at least this fraction of the whole stroke's own length. */
private const val ARROW_MIN_SHAFT_LENGTH_FRACTION = 0.55f

/** Each of an arrow head's own strokes is shorter than this fraction of the shaft. */
private const val ARROW_MAX_HEAD_STROKE_SHAFT_FRACTION = 0.35f

/** An arrow head's own strokes turn back from the shaft's direction within this angular range. */
private const val ARROW_HEAD_TURN_MIN_RADIANS = 110f * PI.toFloat() / 180f
private const val ARROW_HEAD_TURN_MAX_RADIANS = 170f * PI.toFloat() / 180f

/** A simplified vertex whose own turn is under this angle is noise at the shaft-to-head corner, not a real head stroke. */
private const val ARROW_SPURIOUS_VERTEX_TURN_RADIANS = 25f * PI.toFloat() / 180f

/** A box has exactly this many corners once its own outline is simplified. */
private const val BOX_CORNER_COUNT = 4

/** A box's own corners turn by a right angle, within this tolerance. */
private const val BOX_CORNER_TURN_TARGET_RADIANS = PI.toFloat() / 2f
private const val BOX_CORNER_TURN_TOLERANCE_RADIANS = 25f * PI.toFloat() / 180f

/** A box's own edges run parallel to the sheet's axes, within this tolerance. */
private const val BOX_EDGE_AXIS_TOLERANCE_RADIANS = 15f * PI.toFloat() / 180f

/** An ellipse's own points may deviate from the inscribed curve by this much, on average, as a fraction of its own radius. */
private const val ELLIPSE_MAX_MEAN_RADIAL_ERROR = 0.12f

/** An ellipse's own shorter axis is at least this fraction of its longer one, or it reads as a line instead. */
private const val ELLIPSE_MIN_AXIS_RATIO = 0.15f

/** A triangle has exactly this many corners once its own outline is simplified. */
private const val TRIANGLE_CORNER_COUNT = 3

/** A triangle's own interior angle must be at least this wide, or the corner it forms is too thin a sliver to have been intended. */
private const val TRIANGLE_MIN_INTERIOR_ANGLE_RADIANS = 15f * PI.toFloat() / 180f

/** A triangle's own area must be at least this fraction of its bounding box's area, or it is too thin a sliver to have been intended. */
private const val TRIANGLE_MIN_AREA_BOUNDING_BOX_FRACTION = 0.10f

/** A triangle's own resampled points may wander this far from its three candidate edges, as a fraction of the stroke's own diagonal, or the outline they were reduced from was never straight-sided to begin with. */
private const val TRIANGLE_MAX_EDGE_DEVIATION_DIAGONAL_FRACTION = 0.05f

/** A recognised box or ellipse whose two sides, or two axes, differ by less than this fraction of the longer one snaps to an exact square or circle. */
private const val SQUARE_OR_CIRCLE_SNAP_MAX_SIDE_DIFFERENCE_FRACTION = 0.12f

/**
 * Decides whether [points], a freehand pen stroke's own sheet-space samples in drawing order,
 * was meant as one of [InkShape]'s five shapes rather than ordinary writing, and if so the two
 * points [shapeSamples] needs to draw it in the stroke's place. Returns `null` for anything else,
 * including a stroke too short or too small to have been a deliberate shape.
 *
 * The five shapes are tried in a fixed order, LINE then ARROW then BOX then TRIANGLE then ELLIPSE,
 * because an arrow's shaft alone would also pass the LINE test, and a box's or a triangle's own
 * outline can pass the ELLIPSE test if its corners round enough; TRIANGLE is tried before ELLIPSE,
 * and after BOX, so a corner count of exactly three or four is caught by its own polygon test before
 * a rounded one could be mistaken for the ellipse's smooth curve. Each earlier test's own thresholds
 * are strict enough that a later shape essentially never satisfies it by accident.
 */
fun recognizeShape(points: List<SheetPoint>): RecognizedShape? {
    if (points.size < MIN_RAW_POINTS) return null

    val length = pathLength(points)
    val diagonal = boundingBoxDiagonal(points)
    if (length <= 0f || diagonal < MIN_DIAGONAL_SHEET_UNITS) return null

    val resampled = resample(points, RESAMPLE_POINT_COUNT)
    val closure = distance(points.first(), points.last()) / length

    recognizeLine(points, resampled, closure)?.let { return it }
    recognizeArrow(points, resampled, length, diagonal, closure)?.let { return it }
    recognizeBox(points, resampled, diagonal, closure)?.let { return it }
    recognizeTriangle(points, resampled, diagonal, closure)?.let { return it }
    recognizeEllipse(points, resampled, closure)?.let { return it }
    return null
}

private fun recognizeLine(raw: List<SheetPoint>, resampled: List<SheetPoint>, closure: Float): RecognizedShape? {
    if (closure < OPEN_MIN_CLOSURE) return null

    val start = raw.first()
    val end = raw.last()
    val chordLength = distance(start, end)
    if (chordLength <= 0f) return null

    val maxDeviation = resampled.maxOf { perpendicularDistance(it, start, end) }
    if (maxDeviation > LINE_MAX_CHORD_DEVIATION_CHORD_FRACTION * chordLength) return null

    val (snappedStart, snappedEnd) = snapToAxisIfClose(start, end)
    return RecognizedShape(InkShape.LINE, snappedStart, snappedEnd)
}

/** Snaps [start]-[end] onto the horizontal or vertical axis when it is already close to one, keeping their midpoint fixed. */
private fun snapToAxisIfClose(start: SheetPoint, end: SheetPoint): Pair<SheetPoint, SheetPoint> {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val halfLength = distance(start, end) / 2f
    if (halfLength <= 0f) return Pair(start, end)

    val angleModHalfTurn = ((atan2(dy, dx) % PI.toFloat()) + PI.toFloat()) % PI.toFloat()
    val midX = (start.x + end.x) / 2f
    val midY = (start.y + end.y) / 2f

    val isHorizontal = angleModHalfTurn < AXIS_SNAP_TOLERANCE_RADIANS ||
        angleModHalfTurn > PI.toFloat() - AXIS_SNAP_TOLERANCE_RADIANS
    val isVertical = abs(angleModHalfTurn - PI.toFloat() / 2f) < AXIS_SNAP_TOLERANCE_RADIANS

    return when {
        isHorizontal -> {
            val sign = if (dx >= 0f) 1f else -1f
            Pair(SheetPoint(midX - sign * halfLength, midY), SheetPoint(midX + sign * halfLength, midY))
        }
        isVertical -> {
            val sign = if (dy >= 0f) 1f else -1f
            Pair(SheetPoint(midX, midY - sign * halfLength), SheetPoint(midX, midY + sign * halfLength))
        }
        else -> Pair(start, end)
    }
}

/**
 * An arrow's shaft, drawn first and simplified to its own single segment, followed by one or two
 * short strokes that turn back from it without lifting the pen: the head's wings.
 */
private fun recognizeArrow(
    raw: List<SheetPoint>,
    resampled: List<SheetPoint>,
    length: Float,
    diagonal: Float,
    closure: Float
): RecognizedShape? {
    if (closure <= CLOSED_MAX_CLOSURE) return null

    val epsilon = SIMPLIFY_EPSILON_DIAGONAL_FRACTION * diagonal
    val vertices = removeStraightVertices(simplify(resampled, epsilon), ARROW_SPURIOUS_VERTEX_TURN_RADIANS)
    if (vertices.size < 3) return null

    val shaftStart = vertices[0]
    val tip = vertices[1]
    val shaftLength = distance(shaftStart, tip)
    if (shaftLength < ARROW_MIN_SHAFT_LENGTH_FRACTION * length) return null

    val shaftAngle = atan2(tip.y - shaftStart.y, tip.x - shaftStart.x)
    var sawHeadStroke = false

    for (i in 2 until vertices.size) {
        val candidate = vertices[i]
        val distanceFromTip = distance(tip, candidate)
        if (distanceFromTip < epsilon) continue

        if (distanceFromTip >= ARROW_MAX_HEAD_STROKE_SHAFT_FRACTION * shaftLength) return null

        val candidateAngle = atan2(candidate.y - tip.y, candidate.x - tip.x)
        val turn = angleDifference(candidateAngle, shaftAngle)
        if (turn < ARROW_HEAD_TURN_MIN_RADIANS || turn > ARROW_HEAD_TURN_MAX_RADIANS) return null

        sawHeadStroke = true
    }

    if (!sawHeadStroke) return null
    return RecognizedShape(InkShape.ARROW, raw.first(), tip)
}

/** A closed, four-cornered, axis-aligned outline. */
private fun recognizeBox(raw: List<SheetPoint>, resampled: List<SheetPoint>, diagonal: Float, closure: Float): RecognizedShape? {
    if (closure > CLOSED_MAX_CLOSURE) return null

    val epsilon = SIMPLIFY_EPSILON_DIAGONAL_FRACTION * diagonal
    var vertices = simplify(resampled, epsilon)
    if (vertices.size > 1 && distance(vertices.first(), vertices.last()) < epsilon) {
        vertices = vertices.dropLast(1)
    }
    vertices = mergeSpuriousVertices(vertices, BOX_CORNER_COUNT)
    if (vertices.size != BOX_CORNER_COUNT) return null

    for (i in vertices.indices) {
        val prev = vertices[(i - 1 + vertices.size) % vertices.size]
        val current = vertices[i]
        val next = vertices[(i + 1) % vertices.size]
        val turn = turnAngle(prev, current, next)
        if (abs(turn - BOX_CORNER_TURN_TARGET_RADIANS) > BOX_CORNER_TURN_TOLERANCE_RADIANS) return null
    }

    for (i in vertices.indices) {
        val from = vertices[i]
        val to = vertices[(i + 1) % vertices.size]
        if (!isAxisAlignedEdge(from, to)) return null
    }

    val box = snappedToSquareIfClose(boundingBox(raw))
    return RecognizedShape(InkShape.BOX, SheetPoint(box.left, box.top), SheetPoint(box.right, box.bottom))
}

/**
 * A closed outline of exactly three corners, the third shape a box-like polygon test tries after
 * BOX's own four-corner test has failed. Its own three real [SheetPoint]s are kept, snapped onto the
 * horizontal or vertical axis one side at a time where a side is already close to one, rather than
 * forced into any particular symmetry: a freehand triangle is rarely isosceles or right-angled on
 * purpose, but a side the pen meant to be level or plumb reads better once it is exactly that.
 *
 * Reducing any closed loop's own simplified outline down to exactly three points always yields
 * *some* triangle, including a smooth curve's: [resampled] must actually hug the three candidate
 * edges within [TRIANGLE_MAX_EDGE_DEVIATION_DIAGONAL_FRACTION] of the stroke's own [diagonal], the
 * same test [InkShape.LINE] applies to its own chord, or a circle or an ellipse reduced this way is
 * rejected instead.
 */
private fun recognizeTriangle(raw: List<SheetPoint>, resampled: List<SheetPoint>, diagonal: Float, closure: Float): RecognizedShape? {
    if (closure > CLOSED_MAX_CLOSURE) return null

    val epsilon = SIMPLIFY_EPSILON_DIAGONAL_FRACTION * diagonal
    var vertices = simplify(resampled, epsilon)
    if (vertices.size > 1 && distance(vertices.first(), vertices.last()) < epsilon) {
        vertices = vertices.dropLast(1)
    }
    vertices = mergeSpuriousVertices(vertices, TRIANGLE_CORNER_COUNT)
    if (vertices.size != TRIANGLE_CORNER_COUNT) return null

    for (i in vertices.indices) {
        val prev = vertices[(i - 1 + vertices.size) % vertices.size]
        val current = vertices[i]
        val next = vertices[(i + 1) % vertices.size]
        val interiorAngle = PI.toFloat() - turnAngle(prev, current, next)
        if (interiorAngle < TRIANGLE_MIN_INTERIOR_ANGLE_RADIANS) return null
    }

    val triangleBox = boundingBox(vertices)
    val boxArea = triangleBox.width * triangleBox.height
    if (boxArea <= 0f || polygonArea(vertices) < TRIANGLE_MIN_AREA_BOUNDING_BOX_FRACTION * boxArea) return null

    val maxEdgeDeviation = resampled.maxOf { distanceToPolygon(it, vertices) }
    if (maxEdgeDeviation > TRIANGLE_MAX_EDGE_DEVIATION_DIAGONAL_FRACTION * diagonal) return null

    val snappedVertices = snapTriangleSidesToAxis(vertices)
    val snappedBox = boundingBox(snappedVertices)
    return RecognizedShape(
        InkShape.TRIANGLE,
        SheetPoint(snappedBox.left, snappedBox.top),
        SheetPoint(snappedBox.right, snappedBox.bottom),
        snappedVertices
    )
}

/** Moves each side of [vertices] that is already close to horizontal or vertical exactly onto that axis, keeping that side's own midpoint fixed. */
private fun snapTriangleSidesToAxis(vertices: List<SheetPoint>): List<SheetPoint> {
    val snapped = vertices.toMutableList()

    for (i in vertices.indices) {
        val next = (i + 1) % vertices.size
        val from = snapped[i]
        val to = snapped[next]
        val angleModHalfTurn = ((atan2(to.y - from.y, to.x - from.x) % PI.toFloat()) + PI.toFloat()) % PI.toFloat()
        val isHorizontal = angleModHalfTurn < AXIS_SNAP_TOLERANCE_RADIANS || angleModHalfTurn > PI.toFloat() - AXIS_SNAP_TOLERANCE_RADIANS
        val isVertical = abs(angleModHalfTurn - PI.toFloat() / 2f) < AXIS_SNAP_TOLERANCE_RADIANS

        when {
            isHorizontal -> {
                val meanY = (from.y + to.y) / 2f
                snapped[i] = SheetPoint(from.x, meanY)
                snapped[next] = SheetPoint(to.x, meanY)
            }
            isVertical -> {
                val meanX = (from.x + to.x) / 2f
                snapped[i] = SheetPoint(meanX, from.y)
                snapped[next] = SheetPoint(meanX, to.y)
            }
        }
    }

    return snapped
}

/** The shoelace formula's own unsigned area of the closed polygon through [vertices] in order. */
private fun polygonArea(vertices: List<SheetPoint>): Float {
    var signedArea = 0f
    for (i in vertices.indices) {
        val current = vertices[i]
        val next = vertices[(i + 1) % vertices.size]
        signedArea += current.x * next.y - next.x * current.y
    }
    return abs(signedArea) / 2f
}

/** [point]'s own distance to the closest of the closed polygon's edges through [vertices] in order. */
private fun distanceToPolygon(point: SheetPoint, vertices: List<SheetPoint>): Float =
    vertices.indices.minOf { i -> distanceToSegment(point, vertices[i], vertices[(i + 1) % vertices.size]) }

/** [point]'s own distance to the closest point of the segment from [from] to [to], not the infinite line through them. */
private fun distanceToSegment(point: SheetPoint, from: SheetPoint, to: SheetPoint): Float {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= 0f) return distance(point, from)

    val t = (((point.x - from.x) * dx + (point.y - from.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    val projection = SheetPoint(from.x + t * dx, from.y + t * dy)
    return distance(point, projection)
}

/**
 * [box] itself, unless its own width and height differ by less than
 * [SQUARE_OR_CIRCLE_SNAP_MAX_SIDE_DIFFERENCE_FRACTION] of the longer one, in which case its own mean
 * side is used for both, keeping its centre fixed: a recognised box reads as an exact square, and a
 * recognised ellipse's own bounding box reads as an exact circle.
 */
private fun snappedToSquareIfClose(box: SheetRect): SheetRect {
    val longerSide = max(box.width, box.height)
    if (longerSide <= 0f || abs(box.width - box.height) >= SQUARE_OR_CIRCLE_SNAP_MAX_SIDE_DIFFERENCE_FRACTION * longerSide) return box

    val meanHalfSide = (box.width + box.height) / 4f
    val centerX = (box.left + box.right) / 2f
    val centerY = (box.top + box.bottom) / 2f
    return SheetRect(centerX - meanHalfSide, centerY - meanHalfSide, centerX + meanHalfSide, centerY + meanHalfSide)
}

/** A closed outline that is not a box and hugs the ellipse inscribed in its own bounding box. */
private fun recognizeEllipse(raw: List<SheetPoint>, resampled: List<SheetPoint>, closure: Float): RecognizedShape? {
    if (closure > CLOSED_MAX_CLOSURE) return null

    val box = boundingBox(raw)
    val semiMajor = box.width / 2f
    val semiMinor = box.height / 2f
    if (semiMajor <= 0f || semiMinor <= 0f) return null

    val shorterSide = min(box.width, box.height)
    val longerSide = max(box.width, box.height)
    if (shorterSide < ELLIPSE_MIN_AXIS_RATIO * longerSide) return null

    val centerX = (box.left + box.right) / 2f
    val centerY = (box.top + box.bottom) / 2f

    val meanRadialError = resampled.map { point ->
        val normalizedX = (point.x - centerX) / semiMajor
        val normalizedY = (point.y - centerY) / semiMinor
        abs(hypot(normalizedX.toDouble(), normalizedY.toDouble()).toFloat() - 1f)
    }.average().toFloat()
    if (meanRadialError > ELLIPSE_MAX_MEAN_RADIAL_ERROR) return null

    val snappedBox = snappedToSquareIfClose(box)
    return RecognizedShape(InkShape.ELLIPSE, SheetPoint(snappedBox.left, snappedBox.top), SheetPoint(snappedBox.right, snappedBox.bottom))
}

/**
 * Removes an interior vertex of the open polyline [vertices] whose own turn is under [turnThresholdRadians],
 * one at a time until none remain: a corner where the pen briefly changed direction without really
 * turning, such as the wobble simplification leaves at the exact point a shaft ends and a head stroke
 * begins. Never touches the first or last vertex, since those are the polyline's own genuine ends.
 */
private fun removeStraightVertices(vertices: List<SheetPoint>, turnThresholdRadians: Float): List<SheetPoint> {
    if (vertices.size < 3) return vertices

    val result = vertices.toMutableList()
    var index = 1
    while (result.size > 2 && index < result.size - 1) {
        val turn = turnAngle(result[index - 1], result[index], result[index + 1])
        if (turn < turnThresholdRadians) {
            result.removeAt(index)
        } else {
            index++
        }
    }
    return result
}

/**
 * Removes the simplified vertex whose own two neighbours make the smallest triangle with it, one at a
 * time until [targetCount] remain (Visvalingam-Whyatt): a stray point a wobble left almost on top of
 * a real corner contributes a tiny triangle regardless of how sharp its own local turn reads, while a
 * shape's own real corners each contribute a large one.
 */
private fun mergeSpuriousVertices(vertices: List<SheetPoint>, targetCount: Int): List<SheetPoint> {
    if (vertices.size <= targetCount) return vertices

    val result = vertices.toMutableList()
    while (result.size > targetCount) {
        var leastSignificantIndex = 0
        var smallestArea = Float.MAX_VALUE

        for (i in result.indices) {
            val prev = result[(i - 1 + result.size) % result.size]
            val current = result[i]
            val next = result[(i + 1) % result.size]
            val area = triangleArea(prev, current, next)
            if (area < smallestArea) {
                smallestArea = area
                leastSignificantIndex = i
            }
        }

        result.removeAt(leastSignificantIndex)
    }
    return result
}

private fun triangleArea(a: SheetPoint, b: SheetPoint, c: SheetPoint): Float =
    abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2f

private fun isAxisAlignedEdge(from: SheetPoint, to: SheetPoint): Boolean {
    val angle = atan2(to.y - from.y, to.x - from.x)
    val quarterTurn = PI.toFloat() / 2f
    val offsetFromNearestAxis = angle - quarterTurn * round(angle / quarterTurn)
    return abs(offsetFromNearestAxis) < BOX_EDGE_AXIS_TOLERANCE_RADIANS
}

/** The exterior turn a path makes at [current], from the direction it arrived in to the direction it leaves in. */
private fun turnAngle(prev: SheetPoint, current: SheetPoint, next: SheetPoint): Float {
    val incomingAngle = atan2(current.y - prev.y, current.x - prev.x)
    val outgoingAngle = atan2(next.y - current.y, next.x - current.x)
    return angleDifference(incomingAngle, outgoingAngle)
}

/** The smaller of the two angles between directions [a] and [b], always in `0..PI`. */
private fun angleDifference(a: Float, b: Float): Float {
    val twoPi = 2f * PI.toFloat()
    var diff = abs(a - b) % twoPi
    if (diff > PI.toFloat()) diff = twoPi - diff
    return diff
}

/** Ramer-Douglas-Peucker: the smallest subsequence of [points], always keeping its own first and last, that stays within [epsilon]. */
private fun simplify(points: List<SheetPoint>, epsilon: Float): List<SheetPoint> {
    if (points.size < 3) return points

    var maxDistance = 0f
    var splitIndex = 0
    for (i in 1 until points.size - 1) {
        val d = perpendicularDistance(points[i], points.first(), points.last())
        if (d > maxDistance) {
            maxDistance = d
            splitIndex = i
        }
    }

    return if (maxDistance > epsilon) {
        val left = simplify(points.subList(0, splitIndex + 1), epsilon)
        val right = simplify(points.subList(splitIndex, points.size), epsilon)
        left.dropLast(1) + right
    } else {
        listOf(points.first(), points.last())
    }
}

private fun perpendicularDistance(point: SheetPoint, lineStart: SheetPoint, lineEnd: SheetPoint): Float {
    val dx = lineEnd.x - lineStart.x
    val dy = lineEnd.y - lineStart.y
    val lineLength = hypot(dx.toDouble(), dy.toDouble()).toFloat()
    if (lineLength <= 0f) return distance(point, lineStart)

    val numerator = abs(dy * point.x - dx * point.y + lineEnd.x * lineStart.y - lineEnd.y * lineStart.x)
    return numerator / lineLength
}

/** [points] resampled to exactly [count] points, evenly spaced by arc length along the original path. */
private fun resample(points: List<SheetPoint>, count: Int): List<SheetPoint> {
    val cumulative = FloatArray(points.size)
    for (i in 1 until points.size) {
        cumulative[i] = cumulative[i - 1] + distance(points[i - 1], points[i])
    }
    val length = cumulative.last()
    if (length <= 0f) return List(count) { points.first() }

    val interval = length / (count - 1)
    val result = ArrayList<SheetPoint>(count)
    var segmentIndex = 0

    for (i in 0 until count) {
        val targetDistance = if (i == count - 1) length else i * interval
        while (segmentIndex < points.size - 2 && cumulative[segmentIndex + 1] < targetDistance) {
            segmentIndex++
        }

        val from = points[segmentIndex]
        val to = points[segmentIndex + 1]
        val segmentLength = cumulative[segmentIndex + 1] - cumulative[segmentIndex]
        val fraction = if (segmentLength > 0f) (targetDistance - cumulative[segmentIndex]) / segmentLength else 0f
        result.add(SheetPoint(from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction))
    }

    return result
}

private fun pathLength(points: List<SheetPoint>): Float {
    var total = 0f
    for (i in 1 until points.size) total += distance(points[i - 1], points[i])
    return total
}

private fun boundingBox(points: List<SheetPoint>): SheetRect {
    var left = points[0].x
    var right = points[0].x
    var top = points[0].y
    var bottom = points[0].y

    for (point in points) {
        left = min(left, point.x)
        right = max(right, point.x)
        top = min(top, point.y)
        bottom = max(bottom, point.y)
    }

    return SheetRect(left, top, right, bottom)
}

private fun boundingBoxDiagonal(points: List<SheetPoint>): Float {
    val box = boundingBox(points)
    return hypot(box.width.toDouble(), box.height.toDouble()).toFloat()
}

private fun distance(a: SheetPoint, b: SheetPoint): Float =
    hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
