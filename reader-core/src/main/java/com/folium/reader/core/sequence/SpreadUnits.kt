package com.folium.reader.core.sequence

import com.folium.reader.core.ink.SheetId

/** What one pager page shows: [left] alone, or [left] beside [right]. */
data class SpreadUnit(val left: SequenceItem, val right: SequenceItem?)

/**
 * A [ReadingSequence] cut into the [units] a pager turns through, one unit per pager page.
 *
 * With one page per view every item is its own unit. With two, pages pair from the first page exactly
 * as they do without sheets, and a sheet always sits in the right cell beside its own page:
 *
 * - a right page `p` with sheets shows in its usual spread `[p - 1, p]`, then as `[p | sheet]` once
 *   per sheet, and pairing resumes at `p + 1`;
 * - a left page `p` with sheets shows as `[p | sheet]` once per sheet, then its right page `p + 1`
 *   shows on its own, and pairing resumes at `p + 2`;
 * - a page shown on its own, whether that right page or a lone last page, sits beside its own sheets
 *   as `[page | sheet]` when it has any, and as `[page | null]` when it has none.
 *
 * A page can therefore appear in several units; [unitOf] returns the first.
 */
class SpreadUnits internal constructor(val units: List<SpreadUnit>, private val pagesPerView: Int) {

    private val firstUnitOfPage: Map<Int, Int>
    private val firstUnitOfSheet: Map<SheetId, Int>

    init {
        val pages = HashMap<Int, Int>()
        val sheets = HashMap<SheetId, Int>()

        units.forEachIndexed { unitIndex, unit ->
            for (item in listOfNotNull(unit.left, unit.right)) {
                when (item) {
                    is SequenceItem.Page -> pages.putIfAbsent(item.index, unitIndex)
                    is SequenceItem.Sheet -> sheets.putIfAbsent(item.id, unitIndex)
                }
            }
        }

        firstUnitOfPage = pages
        firstUnitOfSheet = sheets
    }

    /** The index of the first unit showing [item], matching a sheet by id alone; -1 when none does. */
    fun unitOf(item: SequenceItem): Int = when (item) {
        is SequenceItem.Page -> firstUnitOfPage[item.index] ?: -1
        is SequenceItem.Sheet -> firstUnitOfSheet[item.id] ?: -1
    }

    /**
     * The book page the page presenter should sit on while [unit] shows: the unit's first book page,
     * or the page a sheet-only unit is anchored to, moved to the even left page of its pair with two
     * pages per view.
     */
    fun presenterPageFor(unit: SpreadUnit): Int {
        val bookPage = when (val left = unit.left) {
            is SequenceItem.Page -> left.index
            is SequenceItem.Sheet -> left.pageIndex
        }

        return if (pagesPerView == 2) bookPage - bookPage % 2 else bookPage
    }
}

/** Cuts [sequence] into the units a pager with [pagesPerView] pages per view turns through. */
fun spreadUnits(sequence: ReadingSequence, pagesPerView: Int): SpreadUnits {
    require(pagesPerView == 1 || pagesPerView == 2) { "pagesPerView must be 1 or 2, was $pagesPerView" }

    if (pagesPerView == 1) return SpreadUnits(sequence.items.map { SpreadUnit(it, null) }, pagesPerView)

    val sheetsByPage = sequence.items.filterIsInstance<SequenceItem.Sheet>().groupBy { it.pageIndex }
    val pageCount = sequence.items.count { it is SequenceItem.Page }
    val units = mutableListOf<SpreadUnit>()

    fun addAlone(pageIndex: Int) {
        val sheets = sheetsByPage[pageIndex].orEmpty()

        if (sheets.isEmpty()) {
            units += SpreadUnit(SequenceItem.Page(pageIndex), null)
        } else {
            sheets.forEach { units += SpreadUnit(SequenceItem.Page(pageIndex), it) }
        }
    }

    for (leftPage in 0 until pageCount step 2) {
        val rightPage = leftPage + 1

        when {
            rightPage >= pageCount -> addAlone(leftPage)

            sheetsByPage.containsKey(leftPage) -> {
                addAlone(leftPage)
                addAlone(rightPage)
            }

            else -> {
                units += SpreadUnit(SequenceItem.Page(leftPage), SequenceItem.Page(rightPage))
                sheetsByPage[rightPage].orEmpty().forEach { units += SpreadUnit(SequenceItem.Page(rightPage), it) }
            }
        }
    }

    return SpreadUnits(units, pagesPerView)
}
