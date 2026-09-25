package com.folium.reader.reader

import com.folium.reader.core.ink.SheetAnchor
import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.ink.SheetListing
import com.folium.reader.core.pdf.ReadingPosition
import com.folium.reader.core.sequence.PlacedSheet
import com.folium.reader.core.sequence.ReadingSequence
import com.folium.reader.core.sequence.SequenceItem
import com.folium.reader.core.sequence.SequenceLabel
import com.folium.reader.core.sequence.SpreadUnit
import com.folium.reader.core.sequence.SpreadUnits
import com.folium.reader.core.sequence.spreadUnits

/**
 * What the reader turns through once a book's sheets are interleaved with its pages: one [units]
 * entry per pager page, the [currentUnit] on screen, what that unit is called, and the sheet it
 * shows, if any. With no sheets [units] is exactly one unit per pager page of today's reader, so
 * [currentUnit] equals [pagerPageFor] of the presenter's current page.
 *
 * [currentLabel] is `null` only before the document has opened, or for a document with no pages.
 */
data class ReaderSequenceState(
    val units: List<SpreadUnit> = emptyList(),
    val currentUnit: Int = 0,
    val currentLabel: SequenceLabel? = null,
    val currentSheet: SheetId? = null
)

/**
 * Places every sheet in [listing] on a page of a book laid out in [pageCount] pages: a page anchor on
 * its own page, and a text anchor on the page [resolvePositions] maps its position to, asked once for
 * every text anchor together.
 *
 * A text anchor [resolvePositions] cannot map — its chapter no longer exists, or the book is
 * fixed-layout and has no text positions — is placed on the book's last page, and a page anchor past
 * the end is read after the last page by [ReadingSequence.build] itself, so a sheet is never dropped
 * from the sequence. A sheet with no anchor is not part of any book and is skipped.
 *
 * A batch [resolvePositions] throws on is asked again one position at a time, and every position that
 * still throws counts as one it cannot map: one bad anchor lands on the last page without moving the
 * others, and a document that fails outright — closed or relaid out while this ran — places every
 * text anchor there. Such a placement is only ever adopted if the caller's own staleness check lets it.
 */
internal fun placeAnchoredSheets(
    listing: SheetListing,
    pageCount: Int,
    resolvePositions: (List<ReadingPosition>) -> List<Int?>
): List<PlacedSheet> {
    val lastPage = (pageCount - 1).coerceAtLeast(0)
    val anchored = listing.sheets.filter { it.anchor != null }

    val positions = anchored.mapNotNull { (it.anchor as? SheetAnchor.Text)?.position }
    val resolved = if (positions.isEmpty()) emptyList() else resolveEachTolerating(positions, resolvePositions)
    var nextResolved = 0

    return anchored.map { summary ->
        val pageIndex = when (val anchor = requireNotNull(summary.anchor)) {
            is SheetAnchor.Page -> anchor.pageIndex
            is SheetAnchor.Text -> resolved.getOrNull(nextResolved++) ?: lastPage
        }

        PlacedSheet(summary.id, pageIndex, requireNotNull(summary.anchor).rank, summary.createdAtEpochMillis)
    }
}

private fun resolveEachTolerating(
    positions: List<ReadingPosition>,
    resolvePositions: (List<ReadingPosition>) -> List<Int?>
): List<Int?> {
    try {
        return resolvePositions(positions)
    } catch (_: Exception) {
        return positions.map { position ->
            try {
                resolvePositions(listOf(position)).singleOrNull()
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Tracks which [SpreadUnit] of a book's interleaved [ReadingSequence] is on screen, alongside the page
 * presenter that keeps showing book pages exactly as it does without sheets. Confined to the main
 * thread, like the presenter state it follows, and free of any threading of its own.
 *
 * The presenter never leaves book pages: while a sheet unit is current it sits on
 * [SpreadUnits.presenterPageFor] that unit, so the page it reports — and the reading progress recorded
 * from it — is the sheet's own page, or that page's even left page in a spread. Every operation that
 * moves between units returns the page the presenter has to be on for the new unit; the caller moves
 * the presenter there when it is not already.
 *
 * The current unit survives a presenter change only while the presenter still sits on that unit's
 * page; any other page the presenter lands on — a direct jump, a page turn the navigator did not ask
 * for, a relayout — makes the first unit showing that page current instead.
 */
internal class ReaderSequenceNavigator {
    private var sheets: List<PlacedSheet> = emptyList()
    private var pageCount = -1
    private var pagesPerView = 1
    private var sequence: ReadingSequence = ReadingSequence.build(0, emptyList())
    private var spread: SpreadUnits = spreadUnits(sequence, 1)
    private var currentUnit = 0

    var state: ReaderSequenceState = ReaderSequenceState()
        private set

    /**
     * Follows the presenter to [presenterPage] of a book now laid out in [pageCount] pages shown
     * [pagesPerView] at a time, rebuilding the units when either changed. A sheet stays current across
     * a rebuild only when the presenter already sits on its unit's page; see [replaceSheets] for the
     * one rebuild allowed to move the presenter.
     */
    fun followPresenter(pageCount: Int, pagesPerView: Int, presenterPage: Int) {
        if (pageCount != this.pageCount || pagesPerView != this.pagesPerView) {
            val kept = currentItem()
            this.pageCount = pageCount
            this.pagesPerView = pagesPerView
            rebuild()
            currentUnit = unitKeeping(kept, presenterPage, allowPresenterMove = false)
        } else if (presenterPageOf(currentUnit) != presenterPage) {
            currentUnit = unitOfPage(presenterPage)
        }

        publish()
    }

    /**
     * Adopts a fresh placement of the book's sheets. The item on screen stays current: a sheet that
     * still exists stays current even when its page moved, in which case the page the presenter has to
     * move to is returned; a sheet that is gone gives way to the page the presenter is on.
     */
    fun replaceSheets(sheets: List<PlacedSheet>, presenterPage: Int): Int? {
        val kept = currentItem()
        this.sheets = sheets
        rebuild()
        currentUnit = unitKeeping(kept, presenterPage, allowPresenterMove = true)
        publish()

        return presenterPageOf(currentUnit)?.takeIf { it != presenterPage }
    }

    /** Moves [delta] units along the sequence; `null`, changing nothing, when no unit is there. */
    fun step(delta: Int): Int? = moveTo(currentUnit + delta)

    /** Makes unit [index] current, as the pager does when it settles; `null` when there is no such unit. */
    fun settle(index: Int): Int? = moveTo(index)

    /** Makes the first unit showing sheet [id] current; `null`, changing nothing, when no unit shows it. */
    fun goToSheet(id: SheetId): Int? {
        val unit = spread.unitOf(SequenceItem.Sheet(id, pageIndex = 0, ordinal = 1))
        return if (unit < 0) null else moveTo(unit)
    }

    /**
     * A direct jump to [pageIndex] — the scrubber, the jump dialog, the contents, a search result —
     * leaves any sheet behind and makes the first unit showing that page current. With two pages per
     * view a left page with sheets is only ever shown beside its first sheet, so that sheet is what
     * the unit shows even then.
     */
    fun jumpToPage(pageIndex: Int) {
        currentUnit = unitOfPage(pageIndex)
        publish()
    }

    private fun moveTo(index: Int): Int? {
        if (index !in spread.units.indices) return null

        currentUnit = index
        publish()

        return spread.presenterPageFor(spread.units[index])
    }

    private fun rebuild() {
        sequence = ReadingSequence.build(pageCount.coerceAtLeast(0), sheets)
        spread = spreadUnits(sequence, pagesPerView)
    }

    private fun unitKeeping(item: SequenceItem?, presenterPage: Int, allowPresenterMove: Boolean): Int {
        val unit = item?.let(spread::unitOf) ?: -1
        if (unit < 0) return unitOfPage(presenterPage)

        val keepsPresenter = presenterPageOf(unit) == presenterPage
        val movable = allowPresenterMove && item is SequenceItem.Sheet

        return if (keepsPresenter || movable) unit else unitOfPage(presenterPage)
    }

    private fun unitOfPage(pageIndex: Int): Int {
        if (spread.units.isEmpty()) return 0

        val clamped = pageIndex.coerceIn(0, pageCount - 1)
        return spread.unitOf(SequenceItem.Page(clamped)).coerceAtLeast(0)
    }

    private fun presenterPageOf(unit: Int): Int? = spread.units.getOrNull(unit)?.let(spread::presenterPageFor)

    /** The sheet a unit shows when it shows one, and otherwise its first page. */
    private fun currentItem(): SequenceItem? {
        val unit = spread.units.getOrNull(currentUnit) ?: return null
        return unit.right as? SequenceItem.Sheet ?: unit.left
    }

    private fun publish() {
        val item = currentItem()

        state = ReaderSequenceState(
            units = spread.units,
            currentUnit = currentUnit,
            currentLabel = item?.let(sequence::label),
            currentSheet = (item as? SequenceItem.Sheet)?.id
        )
    }
}
