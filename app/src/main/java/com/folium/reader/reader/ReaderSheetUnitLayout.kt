package com.folium.reader.reader

import com.folium.reader.core.sequence.SpreadUnit
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The pure layout decisions behind a reader unit that shows a sheet (T-Hoja, T-Lapiz), kept free of
 * composition so they can be tested without a device.
 */

/**
 * Which chrome the reader draws over a unit: [BOOK], its full bars with the position scrubber, or
 * [SHEET], the design's slim header and single footer row, which leave the writing its height.
 */
internal enum class ReaderChromeStyle { BOOK, SHEET }

/** The slim sheet chrome while [unit] shows a sheet in either cell; a book's own chrome otherwise. */
internal fun readerChromeStyle(unit: SpreadUnit?): ReaderChromeStyle =
    if (unitShowsSheet(unit)) ReaderChromeStyle.SHEET else ReaderChromeStyle.BOOK

/**
 * Where the two cells of a book page beside its sheet sit in a page area: the page from
 * [pageLeftPx] across [pageWidthPx], the gap's rule centred on [ruleCenterPx], the sheet from
 * [sheetLeftPx] across [sheetWidthPx], and both from [topPx] down [heightPx].
 */
internal data class SheetSpreadGeometry(
    val pageLeftPx: Int,
    val pageWidthPx: Int,
    val ruleCenterPx: Float,
    val sheetLeftPx: Int,
    val sheetWidthPx: Int,
    val topPx: Int,
    val heightPx: Int
)

/**
 * Splits a page area [widthPx] by [heightPx] into a book page and its sheet: two equal halves around
 * [gapPx], any odd pixel going to the sheet, and both cells bounded by the same [insets] — the ones a
 * sheet cell keeps clear of the chrome — so the page and the sheet start and end on the same lines.
 */
internal fun sheetSpreadGeometry(widthPx: Int, heightPx: Int, gapPx: Int, insets: SheetCellInsets): SheetSpreadGeometry {
    val pageWidth = ((widthPx - gapPx) / 2).coerceAtLeast(0)
    val sheetLeft = pageWidth + gapPx
    val top = insets.topPx.roundToInt()
    val bottom = insets.bottomPx.roundToInt()

    return SheetSpreadGeometry(
        pageLeftPx = 0,
        pageWidthPx = pageWidth,
        ruleCenterPx = pageWidth + gapPx / 2f,
        sheetLeftPx = sheetLeft,
        sheetWidthPx = (widthPx - sheetLeft).coerceAtLeast(0),
        topPx = top,
        heightPx = (heightPx - top - bottom).coerceAtLeast(0)
    )
}

/**
 * A book page inside its cell beside a sheet: the whole page fitted to [viewport] at its own
 * [pageAspect], centred across the cell and resting on its top edge, whatever fit or zoom the book
 * itself is read at — a unit with a sheet takes no pinch.
 */
internal fun sheetSpreadPageLayout(viewport: ReaderViewport, pageAspect: Float): ViewportLayout {
    val viewportWidth = viewport.widthPx.toFloat()
    val pageWidth = min(viewportWidth, viewport.heightPx * pageAspect)

    return ViewportLayout(
        viewport = viewport,
        originX = (viewportWidth - pageWidth) / 2f,
        originY = 0f,
        pageWidth = pageWidth,
        pageHeight = pageWidth / pageAspect
    )
}
