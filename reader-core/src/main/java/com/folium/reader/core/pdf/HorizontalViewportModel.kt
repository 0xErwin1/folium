package com.folium.reader.core.pdf

/** Minimum zoom scale: the whole page fits the viewport, and no panning is possible. */
const val MIN_ZOOM_SCALE = 1.0f

/** Maximum zoom scale a viewport may reach. */
const val MAX_ZOOM_SCALE = 5.0f

/** The whole of a page's height fits its viewport at [MIN_ZOOM_SCALE] — see [PageFitMode]. */
const val WHOLE_PAGE_VISIBLE = 1.0f

/**
 * How a page is sized against its viewport at [MIN_ZOOM_SCALE].
 *
 * [WIDTH] gives the page the viewport's full width, which is what makes its text as large as the
 * screen allows, and lets the page overflow the viewport vertically when it is the taller shape.
 * [PAGE] fits the whole page instead, so nothing of it is ever off screen.
 *
 * Neither mode ever makes a page wider than its viewport at [MIN_ZOOM_SCALE], which is why only the
 * vertical axis needs [HorizontalViewportState.visibleHeightFraction] to describe how much of the
 * page a fitted viewport can actually reach.
 */
enum class PageFitMode { WIDTH, PAGE }

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
 * page is current, how the page is fitted to its viewport and how far zoomed in beyond that, about
 * which point, whether chrome (toolbars/overlays) is visible, and which render generation is active.
 *
 * [visibleHeightFraction] is how much of the current page's height a viewport at [MIN_ZOOM_SCALE]
 * can show — `1.0` whenever the fitted page is no taller than the viewport, and less than that
 * under [PageFitMode.WIDTH] when it is. It is the one piece of viewport geometry this otherwise
 * purely logical state has to carry, because without it panning could not tell a page that is
 * entirely on screen from one whose ends are off it, and would pin the latter to its middle band.
 * Whoever measures the viewport keeps it current with [GestureIntent.PageFrameMeasured].
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
 *
 * [pagesPerView] of `2` requests a facing-page spread: pages pair from the first page, so
 * [currentPage] is always the even, left-hand page of its pair while the spread is actually
 * showing. A spread only shows at [MIN_ZOOM_SCALE] — zooming in leaves it showing a single page
 * exactly like [pagesPerView] `1` (see [HorizontalViewportReducer.effectivePagesPerView]), which is
 * why this class only enforces the even-left invariant while both conditions hold.
 */
data class HorizontalViewportState(
    val pageCount: Int,
    val currentPage: Int,
    val zoom: HorizontalViewportZoom,
    val chromeVisible: Boolean,
    val generation: Long,
    val fitMode: PageFitMode = PageFitMode.WIDTH,
    val visibleHeightFraction: Float = WHOLE_PAGE_VISIBLE,
    val pagesPerView: Int = 1
) {
    init {
        require(pageCount >= 0) { "pageCount must be non-negative, was $pageCount" }
        val validRange = if (pageCount == 0) 0..0 else 0 until pageCount
        require(currentPage in validRange) {
            "currentPage must be within bounds for pageCount=$pageCount, was $currentPage"
        }
        require(visibleHeightFraction > 0f && visibleHeightFraction <= WHOLE_PAGE_VISIBLE) {
            "visibleHeightFraction must describe part of a page, was $visibleHeightFraction"
        }
        require(pagesPerView == 1 || pagesPerView == 2) {
            "pagesPerView must be 1 or 2, was $pagesPerView"
        }
        require(pagesPerView != 2 || zoom.scale != MIN_ZOOM_SCALE || currentPage % 2 == 0) {
            "currentPage must be the spread's left page while a spread is fitted, was $currentPage"
        }
    }

    companion object {
        /**
         * The state a freshly opened document starts in: fitted to width, chrome visible, seeded
         * at [currentPage] (page 1 by default) and [pagesPerView] (single page by default).
         * [currentPage] is rejected, not coerced, by this class's own `init` when it is out of
         * range, or when it is not the even left page of its pair while [pagesPerView] is `2` —
         * restoring a stored page that has since gone out of range, or pairing it to its spread's
         * left page, is the caller's responsibility before calling this.
         */
        fun initial(pageCount: Int, currentPage: Int = 0, pagesPerView: Int = 1): HorizontalViewportState =
            HorizontalViewportState(
                pageCount = pageCount,
                currentPage = currentPage,
                zoom = HorizontalViewportZoom(MIN_ZOOM_SCALE, PageSpacePoint(0.5f, 0.5f)),
                chromeVisible = true,
                generation = 0L,
                pagesPerView = pagesPerView
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

    /**
     * Multiply the current zoom scale by [factor], keeping [focal] stable — see
     * [HorizontalViewportReducer]. [focusPage] names which of a fitted spread's two visible pages
     * [focal] belongs to, and is required exactly when this zoom is the one that leaves a spread
     * for a single zoomed page (see [HorizontalViewportReducer.effectivePagesPerView]); it is
     * ignored once already zoomed in, and whenever no spread is showing.
     */
    data class ZoomBy(val factor: Float, val focal: PageSpacePoint, val focusPage: Int? = null) : GestureIntent()

    /**
     * Drag the visible window across a zoomed page by [dx]/[dy], expressed as a fraction of the
     * viewport's own width and height in the direction the content was dragged. Without this a
     * zoomed page has no reachable content outside the region [GestureIntent.ZoomBy]'s focal point
     * happened to land on, since [HorizontalViewportZoom.center] is otherwise only ever moved as a
     * side effect of a scale change.
     */
    data class PanBy(val dx: Float, val dy: Float) : GestureIntent()

    /** Return to [MIN_ZOOM_SCALE], showing as much of the page as the current [PageFitMode] fits. */
    data object ResetZoom : GestureIntent()

    /**
     * Fit pages to the viewport differently from now on. The scale a page is drawn at is relative
     * to its fit, so changing the fit changes every raster target and returns to [MIN_ZOOM_SCALE].
     */
    data class SetFitMode(val fitMode: PageFitMode) : GestureIntent()

    /**
     * How much of the current page's height a fitted viewport can show, as a fraction in
     * `0f exclusive .. 1f`. Whoever owns the viewport measures this and reports it whenever the
     * viewport, the fit mode or the current page's shape changes — see
     * [HorizontalViewportState.visibleHeightFraction].
     */
    data class PageFrameMeasured(val visibleHeightFraction: Float) : GestureIntent()

    data object ToggleChrome : GestureIntent()
    data object ShowChrome : GestureIntent()
    data object HideChrome : GestureIntent()

    /**
     * Request a facing-page spread ([pagesPerView] `2`) or a single page ([pagesPerView] `1`) from
     * now on. Whoever measures the page area and decides it qualifies for a spread (see
     * `FoliumWidthClass.EXPANDED_FROM`) dispatches this; the reducer only normalizes [currentPage]
     * to its spread's left page when the change actually takes hold at [MIN_ZOOM_SCALE].
     */
    data class SetPagesPerView(val pagesPerView: Int) : GestureIntent()

    /**
     * The viewport's own dimensions changed (rotation, window resize, multi-window). Page and
     * zoom are unaffected, but every raster target derived from the old viewport size is now
     * wrong, so this still rolls the generation — see [HorizontalViewportReducer].
     */
    data object ViewportResized : GestureIntent()
}
