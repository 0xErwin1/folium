package com.folium.reader.ink

private const val MIN_IME_PAN_REQUEST_PX = 0.5f

/**
 * Page mode's keyboard pan bookkeeping. A page surface cannot pan itself: it asks its host through a
 * [PanZoomStep], and the host applies it later, so every inset callback that arrives before the page
 * frame moves would otherwise ask for the same pan again. The ledger remembers how much it asked for
 * since the last [reset] and how far the page's top edge has since moved up, and asks only for the
 * part of a need that is neither applied nor still pending.
 */
internal class PageImePanLedger {
    private var baselineOriginYPx: Float? = null
    private var requestedPx = 0f

    /**
     * The step to ask of the host so the content moves up by [neededPx] more than the current frame,
     * whose page top sits at [frameOriginYPx], already shows; `null` when [neededPx] is `null` or the
     * pans already asked for and not yet applied cover it. The step pans up the way a finger dragging
     * up does: a negative [PanZoomStep.panDyPx], with no zoom, about the view's centre.
     */
    fun nextStep(neededPx: Float?, frameOriginYPx: Float, viewWidthPx: Float, viewHeightPx: Float): PanZoomStep? {
        if (neededPx == null) return null

        val baseline = baselineOriginYPx ?: frameOriginYPx.also { baselineOriginYPx = it }
        val appliedPx = baseline - frameOriginYPx
        val pendingPx = (requestedPx - appliedPx).coerceAtLeast(0f)

        val remainingPx = neededPx - pendingPx
        if (remainingPx < MIN_IME_PAN_REQUEST_PX) return null

        requestedPx += remainingPx
        return PanZoomStep(0f, -remainingPx, 1f, viewWidthPx / 2f, viewHeightPx / 2f)
    }

    /** Forgets every earlier request, for when the editor or the keyboard closes. */
    fun reset() {
        baselineOriginYPx = null
        requestedPx = 0f
    }
}
