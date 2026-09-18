package com.folium.reader.core.pdf

/**
 * Pure state machine turning a [GestureIntent] into the next [HorizontalViewportState].
 *
 * [HorizontalViewportState.generation] rolls exactly when something that affects what should be
 * rendered actually changes — a page navigation that lands on a different page, a zoom that
 * actually changes scale or center, a viewport resize, a change of [PageFitMode], or a re-measured
 * page frame. The last two matter as much as the others: both change the pixel size and the region
 * every page is requested at, so without rolling, rasters cut for the previous fit would be drawn
 * against the new one. It deliberately does not roll when an
 * intent is a no-op (e.g. [GestureIntent.PageForward] at the last page) or only affects UI chrome
 * visibility, which never affects a raster's content. A rolled generation is how a downstream
 * consumer (see [HorizontalViewportRequestCoordinator]) knows to invalidate in-flight renders
 * against a [ViewportScheduler]; rolling it needlessly would only cost superseded work.
 */
object HorizontalViewportReducer {

    fun reduce(state: HorizontalViewportState, intent: GestureIntent): HorizontalViewportState = when (intent) {
        GestureIntent.PageForward -> navigateTo(state, state.currentPage + effectivePagesPerView(state))
        GestureIntent.PageBack -> navigateTo(state, state.currentPage - effectivePagesPerView(state))
        is GestureIntent.FlingToPage -> navigateTo(state, intent.targetPage)
        is GestureIntent.ZoomBy -> applyZoom(state, intent.factor, intent.focal, intent.focusPage)
        is GestureIntent.PanBy -> applyPan(state, intent.dx, intent.dy)
        is GestureIntent.SetFitMode -> applyFitMode(state, intent.fitMode)
        is GestureIntent.PageFrameMeasured -> applyPageFrame(state, intent.visibleHeightFraction)
        GestureIntent.ResetZoom -> resetZoom(state)
        GestureIntent.ToggleChrome -> state.copy(chromeVisible = !state.chromeVisible)
        GestureIntent.ShowChrome -> state.copy(chromeVisible = true)
        GestureIntent.HideChrome -> state.copy(chromeVisible = false)
        GestureIntent.ViewportResized -> state.copy(generation = state.generation + 1)
        is GestureIntent.SetPagesPerView -> applyPagesPerView(state, intent.pagesPerView)
    }

    /**
     * How many pages a page turn or a render window should treat as one unit right now: `2` only
     * while a spread is both requested and actually fitted at [MIN_ZOOM_SCALE]. Zooming in leaves
     * [HorizontalViewportState.pagesPerView] alone but turns this back to `1`, which is what lets a
     * zoomed page behave exactly like single-page mode without a second state to track.
     */
    fun effectivePagesPerView(state: HorizontalViewportState): Int =
        if (state.pagesPerView == 2 && state.zoom.scale == MIN_ZOOM_SCALE) 2 else 1

    /** The even, left-hand page of the pair `pageIndex` belongs to — see [HorizontalViewportState.pagesPerView]. */
    private fun pairLeft(pageIndex: Int): Int = pageIndex - (pageIndex % 2)

    private fun pairedLeftPage(state: HorizontalViewportState): Int =
        pairLeft(state.currentPage).coerceIn(0, maxOf(state.pageCount - 1, 0))

    /**
     * A fitted page is turned to at its own top rather than wherever the previous page was being
     * read, since under [PageFitMode.WIDTH] a page taller than the viewport would otherwise open
     * part-way down. A page the reader has deliberately zoomed into keeps its zoom instead: that is
     * a choice about the document, not a position within one page.
     */
    private fun navigateTo(state: HorizontalViewportState, target: Int): HorizontalViewportState {
        if (state.pageCount == 0) return state

        val clamped = target.coerceIn(0, state.pageCount - 1)
        val normalized = if (effectivePagesPerView(state) == 2) pairLeft(clamped) else clamped
        if (normalized == state.currentPage) return state

        val zoom = if (state.zoom.scale == MIN_ZOOM_SCALE) fittedZoom(state.visibleHeightFraction) else state.zoom
        return state.copy(currentPage = normalized, zoom = zoom, generation = state.generation + 1)
    }

    private fun applyFitMode(state: HorizontalViewportState, fitMode: PageFitMode): HorizontalViewportState {
        if (fitMode == state.fitMode) return state

        val pairedPage = if (state.pagesPerView == 2) pairedLeftPage(state) else state.currentPage
        return state.copy(
            currentPage = pairedPage,
            fitMode = fitMode,
            zoom = fittedZoom(state.visibleHeightFraction),
            generation = state.generation + 1
        )
    }

    /**
     * Turns a spread on or off from now on. The page area's measurement decides whether a spread
     * currently qualifies (see `FoliumWidthClass.EXPANDED_FROM`); this only ever changes what the
     * viewport asks for. Landing back on a spread re-pairs [HorizontalViewportState.currentPage] to
     * its left page immediately, so a caller that reads the state right after this call already
     * sees the invariant [HorizontalViewportState] enforces.
     */
    private fun applyPagesPerView(state: HorizontalViewportState, pagesPerView: Int): HorizontalViewportState {
        require(pagesPerView == 1 || pagesPerView == 2) {
            "pagesPerView must be 1 or 2, was $pagesPerView"
        }
        if (pagesPerView == state.pagesPerView) return state

        val pairedPage = if (pagesPerView == 2 && state.zoom.scale == MIN_ZOOM_SCALE) {
            pairedLeftPage(state)
        } else {
            state.currentPage
        }
        return state.copy(pagesPerView = pagesPerView, currentPage = pairedPage, generation = state.generation + 1)
    }

    /**
     * Records how much of the page a fitted viewport can reach. This changes what every request
     * covers, so it rolls the generation; and at the fitted scale it re-anchors to the top of the
     * page, because a page whose height has just been re-measured is one the reader has not started
     * reading down yet.
     */
    private fun applyPageFrame(state: HorizontalViewportState, fraction: Float): HorizontalViewportState {
        require(fraction > 0f && fraction <= WHOLE_PAGE_VISIBLE) {
            "visibleHeightFraction must describe part of a page, was $fraction"
        }
        if (fraction == state.visibleHeightFraction) return state

        val zoom = if (state.zoom.scale == MIN_ZOOM_SCALE) {
            fittedZoom(fraction)
        } else {
            state.zoom.copy(center = clampCenter(state.zoom.center.x, state.zoom.center.y, state.zoom.scale, fraction))
        }

        return state.copy(visibleHeightFraction = fraction, zoom = zoom, generation = state.generation + 1)
    }

    /**
     * Zooms by [factor] about [focal] (a page-space point), keeping [focal] stable: the page-space
     * point under the gesture's focal position before the zoom is, up to floating-point precision
     * and clamping at the bounds, the same page-space point under it afterwards. Derivation: if
     * `center` is the page-space point mapped to the viewport's own center, requiring that [focal]
     * maps to the same effective viewport position both before and after a scale change from
     * `oldScale` to `newScale` yields `center' = focal + (center - focal) * (oldScale / newScale)`.
     * The result is then clamped so the visible viewport rect never extends past the page bounds.
     *
     * Zooming in out of a fitted spread first collapses the spread onto [focusPage] (the page
     * [focal] was measured against), so every line below applies the existing single-page transform
     * unmodified; zooming back out to [MIN_ZOOM_SCALE] out of a spread request re-pairs the result
     * to that page's spread, which is how the spread is described as "returning" in
     * [HorizontalViewportState.pagesPerView]'s own doc.
     */
    private fun applyZoom(
        state: HorizontalViewportState,
        factor: Float,
        focal: PageSpacePoint,
        focusPage: Int?
    ): HorizontalViewportState {
        require(factor > 0f) { "zoom factor must be positive, was $factor" }

        val newScale = (state.zoom.scale * factor).coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE)
        val leavingSpreadForAZoomedPage = effectivePagesPerView(state) == 2 && newScale > MIN_ZOOM_SCALE

        val basePage = if (leavingSpreadForAZoomedPage) {
            val page = requireNotNull(focusPage) {
                "focusPage is required to zoom in out of a fitted spread"
            }
            require(page == state.currentPage || page == state.currentPage + 1) {
                "focusPage must be one of the spread's two visible pages, was $page"
            }
            page
        } else {
            state.currentPage
        }

        val ratio = state.zoom.scale / newScale
        val rawCenterX = focal.x + (state.zoom.center.x - focal.x) * ratio
        val rawCenterY = focal.y + (state.zoom.center.y - focal.y) * ratio
        val newCenter = clampCenter(rawCenterX, rawCenterY, newScale, state.visibleHeightFraction)
        val newZoom = HorizontalViewportZoom(newScale, newCenter)

        val pairedPage = if (state.pagesPerView == 2 && newScale == MIN_ZOOM_SCALE) {
            pairLeft(basePage).coerceIn(0, maxOf(state.pageCount - 1, 0))
        } else {
            basePage
        }

        if (newZoom == state.zoom && pairedPage == state.currentPage) return state
        return state.copy(currentPage = pairedPage, zoom = newZoom, generation = state.generation + 1)
    }

    /**
     * Drags the visible window by [dx]/[dy] viewport fractions. A viewport fraction covers
     * `1 / scale` of the page across, and [HorizontalViewportState.visibleHeightFraction] as much
     * of it down, so the center moves by that much less the further in the page is zoomed, which is
     * what makes a drag track the content under the finger at every scale and in either fit. The
     * sign is inverted because dragging the content one way moves the window the other, and the
     * result is clamped by the same [clampCenter] a zoom uses, so panning can never expose anything
     * outside the page and is a no-op wherever the page is already entirely on screen.
     */
    private fun applyPan(state: HorizontalViewportState, dx: Float, dy: Float): HorizontalViewportState {
        val scale = state.zoom.scale
        val newCenter = clampCenter(
            state.zoom.center.x - dx / scale,
            state.zoom.center.y - dy * state.visibleHeightFraction / scale,
            scale,
            state.visibleHeightFraction
        )

        if (newCenter == state.zoom.center) return state
        return state.copy(zoom = state.zoom.copy(center = newCenter), generation = state.generation + 1)
    }

    private fun resetZoom(state: HorizontalViewportState): HorizontalViewportState {
        val fitted = fittedZoom(state.visibleHeightFraction)
        val pairedPage = if (state.pagesPerView == 2) pairedLeftPage(state) else state.currentPage
        if (state.zoom == fitted && pairedPage == state.currentPage) return state
        return state.copy(currentPage = pairedPage, zoom = fitted, generation = state.generation + 1)
    }

    /** [MIN_ZOOM_SCALE], anchored at the top of the page rather than at its middle — see [navigateTo]. */
    private fun fittedZoom(visibleHeightFraction: Float): HorizontalViewportZoom =
        HorizontalViewportZoom(MIN_ZOOM_SCALE, PageSpacePoint(0.5f, visibleHeightFraction / 2f))

    private fun clampCenter(x: Float, y: Float, scale: Float, visibleHeightFraction: Float): PageSpacePoint {
        val halfWidth = 0.5f / scale
        val halfHeight = 0.5f * visibleHeightFraction / scale
        return PageSpacePoint(
            x.coerceIn(halfWidth, 1f - halfWidth),
            y.coerceIn(halfHeight, 1f - halfHeight)
        )
    }
}

/** A single page render request implied by a [HorizontalViewportState], tagged with its generation. */
data class ViewportPageRequest(val pageIndex: Int, val priority: RenderPriority, val generation: Long)

/**
 * Maps a [HorizontalViewportState] to the set of pages that should be rendered and at what
 * priority, using the same [RenderPriority] classes [ViewportScheduler] already understands: the
 * currently visible page (or, while a spread is fitted, both of its pages) is
 * [RenderPriority.VISIBLE], the next unit each way is [RenderPriority.NEAR], and a wider window
 * beyond that is [RenderPriority.PREFETCH]. While a spread is fitted, a "unit" is the spread itself
 * — both of a neighboring spread's pages share one priority, since a turn there shows both at once
 * — which is what it means for prefetch to reach the adjacent spreads rather than the adjacent
 * pages. This never produces [RenderPriority.OCR] requests; that priority exists for a different,
 * opportunistic consumer.
 */
object HorizontalViewportPageSelector {
    private const val NEAR_RADIUS = 1
    private const val PREFETCH_RADIUS = 2

    /**
     * How many pages of each class a full single-page window holds. Published because what the
     * window costs is decided elsewhere, by whoever has to fit it in a memory budget, and a count
     * copied into that decision is a count that drifts away from this one. A fitted spread can ask
     * for up to twice as many pages per class; each is fitted to half the page area, so a budget
     * built on these counts still bounds a spread's actual cost.
     */
    const val NEAR_PAGES = NEAR_RADIUS * 2
    const val PREFETCH_PAGES = PREFETCH_RADIUS * 2
    const val WINDOW_PAGES = 1 + NEAR_PAGES + PREFETCH_PAGES

    fun select(state: HorizontalViewportState): List<ViewportPageRequest> {
        if (state.pageCount == 0) return emptyList()

        val spreadFitted = HorizontalViewportReducer.effectivePagesPerView(state) == 2
        val unitWidth = if (spreadFitted) 2 else 1
        val leftmost = state.currentPage

        val priorityByPage = LinkedHashMap<Int, RenderPriority>()
        addUnit(priorityByPage, state, leftmost, unitWidth, RenderPriority.VISIBLE)

        for (unitOffset in 1..NEAR_RADIUS) {
            addUnit(priorityByPage, state, leftmost - unitOffset * unitWidth, unitWidth, RenderPriority.NEAR)
            addUnit(priorityByPage, state, leftmost + unitOffset * unitWidth, unitWidth, RenderPriority.NEAR)
        }
        for (unitOffset in (NEAR_RADIUS + 1)..(NEAR_RADIUS + PREFETCH_RADIUS)) {
            addUnit(priorityByPage, state, leftmost - unitOffset * unitWidth, unitWidth, RenderPriority.PREFETCH)
            addUnit(priorityByPage, state, leftmost + unitOffset * unitWidth, unitWidth, RenderPriority.PREFETCH)
        }

        return priorityByPage.map { (pageIndex, priority) -> ViewportPageRequest(pageIndex, priority, state.generation) }
    }

    private fun addUnit(
        priorityByPage: LinkedHashMap<Int, RenderPriority>,
        state: HorizontalViewportState,
        unitLeftPage: Int,
        unitWidth: Int,
        priority: RenderPriority
    ) {
        addIfInBounds(priorityByPage, state, unitLeftPage, priority)
        if (unitWidth == 2) addIfInBounds(priorityByPage, state, unitLeftPage + 1, priority)
    }

    private fun addIfInBounds(
        priorityByPage: LinkedHashMap<Int, RenderPriority>,
        state: HorizontalViewportState,
        pageIndex: Int,
        priority: RenderPriority
    ) {
        if (pageIndex in 0 until state.pageCount && pageIndex !in priorityByPage) {
            priorityByPage[pageIndex] = priority
        }
    }
}
