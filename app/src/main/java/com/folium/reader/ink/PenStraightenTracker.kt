package com.folium.reader.ink

import com.folium.reader.core.ink.SheetPoint

/**
 * One pen stroke's own sheet-space points, collected as they arrive, alongside the
 * [StraightenHoldDetector] deciding whether the pointer drawing them has come to rest at the
 * stroke's end. [InkDrawingSurface] owns one of these per surface, [reset] between strokes.
 */
class PenStraightenTracker(slopPx: Float) {
    private val detector = StraightenHoldDetector(slopPx)
    private val collectedPoints = mutableListOf<SheetPoint>()

    /** Every point collected for the stroke in hand, in drawing order. */
    val points: List<SheetPoint> get() = collectedPoints

    /** Starts collecting a new stroke at [point], discarding whatever a previous stroke left behind. */
    fun onDown(point: SheetPoint, viewXPx: Float, viewYPx: Float, atMillis: Long) {
        collectedPoints.clear()
        collectedPoints += point
        detector.reset()
        detector.onMove(viewXPx, viewYPx, atMillis)
    }

    /** Records one more point of the stroke in hand. */
    /**
     * Records [point] for recognition and feeds the hold detector the same sample in VIEW pixels: the
     * detector's slop and travel thresholds are touch distances on the screen, which a sheet-space
     * coordinate, a fraction of the sheet's width, can never reach.
     */
    fun onMove(point: SheetPoint, viewXPx: Float, viewYPx: Float, atMillis: Long) {
        collectedPoints += point
        detector.onMove(viewXPx, viewYPx, atMillis)
    }

    fun isHeld(nowMillis: Long): Boolean = detector.isHeld(nowMillis)

    fun nextCheckAtMillis(): Long? = detector.nextCheckAtMillis()

    /** Discards every collected point and the detector's own state, ready for a new stroke. */
    fun reset() {
        collectedPoints.clear()
        detector.reset()
    }
}
