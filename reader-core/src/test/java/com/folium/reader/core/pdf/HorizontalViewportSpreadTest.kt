package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the facing-page spread ([HorizontalViewportState.pagesPerView] `2`): normalization to the
 * spread's left page, paging by two, clamping at both ends of an odd document, the zoom transition
 * that collapses a spread onto one page and restores it, and the widened prefetch window. Single
 * page behavior (`pagesPerView` `1`) is unaffected and stays covered by
 * [HorizontalViewportReducerTest] and [HorizontalViewportModelTest].
 */
class HorizontalViewportSpreadTest {

    private fun spreadState(pageCount: Int, currentPage: Int = 0): HorizontalViewportState =
        HorizontalViewportState.initial(pageCount).copy(currentPage = currentPage, pagesPerView = 2)

    @Test fun enteringASpreadNormalizesAnOddCurrentPageToItsLeftPage() {
        val single = HorizontalViewportState.initial(pageCount = 10).copy(currentPage = 5)
        val spread = HorizontalViewportReducer.reduce(single, GestureIntent.SetPagesPerView(2))
        assertEquals(4, spread.currentPage)
        assertEquals(2, spread.pagesPerView)
        assertEquals(single.generation + 1, spread.generation)
    }

    @Test fun enteringASpreadOnAnAlreadyEvenPageDoesNotMoveTheCurrentPage() {
        val single = HorizontalViewportState.initial(pageCount = 10).copy(currentPage = 4)
        val spread = HorizontalViewportReducer.reduce(single, GestureIntent.SetPagesPerView(2))
        assertEquals(4, spread.currentPage)
    }

    @Test fun settingTheSamePagesPerViewIsANoOp() {
        val single = HorizontalViewportState.initial(pageCount = 10)
        val same = HorizontalViewportReducer.reduce(single, GestureIntent.SetPagesPerView(1))
        assertEquals(single, same)
    }

    @Test fun leavingASpreadKeepsTheLeftPageAsTheCurrentSinglePage() {
        val spread = spreadState(pageCount = 10, currentPage = 4)
        val single = HorizontalViewportReducer.reduce(spread, GestureIntent.SetPagesPerView(1))
        assertEquals(4, single.currentPage)
        assertEquals(1, single.pagesPerView)
    }

    @Test fun pageForwardAndBackMoveByTwoWhileASpreadIsFitted() {
        val spread = spreadState(pageCount = 10, currentPage = 2)
        val forward = HorizontalViewportReducer.reduce(spread, GestureIntent.PageForward)
        assertEquals(4, forward.currentPage)

        val back = HorizontalViewportReducer.reduce(forward, GestureIntent.PageBack)
        assertEquals(2, back.currentPage)
    }

    @Test fun pageForwardClampsAtAnOddDocumentsLoneLastPageAndDoesNotRollGeneration() {
        val spread = spreadState(pageCount = 5, currentPage = 4)
        val forward = HorizontalViewportReducer.reduce(spread, GestureIntent.PageForward)
        assertEquals(4, forward.currentPage)
        assertEquals(spread.generation, forward.generation)
    }

    @Test fun pageBackClampsAtTheFirstSpreadAndDoesNotRollGeneration() {
        val spread = spreadState(pageCount = 10, currentPage = 0)
        val back = HorizontalViewportReducer.reduce(spread, GestureIntent.PageBack)
        assertEquals(0, back.currentPage)
        assertEquals(spread.generation, back.generation)
    }

    @Test fun flingingToAnOddPageOpensTheSpreadThatContainsIt() {
        val spread = spreadState(pageCount = 10)
        val flung = HorizontalViewportReducer.reduce(spread, GestureIntent.FlingToPage(7))
        assertEquals(6, flung.currentPage)
    }

    @Test fun anOddPageCountLeavesTheLastPageAloneOnTheLeftOfAnEmptyRightSlot() {
        val spread = spreadState(pageCount = 5, currentPage = 2)
        val flungToTheEnd = HorizontalViewportReducer.reduce(spread, GestureIntent.FlingToPage(4))
        assertEquals(4, flungToTheEnd.currentPage)

        val selection = HorizontalViewportPageSelector.select(flungToTheEnd)
        assertEquals(RenderPriority.VISIBLE, selection.priorityOf(4))
        assertNull(selection.priorityOf(5))
    }

    @Test fun zoomingInOutOfASpreadRequiresAFocusPageAmongTheTwoVisiblePages() {
        val spread = spreadState(pageCount = 10, currentPage = 4)
        assertFails { HorizontalViewportReducer.reduce(spread, GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f))) }
        assertFails {
            HorizontalViewportReducer.reduce(spread, GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f), focusPage = 8))
        }
    }

    @Test fun zoomingInOnTheRightPageOfASpreadMakesItTheCurrentSinglePage() {
        val spread = spreadState(pageCount = 10, currentPage = 4)
        val zoomedIn = HorizontalViewportReducer.reduce(
            spread,
            GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f), focusPage = 5)
        )
        assertEquals(5, zoomedIn.currentPage)
        assertEquals(2f, zoomedIn.zoom.scale)
        assertEquals(2, zoomedIn.pagesPerView)
    }

    @Test fun zoomingInOnTheLeftPageOfASpreadKeepsItAsTheCurrentSinglePage() {
        val spread = spreadState(pageCount = 10, currentPage = 4)
        val zoomedIn = HorizontalViewportReducer.reduce(
            spread,
            GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f), focusPage = 4)
        )
        assertEquals(4, zoomedIn.currentPage)
        assertEquals(2f, zoomedIn.zoom.scale)
    }

    @Test fun whileZoomedInASpreadShowsOnlyTheFocusedPage() {
        val spread = spreadState(pageCount = 10, currentPage = 4)
        val zoomedIn = HorizontalViewportReducer.reduce(
            spread,
            GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f), focusPage = 5)
        )
        val selection = HorizontalViewportPageSelector.select(zoomedIn)
        assertEquals(RenderPriority.VISIBLE, selection.priorityOf(5))
        assertNotEquals(RenderPriority.VISIBLE, selection.priorityOf(4))
    }

    @Test fun zoomingBackOutToTheMinimumScaleRestoresTheSpreadContainingTheFocusedPage() {
        val spread = spreadState(pageCount = 10, currentPage = 4)
        val zoomedIn = HorizontalViewportReducer.reduce(
            spread,
            GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f), focusPage = 5)
        )
        val zoomedBackOut = HorizontalViewportReducer.reduce(zoomedIn, GestureIntent.ZoomBy(0.5f, PageSpacePoint(0.5f, 0.5f)))
        assertEquals(MIN_ZOOM_SCALE, zoomedBackOut.zoom.scale)
        assertEquals(4, zoomedBackOut.currentPage)
    }

    @Test fun resetZoomOutOfAZoomedSpreadPageRestoresTheSpreadContainingIt() {
        val spread = spreadState(pageCount = 10, currentPage = 4)
        val zoomedIn = HorizontalViewportReducer.reduce(
            spread,
            GestureIntent.ZoomBy(3f, PageSpacePoint(0.5f, 0.5f), focusPage = 5)
        )
        val reset = HorizontalViewportReducer.reduce(zoomedIn, GestureIntent.ResetZoom)
        assertEquals(MIN_ZOOM_SCALE, reset.zoom.scale)
        assertEquals(4, reset.currentPage)
        assertEquals(2, reset.pagesPerView)
    }

    @Test fun changingFitModeWhileZoomedIntoASpreadPageRestoresTheSpreadContainingIt() {
        val spread = spreadState(pageCount = 10, currentPage = 4)
        val zoomedIn = HorizontalViewportReducer.reduce(
            spread,
            GestureIntent.ZoomBy(3f, PageSpacePoint(0.5f, 0.5f), focusPage = 5)
        )
        val refitted = HorizontalViewportReducer.reduce(zoomedIn, GestureIntent.SetFitMode(PageFitMode.PAGE))
        assertEquals(MIN_ZOOM_SCALE, refitted.zoom.scale)
        assertEquals(4, refitted.currentPage)
    }

    @Test fun aFocusPageIsIgnoredWhenAlreadyZoomedInPastTheMinimumScale() {
        val spread = spreadState(pageCount = 10, currentPage = 4)
        val zoomedIn = HorizontalViewportReducer.reduce(
            spread,
            GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f), focusPage = 5)
        )
        val zoomedFurther = HorizontalViewportReducer.reduce(
            zoomedIn,
            GestureIntent.ZoomBy(1.5f, zoomedIn.zoom.center, focusPage = 4)
        )
        assertEquals(5, zoomedFurther.currentPage)
        assertEquals(3f, zoomedFurther.zoom.scale, 0.001f)
    }

    @Test fun selectionOfAFittedSpreadMarksBothVisiblePagesAndTreatsTheAdjacentSpreadsAsOneUnitEach() {
        val spread = spreadState(pageCount = 20, currentPage = 8)
        val selection = HorizontalViewportPageSelector.select(spread)

        assertEquals(RenderPriority.VISIBLE, selection.priorityOf(8))
        assertEquals(RenderPriority.VISIBLE, selection.priorityOf(9))
        assertEquals(RenderPriority.NEAR, selection.priorityOf(6))
        assertEquals(RenderPriority.NEAR, selection.priorityOf(7))
        assertEquals(RenderPriority.NEAR, selection.priorityOf(10))
        assertEquals(RenderPriority.NEAR, selection.priorityOf(11))
        assertEquals(RenderPriority.PREFETCH, selection.priorityOf(2))
        assertEquals(RenderPriority.PREFETCH, selection.priorityOf(3))
        assertEquals(RenderPriority.PREFETCH, selection.priorityOf(4))
        assertEquals(RenderPriority.PREFETCH, selection.priorityOf(5))
        assertEquals(RenderPriority.PREFETCH, selection.priorityOf(12))
        assertEquals(RenderPriority.PREFETCH, selection.priorityOf(13))
        assertEquals(RenderPriority.PREFETCH, selection.priorityOf(14))
        assertEquals(RenderPriority.PREFETCH, selection.priorityOf(15))
        assertNull(selection.priorityOf(0))
        assertNull(selection.priorityOf(1))
        assertNull(selection.priorityOf(16))
        assertEquals(14, selection.size)
    }

    @Test fun selectionOfALoneLastPageSpreadNeverRequestsAnOutOfBoundsRightSlot() {
        val spread = spreadState(pageCount = 5, currentPage = 4)
        val selection = HorizontalViewportPageSelector.select(spread)
        assertTrue(selection.map { it.pageIndex }.all { it in 0 until 5 })
        assertEquals(RenderPriority.VISIBLE, selection.priorityOf(4))
    }

    @Test fun leftPageCurrentPageInvariantIsRejectedWhenFittedButAllowedWhileZoomed() {
        assertFails { HorizontalViewportState.initial(pageCount = 10).copy(currentPage = 3, pagesPerView = 2) }

        val zoomed = HorizontalViewportZoom(2f, PageSpacePoint(0.5f, 0.5f))
        HorizontalViewportState.initial(pageCount = 10).copy(currentPage = 3, pagesPerView = 2, zoom = zoomed)
    }

    @Test fun pagesPerViewOutsideOneOrTwoIsRejected() {
        assertFails { HorizontalViewportState.initial(pageCount = 10).copy(pagesPerView = 0) }
        assertFails { HorizontalViewportState.initial(pageCount = 10).copy(pagesPerView = 3) }
    }

    private fun List<ViewportPageRequest>.priorityOf(pageIndex: Int): RenderPriority? =
        firstOrNull { it.pageIndex == pageIndex }?.priority

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
