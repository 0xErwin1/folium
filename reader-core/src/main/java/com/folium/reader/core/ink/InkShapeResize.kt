package com.folium.reader.core.ink

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * [recognized] as it should look once the finger that just snapped it moves from [fingerAtSnap] to
 * [fingerNow], both in sheet space: dragging after a snap resizes the shape rather than moving or
 * re-recognising it, so the square/circle snapping [recognizeShape] applies never runs again here.
 *
 * [InkShape.LINE] and [InkShape.ARROW] keep [RecognizedShape.start] fixed and move
 * [RecognizedShape.end] by the finger's own delta. Every other shape keeps whichever bounding-box
 * corner is farthest from [fingerAtSnap] fixed as an anchor, and moves the opposite corner by that
 * same delta; [InkShape.TRIANGLE]'s own [RecognizedShape.vertices] are scaled along with it, each axis
 * independently about the anchor, so dragging the moving corner past the anchor still yields a valid,
 * mirrored triangle rather than a degenerate one.
 *
 * Neither a resized line's own length nor a resized bounding box's own width or height is ever let
 * fall under [MIN_DIAGONAL_SHEET_UNITS], the same floor [recognizeShape] itself requires of a stroke
 * for it to have been a deliberate shape to begin with.
 */
fun resizeRecognizedShape(recognized: RecognizedShape, fingerAtSnap: SheetPoint, fingerNow: SheetPoint): RecognizedShape {
    val deltaX = fingerNow.x - fingerAtSnap.x
    val deltaY = fingerNow.y - fingerAtSnap.y

    return when (recognized.shape) {
        InkShape.LINE, InkShape.ARROW -> resizeEndpointShape(recognized, deltaX, deltaY)
        InkShape.BOX, InkShape.ELLIPSE, InkShape.TRIANGLE -> resizeBoundingBoxShape(recognized, fingerAtSnap, deltaX, deltaY)
    }
}

/** [InkShape.LINE] and [InkShape.ARROW]: [RecognizedShape.start] is the anchor, [RecognizedShape.end] moves by the finger's own delta. */
private fun resizeEndpointShape(recognized: RecognizedShape, deltaX: Float, deltaY: Float): RecognizedShape {
    val anchor = recognized.start
    val movedEnd = SheetPoint(recognized.end.x + deltaX, recognized.end.y + deltaY)
    val fallbackDirection = unitDirection(anchor, recognized.end)

    return recognized.copy(end = clampedAwayFrom(anchor, movedEnd, fallbackDirection, MIN_DIAGONAL_SHEET_UNITS))
}

/**
 * [InkShape.BOX], [InkShape.ELLIPSE] and [InkShape.TRIANGLE]: the bounding-box corner farthest from
 * [fingerAtSnap] is the anchor, kept fixed; the opposite corner moves by ([deltaX], [deltaY]), scaling
 * every other point of the shape — [RecognizedShape.vertices] included — about that same anchor.
 */
private fun resizeBoundingBoxShape(recognized: RecognizedShape, fingerAtSnap: SheetPoint, deltaX: Float, deltaY: Float): RecognizedShape {
    val xs = floatArrayOf(recognized.start.x, recognized.end.x)
    val ys = floatArrayOf(recognized.start.y, recognized.end.y)

    var anchorXIndex = 0
    var anchorYIndex = 0
    var farthestDistanceSquared = -1f
    for (xIndex in 0..1) {
        for (yIndex in 0..1) {
            val distanceSquared = distanceSquared(xs[xIndex], ys[yIndex], fingerAtSnap.x, fingerAtSnap.y)
            if (distanceSquared > farthestDistanceSquared) {
                farthestDistanceSquared = distanceSquared
                anchorXIndex = xIndex
                anchorYIndex = yIndex
            }
        }
    }

    val anchorX = xs[anchorXIndex]
    val anchorY = ys[anchorYIndex]
    val originalSpanX = xs[1 - anchorXIndex] - anchorX
    val originalSpanY = ys[1 - anchorYIndex] - anchorY

    val newSpanX = clampedSpan(originalSpanX + deltaX, originalSpanX, MIN_DIAGONAL_SHEET_UNITS)
    val newSpanY = clampedSpan(originalSpanY + deltaY, originalSpanY, MIN_DIAGONAL_SHEET_UNITS)
    val scaleX = newSpanX / originalSpanX
    val scaleY = newSpanY / originalSpanY

    val newOppositeX = anchorX + newSpanX
    val newOppositeY = anchorY + newSpanY

    return recognized.copy(
        start = SheetPoint(min(anchorX, newOppositeX), min(anchorY, newOppositeY)),
        end = SheetPoint(max(anchorX, newOppositeX), max(anchorY, newOppositeY)),
        vertices = recognized.vertices.map { vertex ->
            SheetPoint(anchorX + (vertex.x - anchorX) * scaleX, anchorY + (vertex.y - anchorY) * scaleY)
        }
    )
}

/**
 * [span] itself, unless its own magnitude falls under [minLength], in which case it is stretched out
 * to exactly [minLength] while keeping its own sign — or, when it has been dragged to exactly zero,
 * the sign [originalSpan] already had, since a shape [recognizeShape] found valid always has a nonzero
 * span on both axes to begin with.
 */
private fun clampedSpan(span: Float, originalSpan: Float, minLength: Float): Float {
    val magnitude = max(abs(span), minLength)
    val sign = if (span != 0f) (if (span > 0f) 1f else -1f) else (if (originalSpan >= 0f) 1f else -1f)
    return sign * magnitude
}

/** [target] moved away from [anchor] until at least [minLength] apart, along [target]'s own direction from [anchor], or [fallbackDirection]'s when [target] lands exactly on [anchor]. */
private fun clampedAwayFrom(anchor: SheetPoint, target: SheetPoint, fallbackDirection: Pair<Float, Float>, minLength: Float): SheetPoint {
    val dx = target.x - anchor.x
    val dy = target.y - anchor.y
    val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
    if (length >= minLength) return target

    val (directionX, directionY) = if (length > 0f) dx / length to dy / length else fallbackDirection
    return SheetPoint(anchor.x + directionX * minLength, anchor.y + directionY * minLength)
}

/** The unit vector from [from] to [to], or `(1f, 0f)` when they coincide. */
private fun unitDirection(from: SheetPoint, to: SheetPoint): Pair<Float, Float> {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
    return if (length > 0f) dx / length to dy / length else 1f to 0f
}

private fun distanceSquared(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = ax - bx
    val dy = ay - by
    return dx * dx + dy * dy
}
