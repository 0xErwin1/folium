package com.folium.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure arithmetic behind [ReaderScreen]'s facing-page spread: whether a measured page area
 * qualifies, how a pager page maps to the reader's own page index and back across an odd page count,
 * which slot a gesture landed in, and which page numbers a spread's position label reads.
 */
class ReaderSpreadTest {

    @Test fun `a page area narrower than the threshold never qualifies`() {
        assertFalse(spreadEligible(pageAreaWidthPx = 800, pageAreaHeightPx = 400, minWidthPx = 840))
    }

    @Test fun `a wide page area that is also the taller shape does not qualify`() {
        assertFalse(spreadEligible(pageAreaWidthPx = 900, pageAreaHeightPx = 1200, minWidthPx = 840))
    }

    @Test fun `a page area wide enough and wider than it is tall qualifies`() {
        assertTrue(spreadEligible(pageAreaWidthPx = 1200, pageAreaHeightPx = 800, minWidthPx = 840))
    }

    @Test fun `the threshold itself qualifies`() {
        assertTrue(spreadEligible(pageAreaWidthPx = 840, pageAreaHeightPx = 400, minWidthPx = 840))
    }

    @Test fun `a single page maps straight through to its own pager page`() {
        assertEquals(41, pagerPageFor(currentPage = 41, pagesPerView = 1))
        assertEquals(41, currentPageFor(pagerPage = 41, pagesPerView = 1))
        assertEquals(600, pagerPageCount(pageCount = 600, pagesPerView = 1))
    }

    @Test fun `a spread's left page maps to the spread's own pager page and back`() {
        assertEquals(0, pagerPageFor(currentPage = 0, pagesPerView = 2))
        assertEquals(10, pagerPageFor(currentPage = 20, pagesPerView = 2))
        assertEquals(0, currentPageFor(pagerPage = 0, pagesPerView = 2))
        assertEquals(20, currentPageFor(pagerPage = 10, pagesPerView = 2))
    }

    @Test fun `an odd page count still gives its lone last page a pager page of its own`() {
        assertEquals(308, pagerPageCount(pageCount = 615, pagesPerView = 2))
        assertEquals(307, pagerPageFor(currentPage = 614, pagesPerView = 2))
        assertEquals(614, currentPageFor(pagerPage = 307, pagesPerView = 2))
    }

    @Test fun `an even page count needs exactly half as many pager pages`() {
        assertEquals(300, pagerPageCount(pageCount = 600, pagesPerView = 2))
    }

    @Test fun `a spread's right page is the very next one when it exists`() {
        assertEquals(21, spreadRightPage(leftPage = 20, pageCount = 600))
    }

    @Test fun `a lone last page has no right page`() {
        assertNull(spreadRightPage(leftPage = 614, pageCount = 615))
    }

    @Test fun `a point well inside the left slot hits it at its own local x`() {
        val hit = spreadSlotAt(xPx = 100f, slotWidthPx = 400, gutterPx = 20)
        assertEquals(0, hit.slotIndex)
        assertEquals(100f, hit.localXPx, 0f)
    }

    @Test fun `a point well inside the right slot hits it at its own local x`() {
        val hit = spreadSlotAt(xPx = 500f, slotWidthPx = 400, gutterPx = 20)
        assertEquals(1, hit.slotIndex)
        assertEquals(80f, hit.localXPx, 0f)
    }

    @Test fun `a point in the gutter resolves to whichever slot its half is closer to`() {
        val justLeftOfMidpoint = spreadSlotAt(xPx = 409f, slotWidthPx = 400, gutterPx = 20)
        assertEquals(0, justLeftOfMidpoint.slotIndex)

        val justRightOfMidpoint = spreadSlotAt(xPx = 411f, slotWidthPx = 400, gutterPx = 20)
        assertEquals(1, justRightOfMidpoint.slotIndex)
    }

    @Test fun `a hit is clamped into its slot's own page-space extent`() {
        val beyondTheLeftEdge = spreadSlotAt(xPx = -50f, slotWidthPx = 400, gutterPx = 20)
        assertEquals(0, beyondTheLeftEdge.slotIndex)
        assertEquals(0f, beyondTheLeftEdge.localXPx, 0f)

        val beyondTheRightEdge = spreadSlotAt(xPx = 10_000f, slotWidthPx = 400, gutterPx = 20)
        assertEquals(1, beyondTheRightEdge.slotIndex)
        assertEquals(400f, beyondTheRightEdge.localXPx, 0f)
    }

    @Test fun `a single page's label names just itself`() {
        val label = spreadPositionLabel(page = 19, pageCount = 600, pagesPerView = 1)
        assertEquals(SpreadPositionLabel(20, null), label)
    }

    @Test fun `a spread's label names both of its pages`() {
        val label = spreadPositionLabel(page = 18, pageCount = 615, pagesPerView = 2)
        assertEquals(SpreadPositionLabel(19, 20), label)
    }

    @Test fun `a lone last page's label falls back to naming just itself`() {
        val label = spreadPositionLabel(page = 614, pageCount = 615, pagesPerView = 2)
        assertEquals(SpreadPositionLabel(615, null), label)
    }

    /** A scrubber drag can land on either of a spread's two pages, not only its left one. */
    @Test fun `landing on a spread's right page still labels the whole spread`() {
        val label = spreadPositionLabel(page = 19, pageCount = 615, pagesPerView = 2)
        assertEquals(SpreadPositionLabel(19, 20), label)
    }
}
