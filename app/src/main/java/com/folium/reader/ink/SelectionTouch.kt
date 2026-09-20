package com.folium.reader.ink

import com.folium.reader.core.ink.SelectionCorner

/** What a SELECT-tool pointer-down lands on, once a selection already exists: a resize handle, the selection's own body (to move), or neither. */
sealed class SelectionTouchTarget {
    data class Handle(val corner: SelectionCorner) : SelectionTouchTarget()
    object Body : SelectionTouchTarget()
    object None : SelectionTouchTarget()
}

/**
 * The corner of [boundsViewPx] nearest [touch], once it lies within [hitRadiusPx] of it; `null`
 * otherwise. A tie between two equally close corners keeps whichever is checked first, which only
 * happens for a degenerate (zero-size) selection.
 */
fun selectionCornerAt(touch: ViewPoint, boundsViewPx: ViewRect, hitRadiusPx: Float): SelectionCorner? {
    val corners = listOf(
        SelectionCorner.TOP_LEFT to ViewPoint(boundsViewPx.left, boundsViewPx.top),
        SelectionCorner.TOP_RIGHT to ViewPoint(boundsViewPx.right, boundsViewPx.top),
        SelectionCorner.BOTTOM_LEFT to ViewPoint(boundsViewPx.left, boundsViewPx.bottom),
        SelectionCorner.BOTTOM_RIGHT to ViewPoint(boundsViewPx.right, boundsViewPx.bottom)
    )

    var nearest: SelectionCorner? = null
    var nearestDistanceSquared = hitRadiusPx * hitRadiusPx

    for ((corner, point) in corners) {
        val dx = touch.x - point.x
        val dy = touch.y - point.y
        val distanceSquared = dx * dx + dy * dy
        if (distanceSquared <= nearestDistanceSquared) {
            nearest = corner
            nearestDistanceSquared = distanceSquared
        }
    }

    return nearest
}

/** Whether [touch] lies inside [boundsViewPx], its own edges included. */
fun isInsideSelectionBounds(touch: ViewPoint, boundsViewPx: ViewRect): Boolean =
    touch.x in boundsViewPx.left..boundsViewPx.right && touch.y in boundsViewPx.top..boundsViewPx.bottom

/**
 * What a SELECT-tool pointer-down at [touch] should do against the current selection's own
 * [boundsViewPx]: a corner handle wins over the selection's own body whenever both match, since a
 * handle's own hit area can extend past the selection's own edge for a small selection.
 */
fun selectionTouchTarget(touch: ViewPoint, boundsViewPx: ViewRect, handleHitRadiusPx: Float): SelectionTouchTarget {
    selectionCornerAt(touch, boundsViewPx, handleHitRadiusPx)?.let { return SelectionTouchTarget.Handle(it) }
    if (isInsideSelectionBounds(touch, boundsViewPx)) return SelectionTouchTarget.Body
    return SelectionTouchTarget.None
}
