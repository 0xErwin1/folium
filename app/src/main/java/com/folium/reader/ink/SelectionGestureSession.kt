package com.folium.reader.ink

import com.folium.reader.core.ink.SheetPoint
import kotlin.math.hypot

/**
 * How far apart, in view pixels, consecutive points of a live lasso path are kept: closer motion is
 * dropped rather than fed into the path, so a slow, jittery drag never builds an oversized polygon.
 */
private const val MIN_LASSO_POINT_SPACING_PX: Float = 4f

/**
 * One [InkSurfaceTool.SELECT] gesture, from the pointer's own down point to wherever it is now. Pure
 * and Android-free, so [InkDrawingSurface] can drive it on the UI thread without any of it touching a
 * view, the same contract [PenStraightenTracker] and [PartialEraseSession] already follow.
 *
 * [isDragging] latches true forever, the same contract [StraightenResizeGate] follows, once the
 * pointer has travelled past [slopPx] in view pixels from [onDown]'s own point: merely holding still,
 * or trembling within that slop, must never turn a tap into a drag. [InkDrawingSurface] reads it at
 * lift time to decide whether the gesture is a tap regardless of [PenSelectMode].
 */
class SelectionGestureSession(private val slopPx: Float) {
    private var downSheetPoint: SheetPoint? = null
    private var downViewX = 0f
    private var downViewY = 0f
    private var currentSheetPoint: SheetPoint? = null

    private val lassoSheetPoints = mutableListOf<SheetPoint>()
    private var lastLassoViewX = 0f
    private var lastLassoViewY = 0f

    var isDragging: Boolean = false
        private set

    /** Starts the gesture at [point], discarding whatever an earlier gesture left behind. */
    fun onDown(point: SheetPoint, viewX: Float, viewY: Float) {
        downSheetPoint = point
        downViewX = viewX
        downViewY = viewY
        currentSheetPoint = point
        isDragging = false

        lassoSheetPoints.clear()
        lassoSheetPoints += point
        lastLassoViewX = viewX
        lastLassoViewY = viewY
    }

    /**
     * Records the pointer having moved to [point]. Once [isDragging] latches true, [point] also
     * grows [lassoPoints] whenever it is at least [MIN_LASSO_POINT_SPACING_PX] from the last point
     * kept, decimating a dense move stream down to a path worth tracing on screen and worth handing
     * to [com.folium.reader.core.ink.selectByLasso].
     */
    fun onMove(point: SheetPoint, viewX: Float, viewY: Float) {
        currentSheetPoint = point
        if (!isDragging) isDragging = hypot(viewX - downViewX, viewY - downViewY) > slopPx
        if (!isDragging) return

        if (hypot(viewX - lastLassoViewX, viewY - lastLassoViewY) >= MIN_LASSO_POINT_SPACING_PX) {
            lassoSheetPoints += point
            lastLassoViewX = viewX
            lastLassoViewY = viewY
        }
    }

    /** The pointer's own down point: a tap's own point, and one corner of a box selection. */
    val downPoint: SheetPoint? get() = downSheetPoint

    /** The pointer's own most recently recorded point: the other corner of a box selection. */
    val currentPoint: SheetPoint? get() = currentSheetPoint

    /** The lasso's own points collected so far, decimated by [MIN_LASSO_POINT_SPACING_PX]; empty until [onDown]. */
    val lassoPoints: List<SheetPoint> get() = lassoSheetPoints
}
