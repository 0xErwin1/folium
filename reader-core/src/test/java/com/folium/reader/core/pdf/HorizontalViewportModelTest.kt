package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers construction invariants of [HorizontalViewportState]/[HorizontalViewportZoom] and the
 * page-selection/prefetch-window behavior of [HorizontalViewportPageSelector]. Gesture-driven
 * transitions live in [HorizontalViewportReducerTest].
 */
class HorizontalViewportModelTest {

    @Test fun initialStateFitsThePageAndShowsChrome() {
        val state = HorizontalViewportState.initial(pageCount = 10)
        assertEquals(10, state.pageCount)
        assertEquals(0, state.currentPage)
        assertEquals(MIN_ZOOM_SCALE, state.zoom.scale)
        assertEquals(PageSpacePoint(0.5f, 0.5f), state.zoom.center)
        assertTrue(state.chromeVisible)
        assertEquals(0L, state.generation)
    }

    @Test fun initialStateToleratesAnEmptyDocument() {
        val state = HorizontalViewportState.initial(pageCount = 0)
        assertEquals(0, state.currentPage)
    }

    @Test fun currentPageMustBeWithinBoundsOfPageCount() {
        assertFails { HorizontalViewportState.initial(pageCount = 3).copy(currentPage = 3) }
        assertFails { HorizontalViewportState.initial(pageCount = 3).copy(currentPage = -1) }
        assertFails { HorizontalViewportState.initial(pageCount = 0).copy(currentPage = 1) }
    }

    @Test fun negativePageCountIsRejected() {
        assertFails { HorizontalViewportState.initial(pageCount = -1) }
    }

    @Test fun zoomScaleMustStayWithinTheDocumentedBounds() {
        assertFails { HorizontalViewportZoom(MIN_ZOOM_SCALE - 0.01f, PageSpacePoint(0.5f, 0.5f)) }
        assertFails { HorizontalViewportZoom(MAX_ZOOM_SCALE + 0.01f, PageSpacePoint(0.5f, 0.5f)) }
        HorizontalViewportZoom(MIN_ZOOM_SCALE, PageSpacePoint(0.5f, 0.5f))
        HorizontalViewportZoom(MAX_ZOOM_SCALE, PageSpacePoint(0.5f, 0.5f))
    }

    @Test fun selectionAtTheStartOfTheDocumentMarksTheCurrentPageVisibleAndOnlyForwardNeighboursNearOrPrefetch() {
        val state = HorizontalViewportState.initial(pageCount = 10)
        val selection = HorizontalViewportPageSelector.select(state)

        assertEquals(RenderPriority.VISIBLE, selection.priorityOf(0))
        assertEquals(RenderPriority.NEAR, selection.priorityOf(1))
        assertEquals(RenderPriority.PREFETCH, selection.priorityOf(2))
        assertEquals(RenderPriority.PREFETCH, selection.priorityOf(3))
        assertEquals(null, selection.priorityOf(4))
        assertEquals(4, selection.size)
    }

    @Test fun selectionAroundAMiddlePageCoversBothDirections() {
        val state = HorizontalViewportState.initial(pageCount = 20).copy(currentPage = 10)
        val selection = HorizontalViewportPageSelector.select(state)

        assertEquals(RenderPriority.VISIBLE, selection.priorityOf(10))
        assertEquals(RenderPriority.NEAR, selection.priorityOf(9))
        assertEquals(RenderPriority.NEAR, selection.priorityOf(11))
        assertEquals(RenderPriority.PREFETCH, selection.priorityOf(7))
        assertEquals(RenderPriority.PREFETCH, selection.priorityOf(8))
        assertEquals(RenderPriority.PREFETCH, selection.priorityOf(12))
        assertEquals(RenderPriority.PREFETCH, selection.priorityOf(13))
        assertEquals(null, selection.priorityOf(6))
        assertEquals(null, selection.priorityOf(14))
        assertEquals(7, selection.size)
    }

    @Test fun selectionTagsEveryRequestWithTheStateGeneration() {
        val state = HorizontalViewportState.initial(pageCount = 5).copy(currentPage = 2, generation = 7L)
        val selection = HorizontalViewportPageSelector.select(state)
        assertTrue(selection.isNotEmpty())
        assertTrue(selection.all { it.generation == 7L })
    }

    @Test fun selectionOnAnEmptyDocumentRequestsNothing() {
        val state = HorizontalViewportState.initial(pageCount = 0)
        assertTrue(HorizontalViewportPageSelector.select(state).isEmpty())
    }

    @Test fun selectionNeverProducesDuplicateOrOutOfBoundsPages() {
        val state = HorizontalViewportState.initial(pageCount = 3).copy(currentPage = 1)
        val selection = HorizontalViewportPageSelector.select(state)
        val pages = selection.map { it.pageIndex }
        assertEquals(pages.distinct(), pages)
        assertTrue(pages.all { it in 0 until 3 })
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
