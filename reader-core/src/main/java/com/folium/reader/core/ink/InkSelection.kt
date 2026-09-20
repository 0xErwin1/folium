package com.folium.reader.core.ink

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The corner of a selection's own bounding box a resize drag is pulling on. [selectionResizeScale]
 * reads this to decide which opposite corner stays fixed as the anchor.
 */
enum class SelectionCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** The anchor point and per-axis factors [scaleStrokes] needs to carry out one resize drag. */
data class SelectionResizeScale(val anchor: SheetPoint, val scaleX: Float, val scaleY: Float)

/**
 * The floor a selection's own bounding box may never shrink under on either axis while being
 * resized, so a drag can never collapse it to a sliver or flip it through zero width. Reuses
 * [MIN_DIAGONAL_SHEET_UNITS], the same floor [recognizeShape] already requires of a shape's own
 * bounding box for it to have been a deliberate shape, rather than inventing a second, unrelated
 * notion of "too small" for the exact same kind of geometry.
 */
private const val MIN_SELECTION_SPAN_SHEET_UNITS = MIN_DIAGONAL_SHEET_UNITS

/**
 * The strokes a tap at [point] selects: the topmost (highest [InkStroke.sequence]) stroke among
 * [strokes] that [point] hits within [toleranceSheetUnits] of half its own width, exactly
 * [strokesHitBy]'s own hit rule for a tap-sized eraser path, expanded to every stroke joined to it
 * into the same recognised shape. Empty when nothing is hit.
 */
fun strokeGroupAtTap(strokes: List<InkStroke>, point: SheetPoint, toleranceSheetUnits: Float): Set<StrokeId> {
    val hitIds = strokesHitBy(listOf(point), toleranceSheetUnits, strokes)
    if (hitIds.isEmpty()) return emptySet()

    val topmost = strokes.filter { it.id in hitIds }.maxByOrNull { it.sequence } ?: return emptySet()
    return expandToJoinedShape(topmost, strokes)
}

/**
 * [seed] alongside every stroke reachable from it by repeatedly adding a stroke that has exactly
 * two samples, the same [InkStroke.tool], [InkStroke.colorArgb] and [InkStroke.widthSheetUnits],
 * and an endpoint whose coordinates equal an endpoint of a stroke already in the group — the way
 * [shapeSamples] draws a box, triangle or arrow as several two-sample sides sharing exact corners.
 *
 * [seed] itself never expands, and no other stroke is ever pulled in, unless [seed] has exactly two
 * samples: a freehand stroke's own path always has more than two, so it is returned alone.
 */
private fun expandToJoinedShape(seed: InkStroke, strokes: List<InkStroke>): Set<StrokeId> {
    if (seed.samples.size != 2) return setOf(seed.id)

    val group = mutableSetOf(seed.id)
    val groupStrokes = mutableListOf(seed)

    var grew = true
    while (grew) {
        grew = false
        for (candidate in strokes) {
            if (candidate.id in group) continue
            if (candidate.samples.size != 2) continue
            if (!sameShapeStyle(candidate, seed)) continue
            if (groupStrokes.any { sharesEndpoint(it, candidate) }) {
                group += candidate.id
                groupStrokes += candidate
                grew = true
            }
        }
    }

    return group
}

private fun sameShapeStyle(a: InkStroke, b: InkStroke): Boolean =
    a.tool == b.tool && a.colorArgb == b.colorArgb && a.widthSheetUnits == b.widthSheetUnits

private fun sharesEndpoint(a: InkStroke, b: InkStroke): Boolean {
    val aEnds = listOf(a.samples.first(), a.samples.last())
    val bEnds = listOf(b.samples.first(), b.samples.last())
    return aEnds.any { ae -> bEnds.any { be -> ae.x == be.x && ae.y == be.y } }
}

/**
 * The strokes among [strokes] more than half of whose own length lies inside [polygon], measured
 * along the stroke's path rather than by sample count, so an unevenly sampled freehand stroke and a
 * two-sample shape side are judged the same way. [polygon] is implicitly closed from its last point
 * back to its first; even-odd is used for "inside". Fewer than 3 points selects nothing.
 */
fun selectByLasso(strokes: List<InkStroke>, polygon: List<SheetPoint>): Set<StrokeId> {
    if (polygon.size < 3) return emptySet()
    return selectByPolygon(strokes, polygon, boundsOf(polygon))
}

/**
 * The strokes among [strokes] more than half of whose own length lies inside the rectangle whose
 * opposite corners are [corner1] and [corner2], in either order. Same length-weighted rule as
 * [selectByLasso].
 */
fun selectByRectangle(strokes: List<InkStroke>, corner1: SheetPoint, corner2: SheetPoint): Set<StrokeId> {
    val rect = SheetRect(
        left = min(corner1.x, corner2.x),
        top = min(corner1.y, corner2.y),
        right = max(corner1.x, corner2.x),
        bottom = max(corner1.y, corner2.y)
    )
    val polygon = listOf(
        SheetPoint(rect.left, rect.top),
        SheetPoint(rect.right, rect.top),
        SheetPoint(rect.right, rect.bottom),
        SheetPoint(rect.left, rect.bottom)
    )
    return selectByPolygon(strokes, polygon, rect)
}

private fun selectByPolygon(strokes: List<InkStroke>, polygon: List<SheetPoint>, polygonBounds: SheetRect): Set<StrokeId> {
    val selected = mutableSetOf<StrokeId>()

    for (stroke in strokes) {
        if (!polygonBounds.intersects(stroke.bounds)) continue
        if (isStrokeMostlyInside(stroke, polygon)) selected += stroke.id
    }

    return selected
}

/**
 * A stroke with a single sample (a dot) is selected when that sample itself lies inside [polygon]:
 * its path has zero length, so the length-weighted rule below cannot judge it either way. Every
 * other stroke is judged by summing, segment by segment, the length of its path that lies inside.
 */
private fun isStrokeMostlyInside(stroke: InkStroke, polygon: List<SheetPoint>): Boolean {
    if (stroke.samples.size == 1) {
        return isInsidePolygon(SheetPoint(stroke.samples[0].x, stroke.samples[0].y), polygon)
    }

    var totalLength = 0f
    var insideLength = 0f

    for ((from, to) in stroke.samples.zipWithNext()) {
        val a = SheetPoint(from.x, from.y)
        val b = SheetPoint(to.x, to.y)
        totalLength += hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
        insideLength += segmentLengthInsidePolygon(a, b, polygon)
    }

    return totalLength > 0f && insideLength > totalLength / 2f
}

/**
 * How much of segment [a]..[b] lies inside [polygon], found by cutting the segment at every point
 * it crosses one of [polygon]'s own edges, then testing the midpoint of each resulting piece: a
 * concave polygon can cross a single segment more than once, so a single inside/outside test at
 * either endpoint is not enough.
 */
private fun segmentLengthInsidePolygon(a: SheetPoint, b: SheetPoint, polygon: List<SheetPoint>): Float {
    val segmentLength = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
    if (segmentLength <= 0f) return 0f

    val params = (listOf(0f, 1f) + segmentPolygonIntersectionParams(a, b, polygon)).distinct().sorted()

    var inside = 0f
    for (i in 0 until params.size - 1) {
        val t0 = params[i]
        val t1 = params[i + 1]
        val midT = (t0 + t1) / 2f
        val midPoint = SheetPoint(a.x + (b.x - a.x) * midT, a.y + (b.y - a.y) * midT)
        if (isInsidePolygon(midPoint, polygon)) inside += (t1 - t0) * segmentLength
    }

    return inside
}

/** Every parameter `t` in `0..1` along [a]..[b] where it crosses one of [polygon]'s own edges, [polygon] taken as closed. */
private fun segmentPolygonIntersectionParams(a: SheetPoint, b: SheetPoint, polygon: List<SheetPoint>): List<Float> {
    val params = mutableListOf<Float>()

    var previous = polygon.last()
    for (current in polygon) {
        segmentIntersectionParam(a, b, previous, current)?.let { params += it }
        previous = current
    }

    return params
}

/** The parameter `t` in `0..1` along [a]..[b] where it crosses [p]..[q], or `null` when they do not cross within both segments. */
private fun segmentIntersectionParam(a: SheetPoint, b: SheetPoint, p: SheetPoint, q: SheetPoint): Float? {
    val epsilon = 1e-12f

    val rx = b.x - a.x
    val ry = b.y - a.y
    val sx = q.x - p.x
    val sy = q.y - p.y

    val rCrossS = rx * sy - ry * sx
    if (abs(rCrossS) <= epsilon) return null

    val qpx = p.x - a.x
    val qpy = p.y - a.y

    val t = (qpx * sy - qpy * sx) / rCrossS
    val u = (qpx * ry - qpy * rx) / rCrossS

    return if (t in 0f..1f && u in 0f..1f) t else null
}

/** Even-odd point-in-polygon test; [polygon] is taken as closed from its last point back to its first. */
private fun isInsidePolygon(point: SheetPoint, polygon: List<SheetPoint>): Boolean {
    var inside = false
    var previous = polygon.last()

    for (current in polygon) {
        val crossesRay = (current.y > point.y) != (previous.y > point.y)
        if (crossesRay) {
            val intersectX = current.x + (point.y - current.y) * (previous.x - current.x) / (previous.y - current.y)
            if (point.x < intersectX) inside = !inside
        }
        previous = current
    }

    return inside
}

/**
 * The smallest [SheetRect] containing every one of [selectedStrokes]' own [InkStroke.bounds], which
 * already includes half of each stroke's own [InkStroke.widthSheetUnits] on every side. `null` for an
 * empty selection.
 */
fun selectionBounds(selectedStrokes: List<InkStroke>): SheetRect? =
    selectedStrokes
        .map { it.bounds }
        .reduceOrNull(SheetRect::union)

/**
 * Copies of [strokes] moved by ([dx], [dy]) in sheet units. Each copy keeps its original's
 * [InkStroke.tool], [InkStroke.tip], [InkStroke.colorArgb], [InkStroke.widthSheetUnits],
 * [InkStroke.inputKind] and every per-sample channel besides position, and takes a fresh id from
 * [newId] and a fresh sequence from [newSequence] — the same convention [erasePartially] follows for
 * its own fragments. [strokes] must already be in ascending [InkStroke.sequence] order and
 * [newSequence] must return strictly increasing values in call order for the copies to keep the
 * originals' own relative z-order.
 */
fun translateStrokes(
    strokes: List<InkStroke>,
    dx: Float,
    dy: Float,
    newId: () -> StrokeId,
    newSequence: () -> Long
): List<InkStroke> = strokes.map { stroke ->
    rebuild(stroke, newId(), newSequence()) { sample -> sample.copy(x = sample.x + dx, y = sample.y + dy) }
}

/**
 * Copies of [strokes] with every sample mapped `anchor + (p - anchor) * (scaleX, scaleY)`; negative
 * factors flip the stroke about [anchor]. [InkStroke.widthSheetUnits] is carried over unscaled, and
 * every other channel (pressure, tilt, orientation, timing, tool, tip, colour, input kind) is copied
 * unchanged, the same as [translateStrokes]. Coordinates are signed and unbounded by design: a
 * caller that must keep a copy within [SheetColumn] clamps it itself.
 */
fun scaleStrokes(
    strokes: List<InkStroke>,
    anchor: SheetPoint,
    scaleX: Float,
    scaleY: Float,
    newId: () -> StrokeId,
    newSequence: () -> Long
): List<InkStroke> = strokes.map { stroke ->
    rebuild(stroke, newId(), newSequence()) { sample ->
        sample.copy(x = anchor.x + (sample.x - anchor.x) * scaleX, y = anchor.y + (sample.y - anchor.y) * scaleY)
    }
}

private fun rebuild(stroke: InkStroke, id: StrokeId, sequence: Long, mapSample: (InkSample) -> InkSample): InkStroke =
    InkStroke(
        id = id,
        tool = stroke.tool,
        tip = stroke.tip,
        colorArgb = stroke.colorArgb,
        widthSheetUnits = stroke.widthSheetUnits,
        inputKind = stroke.inputKind,
        samples = stroke.samples.map(mapSample),
        sequence = sequence
    )

/**
 * The anchor and per-axis scale factors a drag of [corner] to [dragPoint] represents against a
 * selection whose own bounding box is [bounds]: the opposite corner from [corner] stays fixed as the
 * anchor, and the span from it to [dragPoint] on each axis is clamped so it never falls under
 * [MIN_SELECTION_SPAN_SHEET_UNITS], the way a resized recognised shape's own span never does.
 */
fun selectionResizeScale(bounds: SheetRect, corner: SelectionCorner, dragPoint: SheetPoint): SelectionResizeScale {
    val anchor = anchorFor(bounds, corner)
    val draggedCorner = draggedCornerFor(bounds, corner)

    val originalSpanX = draggedCorner.x - anchor.x
    val originalSpanY = draggedCorner.y - anchor.y

    val newSpanX = clampedResizeSpan(dragPoint.x - anchor.x, originalSpanX)
    val newSpanY = clampedResizeSpan(dragPoint.y - anchor.y, originalSpanY)

    return SelectionResizeScale(anchor, newSpanX / originalSpanX, newSpanY / originalSpanY)
}

private fun anchorFor(bounds: SheetRect, corner: SelectionCorner): SheetPoint = when (corner) {
    SelectionCorner.TOP_LEFT -> SheetPoint(bounds.right, bounds.bottom)
    SelectionCorner.TOP_RIGHT -> SheetPoint(bounds.left, bounds.bottom)
    SelectionCorner.BOTTOM_LEFT -> SheetPoint(bounds.right, bounds.top)
    SelectionCorner.BOTTOM_RIGHT -> SheetPoint(bounds.left, bounds.top)
}

private fun draggedCornerFor(bounds: SheetRect, corner: SelectionCorner): SheetPoint = when (corner) {
    SelectionCorner.TOP_LEFT -> SheetPoint(bounds.left, bounds.top)
    SelectionCorner.TOP_RIGHT -> SheetPoint(bounds.right, bounds.top)
    SelectionCorner.BOTTOM_LEFT -> SheetPoint(bounds.left, bounds.bottom)
    SelectionCorner.BOTTOM_RIGHT -> SheetPoint(bounds.right, bounds.bottom)
}

/** [span] itself, unless its own magnitude falls under [MIN_SELECTION_SPAN_SHEET_UNITS], stretched out to exactly that floor while keeping its own sign, or [originalSpan]'s sign when [span] is exactly zero. */
private fun clampedResizeSpan(span: Float, originalSpan: Float): Float {
    val magnitude = max(abs(span), MIN_SELECTION_SPAN_SHEET_UNITS)
    val sign = if (span != 0f) (if (span > 0f) 1f else -1f) else (if (originalSpan >= 0f) 1f else -1f)
    return sign * magnitude
}
