package com.folium.reader.core.sequence

import com.folium.reader.core.ink.SheetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadUnitsTest {

    private fun id(name: String) = SheetId("sheet-$name")

    private fun page(index: Int) = SequenceItem.Page(index)

    private fun sheet(name: String, pageIndex: Int, ordinal: Int) = SequenceItem.Sheet(id(name), pageIndex, ordinal)

    private fun sequence(pageCount: Int, vararg sheets: Pair<String, Int>) = ReadingSequence.build(
        pageCount,
        sheets.mapIndexed { order, (name, pageIndex) -> PlacedSheet(id(name), pageIndex, rank = order.toLong(), createdAtEpochMillis = 0L) }
    )

    /*
     * The app's pager arithmetic (app/.../reader/ReaderSpread.kt: pagerPageFor, currentPageFor,
     * pagerPageCount, spreadRightPage) lives in :app, which :reader-core cannot depend on, so its
     * rules are restated here verbatim.
     */
    private fun pagerPageFor(currentPage: Int, pagesPerView: Int) = if (pagesPerView == 2) currentPage / 2 else currentPage

    private fun currentPageFor(pagerPage: Int, pagesPerView: Int) = if (pagesPerView == 2) pagerPage * 2 else pagerPage

    private fun pagerPageCount(pageCount: Int, pagesPerView: Int) = if (pagesPerView == 2) (pageCount + 1) / 2 else pageCount

    private fun spreadRightPage(leftPage: Int, pageCount: Int): Int? = (leftPage + 1).takeIf { it < pageCount }

    @Test fun `with no sheets the units are exactly the app's pager pages`() {
        for (pagesPerView in listOf(1, 2)) {
            for (pageCount in 0..9) {
                val layout = spreadUnits(sequence(pageCount), pagesPerView)

                assertEquals(pagerPageCount(pageCount, pagesPerView), layout.units.size)

                layout.units.forEachIndexed { pagerPage, unit ->
                    val leftPage = currentPageFor(pagerPage, pagesPerView)
                    val rightPage = if (pagesPerView == 2) spreadRightPage(leftPage, pageCount) else null

                    assertEquals(SpreadUnit(page(leftPage), rightPage?.let(::page)), unit)
                    assertEquals(leftPage, layout.presenterPageFor(unit))
                }

                for (pageIndex in 0 until pageCount) {
                    assertEquals(pagerPageFor(pageIndex, pagesPerView), layout.unitOf(page(pageIndex)))
                }
            }
        }
    }

    @Test fun `sheets on a left page sit beside it, then its right page shows alone, then pairing resumes`() {
        val layout = spreadUnits(sequence(8, "a" to 4, "b" to 4), pagesPerView = 2)

        assertEquals(
            listOf(
                SpreadUnit(page(0), page(1)),
                SpreadUnit(page(2), page(3)),
                SpreadUnit(page(4), sheet("a", 4, 1)),
                SpreadUnit(page(4), sheet("b", 4, 2)),
                SpreadUnit(page(5), null),
                SpreadUnit(page(6), page(7))
            ),
            layout.units
        )
    }

    @Test fun `sheets on a right page follow its spread and sit beside it`() {
        val layout = spreadUnits(sequence(8, "a" to 5, "b" to 5), pagesPerView = 2)

        assertEquals(
            listOf(
                SpreadUnit(page(0), page(1)),
                SpreadUnit(page(2), page(3)),
                SpreadUnit(page(4), page(5)),
                SpreadUnit(page(5), sheet("a", 5, 1)),
                SpreadUnit(page(5), sheet("b", 5, 2)),
                SpreadUnit(page(6), page(7))
            ),
            layout.units
        )
    }

    @Test fun `when both pages of a pair have sheets the right page sits beside its own sheets`() {
        val layout = spreadUnits(sequence(8, "a" to 4, "b" to 5), pagesPerView = 2)

        assertEquals(
            listOf(
                SpreadUnit(page(0), page(1)),
                SpreadUnit(page(2), page(3)),
                SpreadUnit(page(4), sheet("a", 4, 1)),
                SpreadUnit(page(5), sheet("b", 5, 1)),
                SpreadUnit(page(6), page(7))
            ),
            layout.units
        )
    }

    @Test fun `a lone last page sits beside its sheets`() {
        val layout = spreadUnits(sequence(5, "a" to 4), pagesPerView = 2)

        assertEquals(
            listOf(SpreadUnit(page(0), page(1)), SpreadUnit(page(2), page(3)), SpreadUnit(page(4), sheet("a", 4, 1))),
            layout.units
        )
    }

    @Test fun `every item is its own unit in single-page mode`() {
        val sequence = sequence(4, "a" to 1, "b" to 1)
        val layout = spreadUnits(sequence, pagesPerView = 1)

        assertEquals(sequence.items.map { SpreadUnit(it, null) }, layout.units)
        assertEquals(1, layout.presenterPageFor(SpreadUnit(sheet("b", 1, 2), null)))
        assertEquals(3, layout.unitOf(sheet("b", 1, 2)))
    }

    @Test fun `an item's unit is the first one that shows it`() {
        val layout = spreadUnits(sequence(8, "a" to 5, "b" to 5), pagesPerView = 2)

        assertEquals(2, layout.unitOf(page(5)))
        assertEquals(4, layout.unitOf(sheet("b", 5, 2)))
        assertEquals(4, layout.unitOf(sheet("b", 5, 1)))
        assertEquals(-1, layout.unitOf(sheet("z", 5, 1)))
    }

    @Test fun `the presenter sits on the even left page of a unit's first book page`() {
        val layout = spreadUnits(sequence(8, "a" to 4, "b" to 5), pagesPerView = 2)

        assertEquals(listOf(0, 2, 4, 4, 6), layout.units.map(layout::presenterPageFor))
    }

    @Test fun `a book with no pages has no units`() {
        assertTrue(spreadUnits(sequence(0, "a" to 0), pagesPerView = 2).units.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `only one or two pages per view are accepted`() {
        spreadUnits(sequence(4), pagesPerView = 3)
    }
}
