package com.folium.reader.reader

import com.folium.reader.core.sequence.ReadingSequence
import com.folium.reader.core.sequence.SequenceItem
import com.folium.reader.core.sequence.SequenceLabel
import com.folium.reader.core.sequence.SpreadUnit
import com.folium.reader.core.sequence.spreadUnits

/**
 * The pure decisions behind [ReaderScreen]'s pager once a book's sheets are interleaved with its
 * pages, kept free of Compose so they can be tested without a device.
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

/** Which of the pager's own gestures the current unit keeps. */
internal data class UnitGestures(val swipe: Boolean, val zoom: Boolean)

/**
 * A unit with a sheet neither swipes nor zooms, so a stroke on the sheet is never taken for a page
 * turn or a pinch. Zoom also needs the unit to open on the page the presenter is on: the presenter
 * zooms its own page, and a lone right page shown after its left page's sheets is not that page.
 * With no pages at all there is no unit, and the gestures stay as they always were.
 */
internal fun unitGestures(unit: SpreadUnit?, presenterPage: Int, zoomed: Boolean): UnitGestures {
    if (unit == null) return UnitGestures(swipe = !zoomed, zoom = true)

    val showsSheet = unitShowsSheet(unit)

    return UnitGestures(
        swipe = !zoomed && !showsSheet,
        zoom = !showsSheet && unit.left == SequenceItem.Page(presenterPage)
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
 */
internal fun pageTap(xPx: Float, widthPx: Int, zoomed: Boolean, sheetStartPx: Float?): PageTap {
    if (sheetStartPx != null && xPx >= sheetStartPx) return PageTap.NONE

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
