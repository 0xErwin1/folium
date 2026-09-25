package com.folium.reader.core.pdf

/**
 * One chapter of a reflowable book under one layout: which document pages it occupies, in order,
 * and how much extracted text each of them carries. It answers both directions between a
 * [ReadingPosition.characterOffset] and a page without touching the engine again.
 *
 * A page starts at the sum of the text on the pages before it. [pageOf] returns the first page
 * whose text ends past the offset, falling back to the chapter's last page when none does, which
 * is exactly where a linear walk that accumulates text page by page and stops once it has passed
 * the offset would land. A page without text therefore never owns an offset: it shares its start
 * with the next page, and the offset resolves forward to the first page that carries text.
 *
 * The index is only valid for the layout it was measured under; a relayout moves every boundary.
 */
class ChapterOffsetIndex(pageIndices: List<Int>, pageTextLengths: List<Int>) {
    private val pages: IntArray = pageIndices.toIntArray()
    private val endOffsets: IntArray

    init {
        require(pageIndices.size == pageTextLengths.size) {
            "pageIndices and pageTextLengths must be the same size, were ${pageIndices.size} and ${pageTextLengths.size}"
        }
        require(pageTextLengths.all { it >= 0 }) { "page text lengths must be non-negative, were $pageTextLengths" }

        var consumed = 0
        endOffsets = IntArray(pageTextLengths.size) { i ->
            consumed = Math.addExact(consumed, pageTextLengths[i])
            consumed
        }
    }

    /** How many pages the chapter occupies under the measured layout. */
    val pageCount: Int get() = pages.size

    /**
     * The document page [characterOffset] falls on, or null when the chapter has no pages at all.
     * An offset at or past the chapter's end resolves to its last page.
     */
    fun pageOf(characterOffset: Int): Int? {
        require(characterOffset >= 0) { "characterOffset must be non-negative, was $characterOffset" }
        if (pages.isEmpty()) return null

        var low = 0
        var high = endOffsets.size - 1
        while (low < high) {
            val middle = (low + high) ushr 1
            if (endOffsets[middle] > characterOffset) high = middle else low = middle + 1
        }

        return pages[low]
    }

    /** The character offset [pageInChapter] starts at: the text on every page before it. */
    fun startOffsetOf(pageInChapter: Int): Int {
        require(pageInChapter in pages.indices) { "pageInChapter must be in 0 until $pageCount, was $pageInChapter" }
        return if (pageInChapter == 0) 0 else endOffsets[pageInChapter - 1]
    }
}
