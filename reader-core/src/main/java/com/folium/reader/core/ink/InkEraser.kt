package com.folium.reader.core.ink

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The strokes among [strokes] a whole-stroke eraser sweeping [eraserPath] would erase: a stroke is
 * hit when the closest distance between the eraser's path and the stroke's own path is at most
 * [eraserRadius] plus half the stroke's own width, so a thick stroke is easier to hit than a thin
 * one even along the same centerline. [strokes] whose bounds cannot possibly be close enough are
 * skipped before any distance is computed.
 *
 * A single-point [eraserPath] (a tap rather than a drag) and a single-sample stroke (a dot) are
 * both treated as a degenerate, zero-length segment rather than special-cased away.
 */
fun strokesHitBy(eraserPath: List<SheetPoint>, eraserRadius: Float, strokes: List<InkStroke>): Set<StrokeId> {
    require(eraserPath.isNotEmpty()) { "eraserPath must have at least one point" }
    require(eraserRadius >= 0f) { "eraserRadius must be non-negative, was $eraserRadius" }
    if (strokes.isEmpty()) return emptySet()

    val eraserBounds = boundsOf(eraserPath).inflate(eraserRadius)
    val hit = mutableSetOf<StrokeId>()

    for (stroke in strokes) {
        if (!eraserBounds.intersects(stroke.bounds)) continue

        val threshold = eraserRadius + stroke.widthSheetUnits / 2f
        val strokePath = stroke.samples.map { SheetPoint(it.x, it.y) }
        if (minPolylineDistance(eraserPath, strokePath) <= threshold) hit += stroke.id
    }

    return hit
}

private fun boundsOf(points: List<SheetPoint>): SheetRect {
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

private fun segmentsOf(points: List<SheetPoint>): List<Pair<SheetPoint, SheetPoint>> =
    if (points.size == 1) listOf(points[0] to points[0]) else points.zipWithNext()

private fun minPolylineDistance(a: List<SheetPoint>, b: List<SheetPoint>): Float {
    val segmentsA = segmentsOf(a)
    val segmentsB = segmentsOf(b)

    var minDistance = Float.MAX_VALUE
    for ((a1, a2) in segmentsA) {
        for ((b1, b2) in segmentsB) {
            minDistance = min(minDistance, segmentDistance(a1, a2, b1, b2))
        }
    }
    return minDistance
}

/**
 * The shortest distance between segment `p1..q1` and segment `p2..q2`, each of which may be a
 * degenerate zero-length segment (a single point). Handles a crossing pair correctly (distance
 * `0`), not just the case where the closest points are one segment's endpoint against the other.
 */
private fun segmentDistance(p1: SheetPoint, q1: SheetPoint, p2: SheetPoint, q2: SheetPoint): Float {
    val epsilon = 1e-12f

    val d1x = q1.x - p1.x
    val d1y = q1.y - p1.y
    val d2x = q2.x - p2.x
    val d2y = q2.y - p2.y
    val rx = p1.x - p2.x
    val ry = p1.y - p2.y

    val lengthSquared1 = d1x * d1x + d1y * d1y
    val lengthSquared2 = d2x * d2x + d2y * d2y

    var s: Float
    var t: Float

    if (lengthSquared1 <= epsilon && lengthSquared2 <= epsilon) {
        s = 0f
        t = 0f
    } else if (lengthSquared1 <= epsilon) {
        s = 0f
        t = ((d2x * rx + d2y * ry) / lengthSquared2).coerceIn(0f, 1f)
    } else {
        val c = d1x * rx + d1y * ry
        if (lengthSquared2 <= epsilon) {
            t = 0f
            s = (-c / lengthSquared1).coerceIn(0f, 1f)
        } else {
            val f = d2x * rx + d2y * ry
            val b = d1x * d2x + d1y * d2y
            val denominator = lengthSquared1 * lengthSquared2 - b * b

            s = if (denominator > epsilon) {
                ((b * f - c * lengthSquared2) / denominator).coerceIn(0f, 1f)
            } else {
                0f
            }

            t = (b * s + f) / lengthSquared2
            if (t < 0f) {
                t = 0f
                s = (-c / lengthSquared1).coerceIn(0f, 1f)
            } else if (t > 1f) {
                t = 1f
                s = ((b - c) / lengthSquared1).coerceIn(0f, 1f)
            }
        }
    }

    val closest1x = p1.x + d1x * s
    val closest1y = p1.y + d1y * s
    val closest2x = p2.x + d2x * t
    val closest2y = p2.y + d2y * t

    return hypot((closest1x - closest2x).toDouble(), (closest1y - closest2y).toDouble()).toFloat()
}
