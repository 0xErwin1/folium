package com.folium.reader.ink

private const val MIN_IME_PAN_REQUEST_PX = 0.5f

/**
 * Page mode's keyboard pan bookkeeping. A page surface cannot pan itself: it asks its host through a
 * [PanZoomStep], and the host applies it later, so every inset callback that arrives before the page
 * frame moves would otherwise ask for the same pan again. The ledger keeps how much it asked for that
 * the frame has not yet shown, and asks only for the part of a need that is not still pending.
 *
 * A request counts as applied only when the page's top edge moves up, and only up to what is still
 * pending: a move down, or the part of a move up beyond every pending request, is the user panning,
 * which never settles a request, so a user pan while the keyboard is open cannot cancel a real need.
 */
internal class PageImePanLedger {
    private var lastFrameOriginYPx: Float? = null
    private var pendingPx = 0f

    /**
     * The step to ask of the host so the content moves up by [neededPx] more than the current frame,
     * whose page top sits at [frameOriginYPx], already shows; `null` when [neededPx] is `null` or the
     * pans already asked for and not yet applied cover it. The step pans up the way a finger dragging
     * up does: a negative [PanZoomStep.panDyPx], with no zoom, about the view's centre.
     */
    fun nextStep(neededPx: Float?, frameOriginYPx: Float, viewWidthPx: Float, viewHeightPx: Float): PanZoomStep? {
        if (neededPx == null) return null

        val movedUpPx = (lastFrameOriginYPx ?: frameOriginYPx) - frameOriginYPx
        lastFrameOriginYPx = frameOriginYPx
        pendingPx -= movedUpPx.coerceIn(0f, pendingPx)

        val remainingPx = neededPx - pendingPx
        if (remainingPx < MIN_IME_PAN_REQUEST_PX) return null

        pendingPx += remainingPx
        return PanZoomStep(0f, -remainingPx, 1f, viewWidthPx / 2f, viewHeightPx / 2f)
    }

    /** Forgets every earlier request, for when the editor or the keyboard closes. */
    fun reset() {
        lastFrameOriginYPx = null
        pendingPx = 0f
    }
}
