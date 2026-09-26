package com.folium.reader.reader

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folium.reader.core.sequence.ReadingSequence
import com.folium.reader.core.sequence.SequenceItem
import com.folium.reader.core.sequence.SequenceLabel
import com.folium.reader.core.sequence.SpreadUnit
import com.folium.reader.core.sequence.spreadUnits
import com.folium.reader.ink.SheetBodyPadding
import com.folium.reader.ink.SheetDockInsets
import com.folium.reader.ink.SheetPaneRailOrientation
import com.folium.reader.ink.sheetPaneRailOrientation
import com.folium.reader.ui.FoliumWidthClass

/**
 * The pure decisions behind [ReaderScreen]'s pager once a book's sheets are interleaved with its
 * pages, kept free of composition so they can be tested without a device.
 */

/**
 * What the pager turns through: [units], one per pager page, and the [current] one.
 *
 * [sequenced] is `true` when the units came from a published [ReaderSequenceState], in which case
 * the pager moves by unit through [ReaderHostController.step] and [ReaderHostController.settleUnit].
 * Without one — a screen composed on its own, before any host has published a sequence — the units
 * are the book's pages paired exactly as [pagerPageCount] counts them, and the pager moves through
 * page intents exactly as it always did.
 */
internal data class ReaderPagerModel(val units: List<SpreadUnit>, val current: Int, val sequenced: Boolean) {
    val currentUnit: SpreadUnit? get() = units.getOrNull(current)

    val backEnabled: Boolean get() = current > 0

    val forwardEnabled: Boolean get() = current < units.lastIndex
}

internal fun readerPagerModel(
    sequence: ReaderSequenceState,
    currentPage: Int,
    pageCount: Int,
    pagesPerView: Int
): ReaderPagerModel {
    if (sequence.units.isNotEmpty()) {
        return ReaderPagerModel(sequence.units, sequence.currentUnit.coerceIn(sequence.units.indices), sequenced = true)
    }

    val pages = spreadUnits(ReadingSequence.build(pageCount.coerceAtLeast(0), emptyList()), pagesPerView).units

    return ReaderPagerModel(pages, pagerPageFor(currentPage, pagesPerView), sequenced = false)
}

/** Whether [unit] shows a sheet in either of its cells. */
internal fun unitShowsSheet(unit: SpreadUnit?): Boolean =
    unit != null && (unit.left is SequenceItem.Sheet || unit.right is SequenceItem.Sheet)

/**
 * Which of the pager's own gestures the current unit keeps: [swipe] turns it, [zoom] pinches it, and
 * [pan] moves a zoomed page under one finger.
 */
internal data class UnitGestures(val swipe: Boolean, val zoom: Boolean, val pan: Boolean = true)

/**
 * A unit with a sheet neither swipes nor zooms, so a stroke on the sheet is never taken for a page
 * turn or a pinch. Zoom also needs the unit to open on the page the presenter is on: the presenter
 * zooms its own page, and a lone right page shown after its left page's sheets is not that page.
 * With no pages at all there is no unit, and the gestures stay as they always were.
 *
 * While [writing] a unit of book pages stops swiping too, so a stroke on a page is never taken for a
 * page turn, and stops panning a zoomed page under one finger, so a stroke there is never taken for a
 * pan; it keeps its zoom, which the page's own drawing surface asks for on the reader's behalf, and
 * the surface pans for the VIEW tool and two fingers itself.
 */
internal fun unitGestures(unit: SpreadUnit?, presenterPage: Int, zoomed: Boolean, writing: Boolean = false): UnitGestures {
    if (unit == null) return UnitGestures(swipe = !zoomed, zoom = true)

    val showsSheet = unitShowsSheet(unit)
    val writingOnPages = writing && !showsSheet

    return UnitGestures(
        swipe = !zoomed && !showsSheet && !writing,
        zoom = !showsSheet && unit.left == SequenceItem.Page(presenterPage),
        pan = !writingOnPages
    )
}

/**
 * The x coordinate, measured against the whole page area of [widthPx], from which a tap lands on
 * [unit]'s sheet: the whole area for a sheet shown alone, and the boundary [spreadSlotAt] draws for a
 * sheet beside its page. `null` for a unit with no sheet.
 */
internal fun sheetStartPx(unit: SpreadUnit?, slotWidthPx: Int?, gutterPx: Int, widthPx: Int): Float? = when {
    unit == null -> null
    unit.left is SequenceItem.Sheet -> 0f
    unit.right is SequenceItem.Sheet -> slotWidthPx?.let { it + gutterPx / 2f } ?: (widthPx / 2f)
    else -> null
}

/** What a single tap on the page area asks for. */
internal enum class PageTap { BACK, FORWARD, TOGGLE_CHROME, NONE }

/**
 * A tap at [xPx] across a page area [widthPx] wide. On pages this is what it always was: the outer
 * [EDGE_TAP_FRACTION] of either edge turns, the middle toggles the chrome, and a zoomed page only
 * toggles the chrome. On a unit with a sheet — [sheetStartPx] not `null` — a tap on the sheet does
 * nothing, since it belongs to the writing there, and nothing hides the chrome, so the system bars
 * never change the page area mid-stroke; the book page beside a sheet still turns back at its edge.
 * While [writing] on a unit of book pages no tap does anything: the pages belong to the pen, and the
 * footer's chevrons turn them.
 */
internal fun pageTap(xPx: Float, widthPx: Int, zoomed: Boolean, sheetStartPx: Float?, writing: Boolean = false): PageTap {
    if (sheetStartPx != null && xPx >= sheetStartPx) return PageTap.NONE
    if (writing && sheetStartPx == null) return PageTap.NONE

    val besideSheet = sheetStartPx != null
    val horizontal = xPx / widthPx

    return when {
        zoomed -> if (besideSheet) PageTap.NONE else PageTap.TOGGLE_CHROME
        horizontal < EDGE_TAP_FRACTION -> PageTap.BACK
        horizontal > 1f - EDGE_TAP_FRACTION -> PageTap.FORWARD
        besideSheet -> PageTap.NONE
        else -> PageTap.TOGGLE_CHROME
    }
}

/** The page and sheet numbers the footer names while a sheet is current; `null` on a book page. */
internal fun sheetPosition(sequence: ReaderSequenceState): SequenceLabel? =
    sequence.currentLabel?.takeIf { sequence.currentSheet != null && it.sheetOrdinal != null }

/** What [sheet]'s own cell is called: its page, 1-based, and its ordinal among that page's sheets. */
internal fun sheetLabelOf(sheet: SequenceItem.Sheet): SequenceLabel = SequenceLabel(sheet.pageIndex + 1, sheet.ordinal)

/** How far a cell is pushed in from the top and bottom of the page area, in pixels. */
internal data class SheetCellInsets(val topPx: Float, val bottomPx: Float)

/**
 * A sheet cell keeps clear of the reader's chrome, which stays drawn over the page area for as long
 * as a sheet is on screen, so its header is never hidden under the bars. A book page keeps the whole
 * page area, as it always has. A bar not measured yet, `null`, takes no room.
 *
 * Beside a column [rail] the cell also keeps [bodyPaddingPx] inside both bars, lining up with the rail
 * itself; above a compact row it stops at the pager's own bottom, which the row already holds clear
 * of the bottom bar.
 */
internal fun sheetCellInsets(
    chromeTopPx: Float?,
    chromeBottomPx: Float?,
    cellShowsSheet: Boolean,
    rail: SheetPaneRailOrientation? = null,
    bodyPaddingPx: Float = 0f
): SheetCellInsets {
    if (!cellShowsSheet) return SheetCellInsets(0f, 0f)

    val top = (chromeTopPx ?: 0f).coerceAtLeast(0f)
    val bottom = (chromeBottomPx ?: 0f).coerceAtLeast(0f)

    return when (rail) {
        null -> SheetCellInsets(top, bottom)
        SheetPaneRailOrientation.COLUMN -> SheetCellInsets(top + bodyPaddingPx, bottom + bodyPaddingPx)
        SheetPaneRailOrientation.ROW -> SheetCellInsets(top, 0f)
    }
}

/**
 * Which rail, if any, the reader draws: none while the unit on screen shows book pages alone and the
 * reader is not [writing] on them, or while there are no tools to drive, and otherwise a column on a
 * tablet-width window and a bottom row on a phone-width one.
 */
internal fun readerSheetRail(
    widthClass: FoliumWidthClass,
    sheetCurrent: Boolean,
    toolsAvailable: Boolean,
    writing: Boolean = false
): SheetPaneRailOrientation? =
    if ((sheetCurrent || writing) && toolsAvailable) sheetPaneRailOrientation(widthClass) else null

/**
 * How far a book page's own cell is pushed in: exactly as far as a sheet cell, [sheetInsets], while
 * [writing], since the chrome then stays drawn over the page area; not at all otherwise, or when the
 * page sits [besideSheet], whose row already bounds both its cells by those insets. [sheetInsets] is
 * read only when used, so a page read as usual never depends on the chrome's measured height.
 */
internal fun readerPageCellInsets(writing: Boolean, besideSheet: Boolean, sheetInsets: () -> SheetCellInsets): SheetCellInsets =
    if (writing && !besideSheet) sheetInsets() else SheetCellInsets(0f, 0f)

/**
 * Where the reader's rail sits in the page area, which its chrome bars overlay: a column keeps
 * [SheetBodyPadding] inside both bars while the pager beside it keeps its full height, and a compact
 * row sits right above the bottom bar with the pager above it.
 */
internal fun readerSheetDockInsets(rail: SheetPaneRailOrientation?, chromeTop: Dp, chromeBottom: Dp): SheetDockInsets =
    when (rail) {
        null -> SheetDockInsets(0.dp, 0.dp, 0.dp, 0.dp, 0.dp)
        SheetPaneRailOrientation.COLUMN -> SheetDockInsets(
            railTop = chromeTop + SheetBodyPadding,
            railBottom = chromeBottom + SheetBodyPadding,
            contentTop = 0.dp,
            contentBottom = 0.dp,
            rowBottom = 0.dp
        )
        SheetPaneRailOrientation.ROW -> SheetDockInsets(0.dp, 0.dp, 0.dp, 0.dp, rowBottom = chromeBottom)
    }
