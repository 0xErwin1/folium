package com.folium.reader.core.pdf

/**
 * Pure state machine turning a [GestureIntent] into the next [HorizontalViewportState].
 *
 * [HorizontalViewportState.generation] rolls exactly when something that affects what should be
 * rendered actually changes — a page navigation that lands on a different page, a zoom that
 * actually changes scale or center, or a viewport resize. It deliberately does not roll when an
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
        GestureIntent.ResetZoom -> resetZoom(state)
        GestureIntent.ToggleChrome -> state.copy(chromeVisible = !state.chromeVisible)
        GestureIntent.ShowChrome -> state.copy(chromeVisible = true)
        GestureIntent.HideChrome -> state.copy(chromeVisible = false)
        GestureIntent.ViewportResized -> state.copy(generation = state.generation + 1)
    }

    private fun navigateTo(state: HorizontalViewportState, target: Int): HorizontalViewportState {
        if (state.pageCount == 0) return state

        val clamped = target.coerceIn(0, state.pageCount - 1)
        if (clamped == state.currentPage) return state

        return state.copy(currentPage = clamped, generation = state.generation + 1)
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
        val newCenter = clampCenter(rawCenterX, rawCenterY, newScale)
        val newZoom = HorizontalViewportZoom(newScale, newCenter)

        if (newZoom == state.zoom) return state
        return state.copy(zoom = newZoom, generation = state.generation + 1)
    }

    private fun resetZoom(state: HorizontalViewportState): HorizontalViewportState {
        val defaultZoom = HorizontalViewportZoom(MIN_ZOOM_SCALE, PageSpacePoint(0.5f, 0.5f))
        if (state.zoom == defaultZoom) return state
        return state.copy(zoom = defaultZoom, generation = state.generation + 1)
    }

    private fun clampCenter(x: Float, y: Float, scale: Float): PageSpacePoint {
        val halfExtent = 0.5f / scale
        return PageSpacePoint(x.coerceIn(halfExtent, 1f - halfExtent), y.coerceIn(halfExtent, 1f - halfExtent))
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
