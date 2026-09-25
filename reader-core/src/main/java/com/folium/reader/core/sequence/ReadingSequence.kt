package com.folium.reader.core.sequence

import com.folium.reader.core.ink.SheetId

/** The rank distance a new sheet keeps from its neighbour at either end of a page's sheets. */
const val SHEET_RANK_STEP: Long = 1L shl 20

/**
 * A sheet whose anchor the caller has already resolved to [pageIndex] of the book being read. For a
 * fixed-layout book that is the anchor's own page; for a reflowable one it is whichever page the
 * anchor's text lands on under the current layout.
 */
data class PlacedSheet(
    val id: SheetId,
    val pageIndex: Int,
    val rank: Long,
    val createdAtEpochMillis: Long
)

/** One stop in a [ReadingSequence]: either a book page or a sheet read right after its page. */
sealed interface SequenceItem {
    data class Page(val index: Int) : SequenceItem

    /** [ordinal] is 1-based among the sheets that follow page [pageIndex], in reading order. */
    data class Sheet(val id: SheetId, val pageIndex: Int, val ordinal: Int) : SequenceItem
}

/**
 * What a [SequenceItem] is called, before any wording: the 1-based [pageNumber] it belongs to, plus
 * the [sheetOrdinal] of a sheet, or `null` for the page itself. Formatting belongs to the caller.
 */
data class SequenceLabel(val pageNumber: Int, val sheetOrdinal: Int?)

/** Where a new sheet goes so that it is read at a chosen spot in a [ReadingSequence]. */
sealed interface SheetInsertion {
    val pageIndex: Int
    val rank: Long

    /** Anchor the new sheet at [pageIndex] with [rank]; no existing sheet changes. */
    data class Ranked(override val pageIndex: Int, override val rank: Long) : SheetInsertion

    /**
     * No rank was left between the new sheet's neighbours. Anchor the new sheet at [pageIndex] with
     * [rank], and rewrite every existing sheet in [reranked] to its new rank; the page keeps its
     * reading order.
     */
    data class Rebalanced(
        override val pageIndex: Int,
        override val rank: Long,
        val reranked: Map<SheetId, Long>
    ) : SheetInsertion
}

/**
 * A book's pages in order, each followed by the sheets anchored to it. Reading forward from page `p`
 * passes every sheet of `p`, ordered by rank, then creation time, then id, before page `p + 1`;
 * reading backward is the exact reverse.
 *
 * A sheet anchored past the last page is read after the last page, and one anchored before the first
 * page after the first page. A book with no pages has an empty sequence: there is no page to read a
 * sheet next to, and the sheets themselves stay stored untouched.
 *
 * A sheet is identified by its [SheetId] alone, so an item built before sheets were added or removed
 * still finds its sheet even though its ordinal may have changed.
 */
class ReadingSequence private constructor(
    val items: List<SequenceItem>,
    private val sheetsByPage: Map<Int, List<PlacedSheet>>
) {
    private val pagePositions: Map<Int, Int> = items.withIndex()
        .mapNotNull { (position, item) -> (item as? SequenceItem.Page)?.let { it.index to position } }
        .toMap()

    private val sheetPositions: Map<SheetId, Int> = items.withIndex()
        .mapNotNull { (position, item) -> (item as? SequenceItem.Sheet)?.let { it.id to position } }
        .toMap()

    /** [item]'s position in [items], or -1 when it is not part of this sequence. */
    fun indexOf(item: SequenceItem): Int = when (item) {
        is SequenceItem.Page -> pagePositions[item.index] ?: -1
        is SequenceItem.Sheet -> sheetPositions[item.id] ?: -1
    }

    /** The item read right after [item], or `null` at the end. [item] must be part of this sequence. */
    fun next(item: SequenceItem): SequenceItem? = items.getOrNull(positionOf(item) + 1)

    /** The item read right before [item], or `null` at the start. [item] must be part of this sequence. */
    fun prev(item: SequenceItem): SequenceItem? = items.getOrNull(positionOf(item) - 1)

    fun label(item: SequenceItem): SequenceLabel = when (val current = items[positionOf(item)]) {
        is SequenceItem.Page -> SequenceLabel(current.index + 1, null)
        is SequenceItem.Sheet -> SequenceLabel(current.pageIndex + 1, current.ordinal)
    }

    /**
     * Where a new sheet goes to be read immediately after [item]: after a page, before that page's
     * existing sheets; after a sheet, between it and the next sheet of the same page. A rank sits
     * [SHEET_RANK_STEP] away from a single neighbour and halfway between two; when no whole rank is
     * left, the page's sheets are renumbered [SHEET_RANK_STEP] apart from zero.
     */
    fun insertionAfter(item: SequenceItem): SheetInsertion {
        val current = items[positionOf(item)]

        val pageIndex = when (current) {
            is SequenceItem.Page -> current.index
            is SequenceItem.Sheet -> current.pageIndex
        }
        val pageSheets = sheetsByPage[pageIndex].orEmpty()
        val slot = when (current) {
            is SequenceItem.Page -> 0
            is SequenceItem.Sheet -> current.ordinal
        }

        val before = pageSheets.getOrNull(slot - 1)?.rank
        val after = pageSheets.getOrNull(slot)?.rank
        val rank = rankBetween(before, after)
            ?: return rebalance(pageIndex, pageSheets, slot)

        return SheetInsertion.Ranked(pageIndex, rank)
    }

    private fun positionOf(item: SequenceItem): Int {
        val position = indexOf(item)
        require(position >= 0) { "$item is not part of this reading sequence" }
        return position
    }

    companion object {
        private val readingOrder = compareBy<PlacedSheet>({ it.rank }, { it.createdAtEpochMillis }, { it.id.value })

        fun build(pageCount: Int, sheets: List<PlacedSheet>): ReadingSequence {
            require(pageCount >= 0) { "pageCount must be non-negative, was $pageCount" }
            require(sheets.map { it.id }.toSet().size == sheets.size) { "sheet ids must be distinct" }

            if (pageCount == 0) return ReadingSequence(emptyList(), emptyMap())

            val sheetsByPage = sheets
                .groupBy { it.pageIndex.coerceIn(0, pageCount - 1) }
                .mapValues { (_, pageSheets) -> pageSheets.sortedWith(readingOrder) }

            val items = mutableListOf<SequenceItem>()
            for (pageIndex in 0 until pageCount) {
                items += SequenceItem.Page(pageIndex)

                sheetsByPage[pageIndex].orEmpty().forEachIndexed { index, sheet ->
                    items += SequenceItem.Sheet(sheet.id, pageIndex, ordinal = index + 1)
                }
            }

            return ReadingSequence(items, sheetsByPage)
        }

        /** A rank strictly between [before] and [after], either of which may be absent; `null` when none exists. */
        private fun rankBetween(before: Long?, after: Long?): Long? = when {
            before != null && after != null ->
                if (before < after && before + 1 < after) (before shr 1) + (after shr 1) + (before and after and 1L) else null

            before != null -> if (before <= Long.MAX_VALUE - SHEET_RANK_STEP) before + SHEET_RANK_STEP else null

            after != null -> if (after >= Long.MIN_VALUE + SHEET_RANK_STEP) after - SHEET_RANK_STEP else null

            else -> 0L
        }

        private fun rebalance(pageIndex: Int, pageSheets: List<PlacedSheet>, slot: Int): SheetInsertion.Rebalanced {
            val reranked = LinkedHashMap<SheetId, Long>()

            pageSheets.forEachIndexed { index, sheet ->
                val position = if (index < slot) index else index + 1
                reranked[sheet.id] = position * SHEET_RANK_STEP
            }

            return SheetInsertion.Rebalanced(pageIndex, rank = slot * SHEET_RANK_STEP, reranked = reranked)
        }
    }
}
