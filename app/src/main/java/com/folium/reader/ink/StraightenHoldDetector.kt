package com.folium.reader.ink

import kotlin.math.hypot

/** [StraightenHoldDetector.holdMillis]'s own default: how long a pointer must sit still before it reads as a hold. */
private const val DEFAULT_HOLD_MILLIS: Long = 600L

/** [StraightenHoldDetector.minTravelPx]'s own default, as a multiple of the slop: a hold right at the down point is a tap, not the end of a stroke. */
private const val MIN_TRAVEL_SLOP_MULTIPLIER: Float = 4f

/**
 * Detects a pointer coming to rest at the end of a stroke: it must first travel at least
 * [minTravelPx] from where the stroke began, then stay within [slopPx] of wherever it currently is
 * for at least [holdMillis], before [isHeld] reports true. Fires at most once per stroke; call
 * [reset] to use this detector for a new one.
 *
 * Pure and Android-free: every timestamp is an opaque `Long` the caller supplies, normally
 * `MotionEvent.getEventTime()`/`SystemClock.uptimeMillis()`'s own millisecond time base.
 */
class StraightenHoldDetector(
    private val slopPx: Float,
    private val holdMillis: Long = DEFAULT_HOLD_MILLIS,
    private val minTravelPx: Float = slopPx * MIN_TRAVEL_SLOP_MULTIPLIER
) {
    private var started = false
    private var fired = false

    private var lastX = 0f
    private var lastY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorAtMillis = 0L
    private var traveledPx = 0f

    /**
     * Records the pointer at [x], [y] as of [uptimeMillis]. The first call after construction or
     * [reset] seeds the stroke's own start and anchor rather than measuring anything; every call
     * after that moves the anchor — and its own clock — only once the pointer has strayed more than
     * [slopPx] from it, so jitter inside the slop never postpones a hold that is already underway.
     */
    fun onMove(x: Float, y: Float, uptimeMillis: Long) {
        if (fired) return

        if (!started) {
            started = true
            lastX = x
            lastY = y
            anchorX = x
            anchorY = y
            anchorAtMillis = uptimeMillis
            return
        }

        traveledPx += hypot(x - lastX, y - lastY)
        lastX = x
        lastY = y

        if (hypot(x - anchorX, y - anchorY) > slopPx) {
            anchorX = x
            anchorY = y
            anchorAtMillis = uptimeMillis
        }
    }

    /**
     * Whether the pointer has been held, as of [nowMillis]: enough travel since the stroke began, and
     * the anchor's own clock reaching [holdMillis]. Latches once true — a later call always answers
     * `false` for the rest of this stroke, whatever [nowMillis] is.
     */
    fun isHeld(nowMillis: Long): Boolean {
        if (fired || !started) return false
        if (traveledPx < minTravelPx) return false
        if (nowMillis - anchorAtMillis < holdMillis) return false

        fired = true
        return true
    }

    /** The pointer's own last recorded position, in view pixels; only meaningful once [onMove] has been called since [reset]. */
    fun lastPositionPx(): Pair<Float, Float> = lastX to lastY

    /** When [isHeld] would next have a chance of answering true, or `null` when it never will again. */
    fun nextCheckAtMillis(): Long? {
        if (fired || !started) return null
        return anchorAtMillis + holdMillis
    }

    /** Clears every recorded point and the latch, so this detector can be reused for a new stroke. */
    fun reset() {
        started = false
        fired = false
        traveledPx = 0f
    }
}
