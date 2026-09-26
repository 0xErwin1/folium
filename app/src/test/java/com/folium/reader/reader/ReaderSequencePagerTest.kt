package com.folium.reader.reader

import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.sequence.PlacedSheet
import com.folium.reader.core.sequence.ReadingSequence
import com.folium.reader.core.sequence.SequenceItem
import com.folium.reader.core.sequence.SequenceLabel
import com.folium.reader.core.sequence.SpreadUnit
import com.folium.reader.core.sequence.spreadUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decisions behind [ReaderScreen]'s pager once a book's sheets are interleaved with its
 * pages: which units it turns through, when its chevrons turn, which gestures a unit keeps, what an
 * edge tap does over a sheet, and what the footer names while a sheet is current.
 */
class ReaderSequencePagerTest {

    private val sheetA = SheetId("sheet-a")
    private val sheetB = SheetId("sheet-b")

    private fun units(pageCount: Int, pagesPerView: Int, vararg sheetsOnPage: Pair<SheetId, Int>): List<SpreadUnit> {
        val placed = sheetsOnPage.mapIndexed { index, (id, page) -> PlacedSheet(id, page, rank = index.toLong(), createdAtEpochMillis = 0) }
        return spreadUnits(ReadingSequence.build(pageCount, placed), pagesPerView).units
    }

    @Test fun `without a published sequence the pager turns through today's pages`() {
        val model = readerPagerModel(ReaderSequenceState(), currentPage = 41, pageCount = 600, pagesPerView = 1)

        assertFalse(model.sequenced)
        assertEquals(600, model.units.size)
        assertEquals(41, model.current)
        assertEquals(SpreadUnit(SequenceItem.Page(41), null), model.units[41])
    }

    @Test fun `without a published sequence a spread pager pairs pages exactly as today`() {
        val model = readerPagerModel(ReaderSequenceState(), currentPage = 614, pageCount = 615, pagesPerView = 2)

        assertEquals(pagerPageCount(615, 2), model.units.size)
        assertEquals(pagerPageFor(614, 2), model.current)
        assertEquals(SpreadUnit(SequenceItem.Page(20), SequenceItem.Page(21)), model.units[10])
    }

    @Test fun `a published sequence drives the pager by unit`() {
        val sequenceUnits = units(3, 1, sheetA to 0)
        val sequence = ReaderSequenceState(sequenceUnits, currentUnit = 1, currentSheet = sheetA)

        val model = readerPagerModel(sequence, currentPage = 0, pageCount = 3, pagesPerView = 1)

        assertTrue(model.sequenced)
        assertEquals(sequenceUnits, model.units)
        assertEquals(1, model.current)
    }

    @Test fun `the chevrons turn until either end of the units`() {
        val sequenceUnits = units(2, 1, sheetA to 1)

        val first = readerPagerModel(ReaderSequenceState(sequenceUnits, currentUnit = 0), 0, 2, 1)
        assertFalse(first.backEnabled)
        assertTrue(first.forwardEnabled)

        val lastPage = readerPagerModel(ReaderSequenceState(sequenceUnits, currentUnit = 1), 1, 2, 1)
        assertTrue(lastPage.backEnabled)
        assertTrue(lastPage.forwardEnabled)

        val lastSheet = readerPagerModel(ReaderSequenceState(sequenceUnits, currentUnit = 2), 1, 2, 1)
        assertTrue(lastSheet.backEnabled)
        assertFalse(lastSheet.forwardEnabled)
    }

    @Test fun `a book with no pages turns neither way`() {
        val model = readerPagerModel(ReaderSequenceState(), currentPage = 0, pageCount = 0, pagesPerView = 1)

        assertFalse(model.backEnabled)
        assertFalse(model.forwardEnabled)
    }

    @Test fun `a unit showing a sheet anywhere counts as a sheet unit`() {
        assertFalse(unitShowsSheet(SpreadUnit(SequenceItem.Page(3), SequenceItem.Page(4))))
        assertTrue(unitShowsSheet(SpreadUnit(SequenceItem.Sheet(sheetA, 3, 1), null)))
        assertTrue(unitShowsSheet(SpreadUnit(SequenceItem.Page(3), SequenceItem.Sheet(sheetA, 3, 1))))
        assertFalse(unitShowsSheet(null))
    }

    @Test fun `a plain page unit keeps swiping and zooming exactly as today`() {
        val gestures = unitGestures(SpreadUnit(SequenceItem.Page(4), SequenceItem.Page(5)), presenterPage = 4, zoomed = false)

        assertTrue(gestures.swipe)
        assertTrue(gestures.zoom)
    }

    @Test fun `a zoomed page stops swiping but keeps zooming`() {
        val gestures = unitGestures(SpreadUnit(SequenceItem.Page(4), null), presenterPage = 4, zoomed = true)

        assertFalse(gestures.swipe)
        assertTrue(gestures.zoom)
    }

    @Test fun `a unit with a sheet neither swipes nor zooms`() {
        val gestures = unitGestures(SpreadUnit(SequenceItem.Page(4), SequenceItem.Sheet(sheetA, 4, 1)), presenterPage = 4, zoomed = false)

        assertFalse(gestures.swipe)
        assertFalse(gestures.zoom)
    }

    @Test fun `a lone page the presenter is not on does not zoom the presenter's page`() {
        val gestures = unitGestures(SpreadUnit(SequenceItem.Page(5), null), presenterPage = 4, zoomed = false)

        assertTrue(gestures.swipe)
        assertFalse(gestures.zoom)
    }

    @Test fun `no pages at all keeps today's gestures`() {
        val gestures = unitGestures(null, presenterPage = 0, zoomed = false)

        assertTrue(gestures.swipe)
        assertTrue(gestures.zoom)
    }

    @Test fun `writing on a unit of pages stops swiping but keeps zooming`() {
        val gestures = unitGestures(SpreadUnit(SequenceItem.Page(4), SequenceItem.Page(5)), presenterPage = 4, zoomed = false, writing = true)

        assertFalse(gestures.swipe)
        assertTrue(gestures.zoom)
    }

    @Test fun `a zoomed page pans under one finger while reading`() {
        val gestures = unitGestures(SpreadUnit(SequenceItem.Page(4), null), presenterPage = 4, zoomed = true)

        assertTrue(gestures.pan)
    }

    @Test fun `writing on a zoomed unit of pages leaves one finger to the pen and keeps zooming`() {
        val gestures = unitGestures(SpreadUnit(SequenceItem.Page(4), null), presenterPage = 4, zoomed = true, writing = true)

        assertFalse(gestures.swipe)
        assertFalse(gestures.pan)
        assertTrue(gestures.zoom)
    }

    @Test fun `writing leaves a unit with a sheet exactly as it was`() {
        val unit = SpreadUnit(SequenceItem.Page(4), SequenceItem.Sheet(sheetA, 4, 1))

        assertEquals(unitGestures(unit, presenterPage = 4, zoomed = false), unitGestures(unit, presenterPage = 4, zoomed = false, writing = true))
    }

    @Test fun `a sheet alone covers the whole page area`() {
        assertEquals(0f, sheetStartPx(SpreadUnit(SequenceItem.Sheet(sheetA, 4, 1), null), slotWidthPx = null, gutterPx = 0, widthPx = 1000)!!, 0f)
    }

    @Test fun `a sheet beside its page starts at the gutter's midpoint`() {
        val unit = SpreadUnit(SequenceItem.Page(4), SequenceItem.Sheet(sheetA, 4, 1))

        assertEquals(410f, sheetStartPx(unit, slotWidthPx = 400, gutterPx = 20, widthPx = 820)!!, 0f)
    }

    @Test fun `a unit of pages has no sheet area`() {
        assertNull(sheetStartPx(SpreadUnit(SequenceItem.Page(4), SequenceItem.Page(5)), slotWidthPx = 400, gutterPx = 20, widthPx = 820))
    }

    @Test fun `taps on pages turn at the edges and toggle the chrome in the middle`() {
        assertEquals(PageTap.BACK, pageTap(xPx = 10f, widthPx = 1000, zoomed = false, sheetStartPx = null))
        assertEquals(PageTap.FORWARD, pageTap(xPx = 990f, widthPx = 1000, zoomed = false, sheetStartPx = null))
        assertEquals(PageTap.TOGGLE_CHROME, pageTap(xPx = 500f, widthPx = 1000, zoomed = false, sheetStartPx = null))
        assertEquals(PageTap.TOGGLE_CHROME, pageTap(xPx = 990f, widthPx = 1000, zoomed = true, sheetStartPx = null))
    }

    @Test fun `a tap on a sheet does nothing, even at the edge`() {
        assertEquals(PageTap.NONE, pageTap(xPx = 990f, widthPx = 1000, zoomed = false, sheetStartPx = 0f))
        assertEquals(PageTap.NONE, pageTap(xPx = 10f, widthPx = 1000, zoomed = false, sheetStartPx = 0f))
        assertEquals(PageTap.NONE, pageTap(xPx = 990f, widthPx = 1000, zoomed = false, sheetStartPx = 500f))
    }

    @Test fun `beside a sheet the page still turns back but never hides the chrome`() {
        assertEquals(PageTap.BACK, pageTap(xPx = 10f, widthPx = 1000, zoomed = false, sheetStartPx = 500f))
        assertEquals(PageTap.NONE, pageTap(xPx = 400f, widthPx = 1000, zoomed = false, sheetStartPx = 500f))
        assertEquals(PageTap.NONE, pageTap(xPx = 400f, widthPx = 1000, zoomed = true, sheetStartPx = 500f))
    }

    @Test fun `while writing a tap on book pages neither turns nor hides the chrome`() {
        assertEquals(PageTap.NONE, pageTap(xPx = 10f, widthPx = 1000, zoomed = false, sheetStartPx = null, writing = true))
        assertEquals(PageTap.NONE, pageTap(xPx = 990f, widthPx = 1000, zoomed = false, sheetStartPx = null, writing = true))
        assertEquals(PageTap.NONE, pageTap(xPx = 500f, widthPx = 1000, zoomed = false, sheetStartPx = null, writing = true))
        assertEquals(PageTap.NONE, pageTap(xPx = 500f, widthPx = 1000, zoomed = true, sheetStartPx = null, writing = true))
    }

    @Test fun `while writing a unit with a sheet taps exactly as it did`() {
        assertEquals(PageTap.BACK, pageTap(xPx = 10f, widthPx = 1000, zoomed = false, sheetStartPx = 500f, writing = true))
        assertEquals(PageTap.NONE, pageTap(xPx = 990f, widthPx = 1000, zoomed = false, sheetStartPx = 500f, writing = true))
    }

    @Test fun `the footer names a sheet only while one is current`() {
        val onSheet = ReaderSequenceState(currentLabel = SequenceLabel(19, 1), currentSheet = sheetA)
        assertEquals(SequenceLabel(19, 1), sheetPosition(onSheet))

        val onPage = ReaderSequenceState(currentLabel = SequenceLabel(19, null))
        assertNull(sheetPosition(onPage))
        assertNull(sheetPosition(ReaderSequenceState()))
    }

    @Test fun `a sheet cell is labelled with its 1-based page and ordinal`() {
        assertEquals(SequenceLabel(19, 2), sheetLabelOf(SequenceItem.Sheet(sheetB, pageIndex = 18, ordinal = 2)))
    }

    @Test fun `a sheet cell is inset by the measured height of both chrome bars`() {
        assertEquals(SheetCellInsets(topPx = 100f, bottomPx = 140f), sheetCellInsets(100f, 140f, cellShowsSheet = true))
    }

    @Test fun `a book page cell keeps the whole page area under the chrome`() {
        assertEquals(SheetCellInsets(0f, 0f), sheetCellInsets(100f, 140f, cellShowsSheet = false))
    }

    @Test fun `a chrome bar not measured yet insets a sheet cell by nothing on that side`() {
        assertEquals(SheetCellInsets(topPx = 100f, bottomPx = 0f), sheetCellInsets(100f, null, cellShowsSheet = true))
        assertEquals(SheetCellInsets(topPx = 0f, bottomPx = 140f), sheetCellInsets(null, 140f, cellShowsSheet = true))
    }

    @Test fun `a negative chrome measurement never grows a sheet cell past the page area`() {
        assertEquals(SheetCellInsets(0f, 0f), sheetCellInsets(-4f, -1f, cellShowsSheet = true))
    }

    @Test fun `a settled sheet unit keeps the pager from turning under a stroke`() {
        assertFalse(pagerSwipeEnabled(unitSwipe = false, scrollInProgress = false, offsetFraction = 0f))
    }

    @Test fun `a swipe that reaches a sheet unit keeps scrolling until it settles`() {
        assertTrue(pagerSwipeEnabled(unitSwipe = false, scrollInProgress = true, offsetFraction = -0.3f))
    }

    @Test fun `a pager left between units on a sheet still swipes`() {
        assertTrue(pagerSwipeEnabled(unitSwipe = false, scrollInProgress = false, offsetFraction = 0.05f))
        assertTrue(pagerSwipeEnabled(unitSwipe = false, scrollInProgress = false, offsetFraction = -0.05f))
    }

    @Test fun `a book page unit swipes at rest`() {
        assertTrue(pagerSwipeEnabled(unitSwipe = true, scrollInProgress = false, offsetFraction = 0f))
    }

    @Test fun `a pager at rest between units snaps to the nearest one`() {
        assertTrue(pagerNeedsSnap(scrollInProgress = false, offsetFraction = 0.05f))
        assertTrue(pagerNeedsSnap(scrollInProgress = false, offsetFraction = -0.4f))
    }

    @Test fun `a pager still scrolling or on a unit is left alone`() {
        assertFalse(pagerNeedsSnap(scrollInProgress = true, offsetFraction = 0.05f))
        assertFalse(pagerNeedsSnap(scrollInProgress = false, offsetFraction = 0f))
        assertFalse(pagerNeedsSnap(scrollInProgress = false, offsetFraction = 0.0001f))
    }
}
