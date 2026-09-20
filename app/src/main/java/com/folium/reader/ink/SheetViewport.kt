package com.folium.reader.ink

import com.folium.reader.core.ink.SheetPoint
import kotlin.math.max

/**
 * A point in the pixel space of the view hosting a sheet: `(0, 0)` at the view's top-left corner,
 * `x` growing right and `y` growing down, the same convention Android delivers touch and layout
 * coordinates in.
 */
data class ViewPoint(val x: Float, val y: Float)

/**
 * Maps between sheet space (see [SheetPoint]) and the pixel space of the view hosting a sheet, and
 * owns the zoom/pan state a drag or pinch gesture manipulates.
 *
 * `zoom == 1` fits the sheet's one-unit-wide column exactly to [viewWidthPx]; [scale] is how many
 * pixels one sheet unit occupies at the current zoom. [topLeft] is the sheet point visible at the
 * view's own top-left pixel.
 *
 * Free of every Android type beyond plain floats, so it is exercised with a plain JVM unit test. The
 * constructor is private: every instance is produced by [initial] or by one of this class's own
 * transition methods, each of which re-clamps [zoom] and [topLeft] before returning, so a caller can
 * never observe or construct a viewport that lets the column drift out of view or the visible sheet
 * y go negative.
 */
@ConsistentCopyVisibility
data class SheetViewport private constructor(
    val viewWidthPx: Float,
    val viewHeightPx: Float,
    val zoom: Float,
    val topLeft: SheetPoint,
    val contentBottom: Float
) {
    /** Pixels one sheet unit occupies at the current [zoom]. */
    val scale: Float get() = zoom * viewWidthPx

    fun sheetToView(point: SheetPoint): ViewPoint =
        ViewPoint((point.x - topLeft.x) * scale, (point.y - topLeft.y) * scale)

    fun viewToSheet(point: ViewPoint): SheetPoint =
        SheetPoint(topLeft.x + point.x / scale, topLeft.y + point.y / scale)

    /** Converts a length in view pixels — a touch slop, an eraser radius — into the same length in sheet units at the current [zoom]. */
    fun lengthToSheetUnits(px: Float): Float = px / scale

    /**
     * Multiplies [zoom] by [factor], clamped to [MIN_ZOOM]..[MAX_ZOOM], keeping the sheet point
     * under [focal] fixed on screen: the same sheet point maps back to [focal] after the zoom,
     * before pan clamping is applied.
     */
    fun zoomedBy(factor: Float, focal: ViewPoint): SheetViewport {
        require(factor > 0f) { "factor must be positive, was $factor" }

        val focalSheetPoint = viewToSheet(focal)
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val newScale = newZoom * viewWidthPx
        val newTopLeft = SheetPoint(
            focalSheetPoint.x - focal.x / newScale,
            focalSheetPoint.y - focal.y / newScale
        )
        return copy(zoom = newZoom, topLeft = newTopLeft).clamped()
    }

    /**
     * Sets [zoom] to [zoom] directly rather than by a multiplicative factor, clamped to
     * [MIN_ZOOM]..[MAX_ZOOM] and keeping the sheet point under [focal] fixed on screen, the same
     * contract [zoomedBy] applies to a factor: a stepper or a fit-to action sets an absolute target
     * rather than accumulating one pinch step at a time.
     */
    fun zoomedTo(zoom: Float, focal: ViewPoint): SheetViewport {
        require(zoom > 0f) { "zoom must be positive, was $zoom" }
        return zoomedBy(zoom / this.zoom, focal)
    }

    /**
     * The viewport at [MIN_ZOOM] — the sheet's nominal width filling [viewWidthPx] — keeping the
     * current top of the view ([topLeft]'s `y`) rather than whatever sheet point sits under some
     * touch focal, since fitting to width is a deliberate reset of the horizontal framing rather than
     * a pinch anchored to a point the user touched.
     */
    fun fittedToWidth(): SheetViewport = copy(zoom = MIN_ZOOM, topLeft = SheetPoint(0f, topLeft.y)).clamped()

    /** Shifts [topLeft] by `(dxPx, dyPx)` screen pixels, then re-clamps it. */
    fun pannedBy(dxPx: Float, dyPx: Float): SheetViewport {
        val newTopLeft = SheetPoint(topLeft.x + dxPx / scale, topLeft.y + dyPx / scale)
        return copy(topLeft = newTopLeft).clamped()
    }

    /** The viewport after the hosting view's size changed, keeping the same sheet-space top-left where the clamp allows it. */
    fun resized(newWidthPx: Float, newHeightPx: Float): SheetViewport {
        require(newWidthPx > 0f) { "newWidthPx must be positive, was $newWidthPx" }
        require(newHeightPx > 0f) { "newHeightPx must be positive, was $newHeightPx" }
        return copy(viewWidthPx = newWidthPx, viewHeightPx = newHeightPx).clamped()
    }

    /** The viewport after the sheet's content grew or shrank, which moves how far down panning is allowed to reach. */
    fun withContentBottom(newContentBottom: Float): SheetViewport =
        copy(contentBottom = max(0f, newContentBottom)).clamped()

    /**
     * Re-clamps [topLeft] so the column never leaves the view horizontally (its whole width stays
     * covered) and the visible top never goes above sheet `y = 0`; the visible bottom may reach one
     * view height past [contentBottom] but no further.
     */
    private fun clamped(): SheetViewport {
        val maxLeftX = max(0f, 1f - viewWidthPx / scale)
        val clampedX = topLeft.x.coerceIn(0f, maxLeftX)

        val maxTopY = max(0f, contentBottom + viewHeightPx / scale)
        val clampedY = topLeft.y.coerceIn(0f, maxTopY)

        return if (clampedX == topLeft.x && clampedY == topLeft.y) this else copy(topLeft = SheetPoint(clampedX, clampedY))
    }

    companion object {
        const val MIN_ZOOM: Float = 1f
        const val MAX_ZOOM: Float = 8f

        /** A viewport at [MIN_ZOOM] showing the sheet's top-left corner, for a view of the given pixel size. */
        fun initial(viewWidthPx: Float, viewHeightPx: Float, contentBottom: Float = 0f): SheetViewport {
            require(viewWidthPx > 0f) { "viewWidthPx must be positive, was $viewWidthPx" }
            require(viewHeightPx > 0f) { "viewHeightPx must be positive, was $viewHeightPx" }
            return SheetViewport(viewWidthPx, viewHeightPx, MIN_ZOOM, SheetPoint(0f, 0f), max(0f, contentBottom)).clamped()
        }
    }
}
