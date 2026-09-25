package com.folium.reader.core.sequence

import com.folium.reader.core.ink.SheetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val STEP = 1L shl 20

class ReadingSequenceTest {

    private fun id(n: Int) = SheetId("sheet-$n")

    private fun placed(n: Int, pageIndex: Int, rank: Long = 0L, createdAt: Long = 0L) =
        PlacedSheet(id(n), pageIndex, rank, createdAt)

    @Test fun `two sheets on page 19 sit between page 19 and page 20 in both directions`() {
        val sequence = ReadingSequence.build(
            pageCount = 20,
            sheets = listOf(placed(2, pageIndex = 18, rank = STEP), placed(1, pageIndex = 18, rank = 0L))
        )

        val forward = generateSequence(SequenceItem.Page(18) as SequenceItem) { sequence.next(it) }.toList()
        assertEquals(
            listOf(SequenceItem.Page(18), SequenceItem.Sheet(id(1), 18, 1), SequenceItem.Sheet(id(2), 18, 2), SequenceItem.Page(19)),
            forward
        )
        assertEquals(
            listOf(SequenceLabel(19, null), SequenceLabel(19, 1), SequenceLabel(19, 2), SequenceLabel(20, null)),
            forward.map(sequence::label)
        )

        val backward = generateSequence(SequenceItem.Page(19) as SequenceItem) { item ->
            sequence.prev(item)?.takeIf { sequence.indexOf(it) >= sequence.indexOf(SequenceItem.Page(18)) }
        }.toList()
        assertEquals(forward.reversed(), backward)
    }

    @Test fun `the sequence holds every page once in order with sheets after their page`() {
        val sequence = ReadingSequence.build(pageCount = 3, sheets = listOf(placed(1, pageIndex = 0), placed(2, pageIndex = 2)))

        assertEquals(
            listOf(
                SequenceItem.Page(0), SequenceItem.Sheet(id(1), 0, 1),
                SequenceItem.Page(1),
                SequenceItem.Page(2), SequenceItem.Sheet(id(2), 2, 1)
            ),
            sequence.items
        )
        assertNull(sequence.prev(SequenceItem.Page(0)))
        assertNull(sequence.next(SequenceItem.Sheet(id(2), 2, 1)))
    }

    @Test fun `sheets on one page order by rank, then creation time, then id, whatever order they arrive in`() {
        val sheets = listOf(
            placed(3, pageIndex = 0, rank = 5L, createdAt = 10L),
            placed(2, pageIndex = 0, rank = 5L, createdAt = 10L),
            placed(4, pageIndex = 0, rank = 5L, createdAt = 1L),
            placed(1, pageIndex = 0, rank = -3L, createdAt = 99L)
        )

        val expected = listOf(id(1), id(4), id(2), id(3))
        for (shuffled in listOf(sheets, sheets.reversed(), sheets.shuffled(java.util.Random(7)))) {
            val order = ReadingSequence.build(pageCount = 1, sheets = shuffled).items
                .filterIsInstance<SequenceItem.Sheet>()

            assertEquals(expected, order.map { it.id })
            assertEquals(listOf(1, 2, 3, 4), order.map { it.ordinal })
        }
    }

    @Test fun `a sheet is found by its id even through an item built with a stale ordinal`() {
        val sequence = ReadingSequence.build(pageCount = 2, sheets = listOf(placed(1, 0, rank = 0L), placed(2, 0, rank = STEP)))

        assertEquals(2, sequence.indexOf(SequenceItem.Sheet(id(2), 0, 1)))
        assertEquals(SequenceLabel(1, 2), sequence.label(SequenceItem.Sheet(id(2), 0, 1)))
    }

    @Test fun `an item that is not in the sequence has no index`() {
        val sequence = ReadingSequence.build(pageCount = 2, sheets = emptyList())

        assertEquals(-1, sequence.indexOf(SequenceItem.Page(2)))
        assertEquals(-1, sequence.indexOf(SequenceItem.Sheet(id(9), 0, 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stepping from an item that is not in the sequence is rejected`() {
        ReadingSequence.build(pageCount = 2, sheets = emptyList()).next(SequenceItem.Page(5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `two sheets with the same id are rejected`() {
        ReadingSequence.build(pageCount = 2, sheets = listOf(placed(1, 0), placed(1, 1)))
    }

    @Test fun `anchors past the last page clamp to it and negative anchors clamp to the first page`() {
        val sequence = ReadingSequence.build(
            pageCount = 3,
            sheets = listOf(placed(1, pageIndex = 40), placed(2, pageIndex = -1))
        )

        assertEquals(
            listOf(
                SequenceItem.Page(0), SequenceItem.Sheet(id(2), 0, 1),
                SequenceItem.Page(1),
                SequenceItem.Page(2), SequenceItem.Sheet(id(1), 2, 1)
            ),
            sequence.items
        )
    }

    @Test fun `a book with no pages has an empty sequence even when sheets are anchored to it`() {
        val sequence = ReadingSequence.build(pageCount = 0, sheets = listOf(placed(1, pageIndex = 0)))

        assertTrue(sequence.items.isEmpty())
    }

    @Test fun `inserting after a page with no sheets starts at rank zero`() {
        val sequence = ReadingSequence.build(pageCount = 2, sheets = emptyList())

        assertEquals(SheetInsertion.Ranked(pageIndex = 1, rank = 0L), sequence.insertionAfter(SequenceItem.Page(1)))
    }

    @Test fun `inserting after a page places the new sheet before that page's existing sheets`() {
        val sequence = ReadingSequence.build(pageCount = 2, sheets = listOf(placed(1, 0, rank = 0L), placed(2, 0, rank = STEP)))

        assertEquals(SheetInsertion.Ranked(pageIndex = 0, rank = -STEP), sequence.insertionAfter(SequenceItem.Page(0)))
    }

    @Test fun `inserting after the last sheet of a page steps past its rank`() {
        val sequence = ReadingSequence.build(pageCount = 2, sheets = listOf(placed(1, 0, rank = 0L), placed(2, 0, rank = STEP)))

        assertEquals(SheetInsertion.Ranked(pageIndex = 0, rank = 2 * STEP), sequence.insertionAfter(SequenceItem.Sheet(id(2), 0, 2)))
    }

    @Test fun `inserting between two sheets takes the midpoint of their ranks`() {
        val sequence = ReadingSequence.build(pageCount = 2, sheets = listOf(placed(1, 1, rank = 0L), placed(2, 1, rank = STEP)))

        assertEquals(SheetInsertion.Ranked(pageIndex = 1, rank = STEP / 2), sequence.insertionAfter(SequenceItem.Sheet(id(1), 1, 1)))
    }

    @Test fun `the midpoint never overflows between ranks at opposite ends of the range`() {
        val sequence = ReadingSequence.build(pageCount = 1, sheets = listOf(placed(1, 0, rank = Long.MIN_VALUE), placed(2, 0, rank = Long.MAX_VALUE)))

        val insertion = sequence.insertionAfter(SequenceItem.Sheet(id(1), 0, 1)) as SheetInsertion.Ranked

        assertTrue(insertion.rank > Long.MIN_VALUE && insertion.rank < Long.MAX_VALUE)
    }

    @Test fun `inserting between two sheets of equal rank rebalances the page in its current order`() {
        val sequence = ReadingSequence.build(
            pageCount = 1,
            sheets = listOf(placed(1, 0, rank = 0L, createdAt = 1L), placed(2, 0, rank = 0L, createdAt = 2L), placed(3, 0, rank = 0L, createdAt = 3L))
        )

        val insertion = sequence.insertionAfter(SequenceItem.Sheet(id(1), 0, 1))

        assertEquals(
            SheetInsertion.Rebalanced(pageIndex = 0, rank = STEP, reranked = linkedMapOf(id(1) to 0L, id(2) to 2 * STEP, id(3) to 3 * STEP)),
            insertion
        )
    }

    @Test fun `inserting before a first sheet already at the lowest rank rebalances`() {
        val sequence = ReadingSequence.build(pageCount = 1, sheets = listOf(placed(1, 0, rank = Long.MIN_VALUE + 1)))

        assertEquals(
            SheetInsertion.Rebalanced(pageIndex = 0, rank = 0L, reranked = linkedMapOf(id(1) to STEP)),
            sequence.insertionAfter(SequenceItem.Page(0))
        )
    }

    @Test fun `inserting after a last sheet already at the highest rank rebalances`() {
        val sequence = ReadingSequence.build(pageCount = 1, sheets = listOf(placed(1, 0, rank = Long.MAX_VALUE - 1)))

        assertEquals(
            SheetInsertion.Rebalanced(pageIndex = 0, rank = STEP, reranked = linkedMapOf(id(1) to 0L)),
            sequence.insertionAfter(SequenceItem.Sheet(id(1), 0, 1))
        )
    }
}
