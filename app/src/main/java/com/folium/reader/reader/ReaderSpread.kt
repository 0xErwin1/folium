package com.folium.reader.reader

/**
 * The pure arithmetic behind showing a facing-page spread in [ReaderScreen], kept free of Compose so
 * it can be tested without a device: whether the measured page area earns one, how a pager page maps
 * to the reader's own page index and back, which of a spread's two slots a gesture landed in, and
 * which page numbers a spread's position label should read.
 */

/**
 * Whether the reader's own page area — not the window around it — is wide enough for a facing-page
 * spread and wider than it is tall. [minWidthPx] is `FoliumWidthClass.EXPANDED_FROM` converted to
 * pixels by whoever measures the page area, so this stays a pixel comparison free of density.
 */
fun spreadEligible(pageAreaWidthPx: Int, pageAreaHeightPx: Int, minWidthPx: Int): Boolean =
    pageAreaWidthPx >= minWidthPx && pageAreaWidthPx > pageAreaHeightPx

/** The pager page that shows [currentPage] — see [currentPageFor] for the inverse. */
fun pagerPageFor(currentPage: Int, pagesPerView: Int): Int =
    if (pagesPerView == 2) currentPage / 2 else currentPage

/**
 * The reader page a given pager page opens on: itself outside a spread, or its spread's even left
 * page while one is fitted. [pagerPageFor] is this function's own inverse for any left page a spread
 * can actually settle on.
 */
fun currentPageFor(pagerPage: Int, pagesPerView: Int): Int =
    if (pagesPerView == 2) pagerPage * 2 else pagerPage

/** How many pager pages the whole document takes: one per spread, rounded up for a lone last page. */
fun pagerPageCount(pageCount: Int, pagesPerView: Int): Int =
    if (pagesPerView == 2) (pageCount + 1) / 2 else pageCount

/** The page that shares [leftPage]'s spread, or `null` when [leftPage] is a lone last page. */
fun spreadRightPage(leftPage: Int, pageCount: Int): Int? = (leftPage + 1).takeIf { it < pageCount }

/**
 * Which of a fitted spread's two slots an x coordinate measured against the whole page area falls
 * in, and that same coordinate translated into the hit slot's own local space — ready to feed into
 * [ReaderGeometry.layout] for that slot alongside the gesture's unchanged y. The boundary sits at the
 * gutter's midpoint, so a touch anywhere inside the gutter itself still resolves to whichever page it
 * is closer to rather than favouring one slot outright.
 */
data class SpreadSlotHit(val slotIndex: Int, val localXPx: Float)

fun spreadSlotAt(xPx: Float, slotWidthPx: Int, gutterPx: Int): SpreadSlotHit {
    val boundary = slotWidthPx + gutterPx / 2f
    return if (xPx < boundary) {
        SpreadSlotHit(0, xPx.coerceIn(0f, slotWidthPx.toFloat()))
    } else {
        SpreadSlotHit(1, (xPx - slotWidthPx - gutterPx).coerceIn(0f, slotWidthPx.toFloat()))
    }
}

/**
 * The one or two 1-based page numbers a spread's position indicator and its spoken form should read,
 * derived from a 0-based [page] that need not already be a spread's own left page — the scrubber
 * previews a spread from wherever a drag currently sits, which can land on either of its two pages.
 * [rightPage] is `null` both outside a spread and for a lone last page, which is what lets the
 * indicator fall back to naming a single page in either case.
 */
data class SpreadPositionLabel(val leftPage: Int, val rightPage: Int?)

fun spreadPositionLabel(page: Int, pageCount: Int, pagesPerView: Int): SpreadPositionLabel {
    if (pagesPerView != 2) return SpreadPositionLabel(page + 1, null)
    val leftPage = page - (page % 2)
    return SpreadPositionLabel(leftPage + 1, spreadRightPage(leftPage, pageCount)?.plus(1))
}
