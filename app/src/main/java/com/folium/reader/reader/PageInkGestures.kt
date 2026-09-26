package com.folium.reader.reader

import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.ink.PanZoomStep

/**
 * The reader intents one two-finger [step] on a page's ink surface stands for, so the reader rather
 * than the ink layer zooms and pans the page: a [GestureIntent.ZoomBy] about the page point under the
 * step's focal (clamped onto the page, as a pinch on the page itself is), then a [GestureIntent.PanBy]
 * in fractions of the [cellWidthPx] by [cellHeightPx] cell — the same order and units the reader's own
 * pinch dispatches. [layout] is the page's layout before this step; a step that neither zooms nor pans
 * emits nothing.
 */
fun panZoomIntents(step: PanZoomStep, cellWidthPx: Float, cellHeightPx: Float, layout: ViewportLayout): List<GestureIntent> {
    val intents = mutableListOf<GestureIntent>()

    if (step.zoomFactor != 1f) {
        val focal = ReaderGeometry.viewportToPage(layout, ViewportPoint(step.focalX, step.focalY), clampToPage = true)
        if (focal != null) intents += GestureIntent.ZoomBy(step.zoomFactor, focal)
    }

    if (step.panDxPx != 0f || step.panDyPx != 0f) {
        intents += GestureIntent.PanBy(step.panDxPx / cellWidthPx, step.panDyPx / cellHeightPx)
    }

    return intents
}
