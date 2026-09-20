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
 * [shapeSamples] needs to draw it in [start]'s and [end]'s place.
 */
data class RecognizedShape(val shape: InkShape, val start: SheetPoint, val end: SheetPoint)

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

/** A simplified vertex whose own turn is under this angle is noise on a straight edge, not a corner. */
private const val BOX_SPURIOUS_VERTEX_TURN_RADIANS = 25f * PI.toFloat() / 180f

/** A box's own corners turn by a right angle, within this tolerance. */
private const val BOX_CORNER_TURN_TARGET_RADIANS = PI.toFloat() / 2f
private const val BOX_CORNER_TURN_TOLERANCE_RADIANS = 25f * PI.toFloat() / 180f

/** A box's own edges run parallel to the sheet's axes, within this tolerance. */
private const val BOX_EDGE_AXIS_TOLERANCE_RADIANS = 15f * PI.toFloat() / 180f

/** An ellipse's own points may deviate from the inscribed curve by this much, on average, as a fraction of its own radius. */
private const val ELLIPSE_MAX_MEAN_RADIAL_ERROR = 0.12f

/** An ellipse's own shorter axis is at least this fraction of its longer one, or it reads as a line instead. */
private const val ELLIPSE_MIN_AXIS_RATIO = 0.15f

/**
 * Decides whether [points], a freehand pen stroke's own sheet-space samples in drawing order,
 * was meant as one of [InkShape]'s four shapes rather than ordinary writing, and if so the two
 * points [shapeSamples] needs to draw it in the stroke's place. Returns `null` for anything else,
 * including a stroke too short or too small to have been a deliberate shape.
 *
 * The four shapes are tried in a fixed order, LINE then ARROW then BOX then ELLIPSE, because an
 * arrow's shaft alone would also pass the LINE test and a box's own outline can pass the ELLIPSE
 * test if its corners round enough; each earlier test's own thresholds are strict enough that a
 * later shape essentially never satisfies it by accident.
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
    vertices = mergeSpuriousVertices(vertices)
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

    val box = boundingBox(raw)
    return RecognizedShape(InkShape.BOX, SheetPoint(box.left, box.top), SheetPoint(box.right, box.bottom))
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

    return RecognizedShape(InkShape.ELLIPSE, SheetPoint(box.left, box.top), SheetPoint(box.right, box.bottom))
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

/** Removes a simplified vertex whose own turn reads as noise on an otherwise straight edge, one at a time until none remain. */
private fun mergeSpuriousVertices(vertices: List<SheetPoint>): List<SheetPoint> {
    if (vertices.size <= BOX_CORNER_COUNT) return vertices

    val result = vertices.toMutableList()
    var index = 0
    while (result.size > BOX_CORNER_COUNT && index < result.size) {
        val prev = result[(index - 1 + result.size) % result.size]
        val current = result[index]
        val next = result[(index + 1) % result.size]
        if (turnAngle(prev, current, next) < BOX_SPURIOUS_VERTEX_TURN_RADIANS) {
            result.removeAt(index)
        } else {
            index++
        }
    }
    return result
}

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
