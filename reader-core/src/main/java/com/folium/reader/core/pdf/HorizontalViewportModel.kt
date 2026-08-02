package com.folium.reader.core.pdf

/** Minimum zoom scale: the whole page fits the viewport, and no panning is possible. */
const val MIN_ZOOM_SCALE = 1.0f

/** Maximum zoom scale a viewport may reach. */
const val MAX_ZOOM_SCALE = 5.0f

/**
 * How far a viewport is currently zoomed into a page, and about which page-space point.
 *
 * [scale] of `1.0` means the whole page fits the viewport; [center] is the page-space point
 * ([PageSpacePoint], normalized `0f..1f` on both axes) currently centered in the viewport. At
 * [MIN_ZOOM_SCALE] the whole page is visible, so [center] can only ever be `(0.5, 0.5)` — see
 * [HorizontalViewportReducer] for how panning is clamped as scale changes.
 */
data class HorizontalViewportZoom(val scale: Float, val center: PageSpacePoint) {
    init {
        require(scale in MIN_ZOOM_SCALE..MAX_ZOOM_SCALE) {
            "scale must be within [$MIN_ZOOM_SCALE, $MAX_ZOOM_SCALE], was $scale"
        }
    }
}

/**
 * Deterministic, engine-neutral state of a single horizontally-paginated reading session: which
 * page is current, how far zoomed in and about which point, whether chrome (toolbars/overlays) is
 * visible, and which render generation is active.
 *
 * This is pure data with no clock, no thread affinity and no dependency on any rendering engine or
 * UI toolkit — see [HorizontalViewportReducer] for how [GestureIntent]s transform it, and
 * [HorizontalViewportPageSelector] for how a given state is turned into a set of page render
 * requests. Nothing in this file, [HorizontalViewportReducer] or [HorizontalViewportPageSelector]
 * ever calls [ByteBoundedPageCache.acquire]: this layer only ever decides *what* should be
 * rendered, never reads a rendered page itself. Reading a cached page and pairing an
 * [ByteBoundedPageCache.acquire] with the matching [CachedPage.release] is the job of whichever
 * consumer displays the result — see [HorizontalViewportRequestCoordinator]'s own doc for where
 * that boundary sits relative to the scheduler.
 */
data class HorizontalViewportState(
    val pageCount: Int,
    val currentPage: Int,
    val zoom: HorizontalViewportZoom,
    val chromeVisible: Boolean,
    val generation: Long
) {
    init {
        require(pageCount >= 0) { "pageCount must be non-negative, was $pageCount" }
        val validRange = if (pageCount == 0) 0..0 else 0 until pageCount
        require(currentPage in validRange) {
            "currentPage must be within bounds for pageCount=$pageCount, was $currentPage"
        }
    }

    companion object {
        /** The state a freshly opened document starts in: first page, unzoomed, chrome visible. */
        fun initial(pageCount: Int): HorizontalViewportState = HorizontalViewportState(
            pageCount = pageCount,
            currentPage = 0,
            zoom = HorizontalViewportZoom(MIN_ZOOM_SCALE, PageSpacePoint(0.5f, 0.5f)),
            chromeVisible = true,
            generation = 0L
        )
    }
}

/**
 * A user gesture translated into intent, as data rather than a UI callback: the reader is
 * responsible for turning a raw touch gesture into one of these, and [HorizontalViewportReducer]
 * is responsible for deciding what it means to [HorizontalViewportState].
 */
sealed class GestureIntent {
    /** Advance to the next page, clamped at the last page. */
    data object PageForward : GestureIntent()

    /** Return to the previous page, clamped at the first page. */
    data object PageBack : GestureIntent()

    /** The result of a fling/swipe or a direct jump, clamped into `0 until pageCount`. */
    data class FlingToPage(val targetPage: Int) : GestureIntent()

    /** Multiply the current zoom scale by [factor], keeping [focal] stable — see [HorizontalViewportReducer]. */
    data class ZoomBy(val factor: Float, val focal: PageSpacePoint) : GestureIntent()

    /**
     * Drag the visible window across a zoomed page by [dx]/[dy], expressed as a fraction of the
     * viewport's own width and height in the direction the content was dragged. Without this a
     * zoomed page has no reachable content outside the region [GestureIntent.ZoomBy]'s focal point
     * happened to land on, since [HorizontalViewportZoom.center] is otherwise only ever moved as a
     * side effect of a scale change.
     */
    data class PanBy(val dx: Float, val dy: Float) : GestureIntent()

    /** Return to [MIN_ZOOM_SCALE], centered on the whole page. */
    data object ResetZoom : GestureIntent()

    data object ToggleChrome : GestureIntent()
    data object ShowChrome : GestureIntent()
    data object HideChrome : GestureIntent()

    /**
     * The viewport's own dimensions changed (rotation, window resize, multi-window). Page and
     * zoom are unaffected, but every raster target derived from the old viewport size is now
     * wrong, so this still rolls the generation — see [HorizontalViewportReducer].
     */
    data object ViewportResized : GestureIntent()
}
