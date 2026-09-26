package com.folium.reader.reader

import com.folium.reader.core.ink.OpenPageInk
import com.folium.reader.core.ink.PageInkStore
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.sequence.SequenceItem
import com.folium.reader.core.sequence.SpreadUnit
import com.folium.reader.ink.PanZoomStep
import kotlin.math.abs

/**
 * The pure decisions behind writing on a book's pages in the reader, kept free of composition so
 * they can be tested without a device.
 */

/**
 * The book pages of [unit] written on live while [writing]: its one page, both pages of a spread, or
 * the page beside a sheet. A sheet cell is never among them, its own pane draws it; nothing is while
 * reading.
 */
internal fun writablePages(unit: SpreadUnit?, writing: Boolean): Set<Int> {
    if (!writing || unit == null) return emptySet()

    return listOfNotNull(unit.left, unit.right)
        .filterIsInstance<SequenceItem.Page>()
        .mapTo(LinkedHashSet()) { it.index }
}

/**
 * Whether the rail and the top bar's history act on a page right now: once any of [pages] has its
 * writer open in [states], so a spread whose second page is still opening is already written on.
 */
internal fun pageInkToolsLive(states: Map<Int, PageInkState>, pages: Set<Int>): Boolean =
    pages.any { page -> states[page] is PageInkState.Live }

/**
 * The reader intents one two-finger [step] on [page]'s drawing surface stands for — see
 * [panZoomIntents] — with the zoom naming [page] as the one to keep, so a pinch on a spread's right
 * page zooms into that page rather than its left neighbour.
 */
internal fun pageSurfaceIntents(step: PanZoomStep, cellWidthPx: Float, cellHeightPx: Float, layout: ViewportLayout, page: Int): List<GestureIntent> =
    panZoomIntents(step, cellWidthPx, cellHeightPx, layout).map { intent ->
        if (intent is GestureIntent.ZoomBy) intent.copy(focusPage = page) else intent
    }

/**
 * The reader intents that bring [page], laid out as [layout], to [targetZoom] — the view panel's
 * zoom, the page's on-screen width over its cell's — about the centre of the cell: nothing when the
 * page is already there or has no width to scale.
 */
internal fun pageZoomIntents(targetZoom: Float, layout: ViewportLayout, page: Int): List<GestureIntent> {
    if (layout.pageWidth <= 0f || targetZoom <= 0f) return emptyList()

    val factor = targetZoom * layout.viewport.widthPx / layout.pageWidth
    if (abs(factor - 1f) < ZOOM_UNCHANGED_TOLERANCE) return emptyList()

    val centreX = layout.viewport.widthPx / 2f
    val centreY = layout.viewport.heightPx / 2f

    return pageSurfaceIntents(PanZoomStep(0f, 0f, factor, centreX, centreY), layout.viewport.widthPx.toFloat(), layout.viewport.heightPx.toFloat(), layout, page)
}

private const val ZOOM_UNCHANGED_TOLERANCE = 1e-3f

/**
 * [PageInkStore]'s ink is bound to a document other than the one open, so it is neither written on
 * nor rebound: writing there would mix strokes laid on two different files.
 */
class PageInkBoundElsewhereException : IllegalStateException("page ink is bound to another document")

/**
 * How a [PageInkLease] opens a page's ink through [store], on its serial worker. The first open
 * binds [store] to the open document's [identity] when it has no binding yet, so ink is always bound
 * before its first write; ink bound to that same identity opens as it is, and ink bound to another
 * document throws [PageInkBoundElsewhereException] without touching the store.
 */
internal fun pageInkOpener(store: PageInkStore, identity: () -> String): (Int) -> OpenPageInk {
    var checked = false

    return { page ->
        if (!checked) {
            val expected = identity()

            when (store.boundIdentity()) {
                null -> store.bind(expected)
                expected -> Unit
                else -> throw PageInkBoundElsewhereException()
            }

            checked = true
        }

        store.open(page)
    }
}
