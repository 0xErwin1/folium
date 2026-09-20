package com.folium.reader.ink

import kotlin.math.abs
import kotlin.math.hypot

/** One active pointer's position, in the pixel space of the view hosting a two-finger gesture. */
data class PointerPosition(val x: Float, val y: Float)

/**
 * The pan and zoom to apply for one touch move. [zoomFactor] of `1f` means no zoom for this step;
 * [focalX] and [focalY] are the centroid the zoom, if any, should pivot around.
 */
data class PanZoomStep(
    val panDxPx: Float,
    val panDyPx: Float,
    val zoomFactor: Float,
    val focalX: Float,
    val focalY: Float
)

/**
 * Turns a stream of active-pointer positions into [PanZoomStep]s, deciding between panning and
 * zooming instead of applying both to every frame: a real two-finger scroll always keeps the
 * finger span a little unsteady, and applying that jitter as zoom is what makes scrolling feel
 * broken. [panSlopPx] is the centroid travel, in view pixels, that commits a gesture to panning
 * once it is under way; the caller passes a multiple of the platform's own touch slop.
 *
 * A gesture starts [Mode.UNDECIDED] at its two-pointer baseline. Panning is always applied from
 * the very first move, since a scroll must feel instant; only whether the span change is also
 * treated as a zoom is deferred. Once [Mode.PAN] is decided, zoom stays locked out unless the
 * pinch is deliberate enough to break out of it, so a scroll that continues to wobble a little
 * cannot suddenly start zooming either.
 *
 * Not thread-safe: driven from the single thread touch events already arrive on.
 */
class PanZoomTracker(private val panSlopPx: Float) {

    private enum class Mode { UNDECIDED, PAN, ZOOM }

    private var mode = Mode.UNDECIDED

    private var focalX = 0f
    private var focalY = 0f

    private var undecidedBaselineFocalX = 0f
    private var undecidedBaselineFocalY = 0f
    private var undecidedBaselineSpan = 0f

    private var panBreakoutBaselineSpan = 0f
    private var zoomBaselineSpan = 0f

    /**
     * Re-anchors this tracker to [pointers] without producing a step, for a pointer going down or
     * up mid-gesture. Keeps the current mode across a pointer count that stays at two or more, but
     * a gesture that has dropped to one pointer starts [Mode.UNDECIDED] again once a second pointer
     * returns, since the two fingers now down are not the ones the previous decision was based on.
     */
    fun rebaseline(pointers: List<PointerPosition>) {
        require(pointers.isNotEmpty()) { "rebaseline requires at least one pointer" }

        val focal = focalOf(pointers)
        focalX = focal.first
        focalY = focal.second

        if (pointers.size < 2) {
            mode = Mode.UNDECIDED
            return
        }

        val span = spanOf(pointers, focal)
        zoomBaselineSpan = span
        panBreakoutBaselineSpan = span

        if (mode == Mode.UNDECIDED) {
            undecidedBaselineFocalX = focalX
            undecidedBaselineFocalY = focalY
            undecidedBaselineSpan = span
        }
    }

    /** The [PanZoomStep] for [pointers] having just moved from the last-known or rebaselined positions. */
    fun onMove(pointers: List<PointerPosition>): PanZoomStep {
        require(pointers.isNotEmpty()) { "onMove requires at least one pointer" }

        val focal = focalOf(pointers)
        val panDxPx = focal.first - focalX
        val panDyPx = focal.second - focalY

        var zoomFactor = 1f

        if (pointers.size >= 2) {
            val span = spanOf(pointers, focal)
            decideMode(focal, span)

            if (mode == Mode.ZOOM) {
                zoomFactor = if (zoomBaselineSpan > 0f && span > 0f) span / zoomBaselineSpan else 1f
                zoomBaselineSpan = span
            }
        }

        focalX = focal.first
        focalY = focal.second

        return PanZoomStep(panDxPx, panDyPx, zoomFactor, focal.first, focal.second)
    }

    private fun decideMode(focal: Pair<Float, Float>, span: Float) {
        when (mode) {
            Mode.UNDECIDED -> {
                if (spanChangeRatio(span, undecidedBaselineSpan) > ZOOM_ENTER_RATIO) {
                    mode = Mode.ZOOM
                    zoomBaselineSpan = span
                    return
                }

                val centroidTravelPx = hypot(
                    (focal.first - undecidedBaselineFocalX).toDouble(),
                    (focal.second - undecidedBaselineFocalY).toDouble()
                ).toFloat()

                if (centroidTravelPx > panSlopPx) {
                    mode = Mode.PAN
                    panBreakoutBaselineSpan = span
                }
            }

            Mode.PAN -> {
                if (spanChangeRatio(span, panBreakoutBaselineSpan) > ZOOM_BREAKOUT_RATIO) {
                    mode = Mode.ZOOM
                    zoomBaselineSpan = span
                }
            }

            Mode.ZOOM -> Unit
        }
    }

    private fun spanChangeRatio(span: Float, baselineSpan: Float): Float =
        if (baselineSpan <= MIN_MEANINGFUL_SPAN_PX) 0f else abs(span - baselineSpan) / baselineSpan

    private fun focalOf(pointers: List<PointerPosition>): Pair<Float, Float> {
        var sumX = 0f
        var sumY = 0f
        for (pointer in pointers) {
            sumX += pointer.x
            sumY += pointer.y
        }
        return (sumX / pointers.size) to (sumY / pointers.size)
    }

    /** The average distance from [focal] to each pointer; only [PanZoomStep.zoomFactor] ratios of this value are ever used. */
    private fun spanOf(pointers: List<PointerPosition>, focal: Pair<Float, Float>): Float {
        var sumDistance = 0f
        for (pointer in pointers) {
            sumDistance += hypot((pointer.x - focal.first).toDouble(), (pointer.y - focal.second).toDouble()).toFloat()
        }
        return sumDistance / pointers.size
    }

    companion object {
        /** A span change past this fraction of the gesture-start span commits an undecided gesture to zooming. */
        const val ZOOM_ENTER_RATIO: Float = 0.12f

        /** A span change past this fraction of the span when [Mode.PAN] was decided lets a deliberate pinch break out of a scroll. */
        const val ZOOM_BREAKOUT_RATIO: Float = 0.25f

        /** Spans at or below this are treated as coincident pointers: no ratio is computed against them, to avoid a NaN or infinite result. */
        private const val MIN_MEANINGFUL_SPAN_PX: Float = 0.01f
    }
}
