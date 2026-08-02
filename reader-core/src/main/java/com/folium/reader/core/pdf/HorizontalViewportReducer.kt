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
        GestureIntent.PageForward -> navigateTo(state, state.currentPage + 1)
        GestureIntent.PageBack -> navigateTo(state, state.currentPage - 1)
        is GestureIntent.FlingToPage -> navigateTo(state, intent.targetPage)
        is GestureIntent.ZoomBy -> applyZoom(state, intent.factor, intent.focal)
        is GestureIntent.PanBy -> applyPan(state, intent.dx, intent.dy)
        is GestureIntent.SetFitMode -> applyFitMode(state, intent.fitMode)
        is GestureIntent.PageFrameMeasured -> applyPageFrame(state, intent.visibleHeightFraction)
        GestureIntent.ResetZoom -> resetZoom(state)
        GestureIntent.ToggleChrome -> state.copy(chromeVisible = !state.chromeVisible)
        GestureIntent.ShowChrome -> state.copy(chromeVisible = true)
        GestureIntent.HideChrome -> state.copy(chromeVisible = false)
        GestureIntent.ViewportResized -> state.copy(generation = state.generation + 1)
    }

    /**
     * A fitted page is turned to at its own top rather than wherever the previous page was being
     * read, since under [PageFitMode.WIDTH] a page taller than the viewport would otherwise open
     * part-way down. A page the reader has deliberately zoomed into keeps its zoom instead: that is
     * a choice about the document, not a position within one page.
     */
    private fun navigateTo(state: HorizontalViewportState, target: Int): HorizontalViewportState {
        if (state.pageCount == 0) return state

        val clamped = target.coerceIn(0, state.pageCount - 1)
        if (clamped == state.currentPage) return state

        val zoom = if (state.zoom.scale == MIN_ZOOM_SCALE) fittedZoom(state.visibleHeightFraction) else state.zoom
        return state.copy(currentPage = clamped, zoom = zoom, generation = state.generation + 1)
    }

    private fun applyFitMode(state: HorizontalViewportState, fitMode: PageFitMode): HorizontalViewportState {
        if (fitMode == state.fitMode) return state

        return state.copy(
            fitMode = fitMode,
            zoom = fittedZoom(state.visibleHeightFraction),
            generation = state.generation + 1
        )
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
     */
    private fun applyZoom(state: HorizontalViewportState, factor: Float, focal: PageSpacePoint): HorizontalViewportState {
        require(factor > 0f) { "zoom factor must be positive, was $factor" }

        val newScale = (state.zoom.scale * factor).coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE)
        val ratio = state.zoom.scale / newScale
        val rawCenterX = focal.x + (state.zoom.center.x - focal.x) * ratio
        val rawCenterY = focal.y + (state.zoom.center.y - focal.y) * ratio
        val newCenter = clampCenter(rawCenterX, rawCenterY, newScale, state.visibleHeightFraction)
        val newZoom = HorizontalViewportZoom(newScale, newCenter)

        if (newZoom == state.zoom) return state
        return state.copy(zoom = newZoom, generation = state.generation + 1)
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
        if (state.zoom == fitted) return state
        return state.copy(zoom = fitted, generation = state.generation + 1)
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
 * current page is [RenderPriority.VISIBLE], its immediate neighbors are [RenderPriority.NEAR], and
 * a wider window beyond that is [RenderPriority.PREFETCH]. This never produces
 * [RenderPriority.OCR] requests; that priority exists for a different, opportunistic consumer.
 */
object HorizontalViewportPageSelector {
    private const val NEAR_RADIUS = 1
    private const val PREFETCH_RADIUS = 2

    fun select(state: HorizontalViewportState): List<ViewportPageRequest> {
        if (state.pageCount == 0) return emptyList()

        val priorityByPage = LinkedHashMap<Int, RenderPriority>()
        priorityByPage[state.currentPage] = RenderPriority.VISIBLE

        for (offset in 1..NEAR_RADIUS) {
            addIfInBounds(priorityByPage, state, state.currentPage - offset, RenderPriority.NEAR)
            addIfInBounds(priorityByPage, state, state.currentPage + offset, RenderPriority.NEAR)
        }
        for (offset in (NEAR_RADIUS + 1)..(NEAR_RADIUS + PREFETCH_RADIUS)) {
            addIfInBounds(priorityByPage, state, state.currentPage - offset, RenderPriority.PREFETCH)
            addIfInBounds(priorityByPage, state, state.currentPage + offset, RenderPriority.PREFETCH)
        }

        return priorityByPage.map { (pageIndex, priority) -> ViewportPageRequest(pageIndex, priority, state.generation) }
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
