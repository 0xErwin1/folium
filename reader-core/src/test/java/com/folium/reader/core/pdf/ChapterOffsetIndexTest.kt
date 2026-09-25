package com.folium.reader.core.pdf

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ChapterOffsetIndexTest {

    private val chapter = ChapterOffsetIndex(
        pageIndices = listOf(10, 11, 12),
        pageTextLengths = listOf(100, 50, 200)
    )

    @Test fun offsetZeroResolvesToTheChaptersFirstPage() {
        assertEquals(10, chapter.pageOf(0))
    }

    @Test fun offsetInsideAPageResolvesToThatPage() {
        assertEquals(10, chapter.pageOf(99))
        assertEquals(11, chapter.pageOf(120))
        assertEquals(12, chapter.pageOf(349))
    }

    /** A page starts at the sum of the text before it, so an offset exactly there belongs to it. */
    @Test fun offsetExactlyAtAPageBoundaryResolvesToThePageStartingThere() {
        assertEquals(11, chapter.pageOf(100))
        assertEquals(12, chapter.pageOf(150))
    }

    @Test fun offsetAtOrBeyondTheChaptersEndResolvesToItsLastPage() {
        assertEquals(12, chapter.pageOf(350))
        assertEquals(12, chapter.pageOf(Int.MAX_VALUE))
    }

    @Test fun aChapterWithNoPagesResolvesNothing() {
        val empty = ChapterOffsetIndex(pageIndices = emptyList(), pageTextLengths = emptyList())

        assertNull(empty.pageOf(0))
        assertNull(empty.pageOf(42))
    }

    @Test fun aNegativeOffsetIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { chapter.pageOf(-1) }
    }

    /**
     * A page without text, such as a full-page image, starts at the same offset as the page after
     * it, and the walk it mirrors only stops once the text consumed passes the offset, so the
     * offset lands on the first following page that carries text.
     */
    @Test fun pagesWithoutTextAreSkippedUntilTextPassesTheOffset() {
        val withImages = ChapterOffsetIndex(
            pageIndices = listOf(0, 1, 2, 3),
            pageTextLengths = listOf(0, 30, 0, 30)
        )

        assertEquals(1, withImages.pageOf(0))
        assertEquals(3, withImages.pageOf(30))
        assertEquals(3, withImages.pageOf(60))
    }

    @Test fun aChapterWithoutAnyTextResolvesEveryOffsetToItsLastPage() {
        val silent = ChapterOffsetIndex(pageIndices = listOf(4, 5), pageTextLengths = listOf(0, 0))

        assertEquals(5, silent.pageOf(0))
        assertEquals(5, silent.pageOf(9))
    }

    @Test fun startOffsetOfAPageIsTheTextBeforeIt() {
        assertEquals(0, chapter.startOffsetOf(0))
        assertEquals(100, chapter.startOffsetOf(1))
        assertEquals(150, chapter.startOffsetOf(2))
    }

    @Test fun startOffsetOfAPageOutsideTheChapterIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { chapter.startOffsetOf(-1) }
        assertThrows(IllegalArgumentException::class.java) { chapter.startOffsetOf(3) }
    }

    @Test fun aPageWithTextResolvesBackToItselfFromItsStartOffset() {
        (0 until chapter.pageCount).forEach { pageInChapter ->
            assertEquals(10 + pageInChapter, chapter.pageOf(chapter.startOffsetOf(pageInChapter)))
        }
    }

    @Test fun pageIndicesAreReturnedAsGivenRatherThanAssumedContiguous() {
        val scattered = ChapterOffsetIndex(pageIndices = listOf(3, 9), pageTextLengths = listOf(5, 5))

        assertEquals(3, scattered.pageOf(4))
        assertEquals(9, scattered.pageOf(5))
    }

    @Test fun mismatchedOrNegativeInputsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ChapterOffsetIndex(pageIndices = listOf(0, 1), pageTextLengths = listOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterOffsetIndex(pageIndices = listOf(0), pageTextLengths = listOf(-1))
        }
    }

    /**
     * Checks the binary search against a transcription of the engine's linear resolve: walk the
     * pages accumulating text and stop on the first page whose end passes the offset, else keep
     * the last page walked.
     */
    @Test fun agreesWithTheLinearChapterWalkOnGeneratedChapters() {
        val random = Random(20260925)

        repeat(500) {
            val lengths = List(random.nextInt(1, 12)) { if (random.nextInt(4) == 0) 0 else random.nextInt(1, 40) }
            val pages = List(lengths.size) { 7 + it }
            val index = ChapterOffsetIndex(pages, lengths)

            (0..lengths.sum() + 3).forEach { offset ->
                assertEquals("lengths=$lengths offset=$offset", linearWalk(pages, lengths, offset), index.pageOf(offset))
            }
        }
    }

    private fun linearWalk(pages: List<Int>, lengths: List<Int>, offset: Int): Int {
        var consumed = 0
        var resolved = pages.first()
        for (i in pages.indices) {
            resolved = pages[i]
            consumed += lengths[i]
            if (consumed > offset) break
        }
        return resolved
    }
}
