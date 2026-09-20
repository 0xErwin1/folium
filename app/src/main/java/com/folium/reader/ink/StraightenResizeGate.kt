package com.folium.reader.ink

import kotlin.math.hypot

/**
 * Whether the pointer that just snapped a stroke into a shape has strayed far enough, in view
 * pixels, from where it was at the moment of that snap to start resizing the shape instead of
 * leaving it untouched: merely holding still, or trembling within [slopPx], must never resize it.
 * [hasExceededSlop] latches once true for the rest of the gesture, so a resize already under way
 * never turns back off once the pointer happens to fall back inside [slopPx].
 */
class StraightenResizeGate(private val slopPx: Float) {
    private var exceeded = false

    /** Feeds the pointer's own distance, [dxPx] and [dyPx], from where it was at the snap, and returns whether resizing should be active now. */
    fun hasExceededSlop(dxPx: Float, dyPx: Float): Boolean {
        if (!exceeded) exceeded = hypot(dxPx, dyPx) > slopPx
        return exceeded
    }
}
