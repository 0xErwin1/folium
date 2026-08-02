package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Covers [HorizontalViewportReducer]'s [GestureIntent] transitions: navigation, zoom/focal stability, chrome, resize and generation rollover. */
class HorizontalViewportReducerTest {

    @Test fun pageForwardAdvancesAndRollsGeneration() {
        val state = HorizontalViewportState.initial(pageCount = 5)
        val next = HorizontalViewportReducer.reduce(state, GestureIntent.PageForward)
        assertEquals(1, next.currentPage)
        assertEquals(state.generation + 1, next.generation)
    }

    @Test fun pageForwardClampsAtTheLastPageAndDoesNotRollGeneration() {
        val state = HorizontalViewportState.initial(pageCount = 3).copy(currentPage = 2)
        val next = HorizontalViewportReducer.reduce(state, GestureIntent.PageForward)
        assertEquals(2, next.currentPage)
        assertEquals(state.generation, next.generation)
    }

    @Test fun pageBackClampsAtTheFirstPageAndDoesNotRollGeneration() {
        val state = HorizontalViewportState.initial(pageCount = 3)
        val next = HorizontalViewportReducer.reduce(state, GestureIntent.PageBack)
        assertEquals(0, next.currentPage)
        assertEquals(state.generation, next.generation)
    }

    @Test fun flingToPageClampsOutOfRangeTargetsDeterministically() {
        val state = HorizontalViewportState.initial(pageCount = 5)

        val flungPastTheEnd = HorizontalViewportReducer.reduce(state, GestureIntent.FlingToPage(99))
        assertEquals(4, flungPastTheEnd.currentPage)

        val flungBeforeTheStart = HorizontalViewportReducer.reduce(state, GestureIntent.FlingToPage(-99))
        assertEquals(0, flungBeforeTheStart.currentPage)
    }

    @Test fun navigationOnAnEmptyDocumentIsANoOp() {
        val state = HorizontalViewportState.initial(pageCount = 0)
        val next = HorizontalViewportReducer.reduce(state, GestureIntent.PageForward)
        assertEquals(state, next)
    }

    @Test fun zoomByRollsGenerationAndClampsAtTheUpperBound() {
        val state = HorizontalViewportState.initial(pageCount = 5)
        val next = HorizontalViewportReducer.reduce(state, GestureIntent.ZoomBy(factor = 100f, focal = PageSpacePoint(0.5f, 0.5f)))
        assertEquals(MAX_ZOOM_SCALE, next.zoom.scale)
        assertEquals(state.generation + 1, next.generation)
    }

    @Test fun zoomByClampsAtTheLowerBoundAndRecentersOnTheFullPage() {
        val zoomedIn = HorizontalViewportReducer.reduce(
            HorizontalViewportState.initial(pageCount = 5),
            GestureIntent.ZoomBy(2f, PageSpacePoint(0.2f, 0.8f))
        )
        val next = HorizontalViewportReducer.reduce(zoomedIn, GestureIntent.ZoomBy(factor = 0.01f, focal = PageSpacePoint(0.2f, 0.8f)))
        assertEquals(MIN_ZOOM_SCALE, next.zoom.scale)
        assertEquals(PageSpacePoint(0.5f, 0.5f), next.zoom.center)
    }

    @Test fun zoomingAboutTheCurrentCenterLeavesTheCenterUnchanged() {
        val zoomedIn = HorizontalViewportReducer.reduce(
            HorizontalViewportState.initial(pageCount = 5),
            GestureIntent.ZoomBy(2f, PageSpacePoint(0.3f, 0.4f))
        )
        val rezoomed = HorizontalViewportReducer.reduce(zoomedIn, GestureIntent.ZoomBy(1.5f, zoomedIn.zoom.center))
        assertEquals(zoomedIn.zoom.center, rezoomed.zoom.center)
    }

    @Test fun zoomingInAboutAnOffCenterFocalPointMovesTheCenterInTheCorrectDirection() {
        val state = HorizontalViewportState.initial(pageCount = 5)
        val zoomedIn = HorizontalViewportReducer.reduce(state, GestureIntent.ZoomBy(2f, PageSpacePoint(0.7f, 0.3f)))
        // center' = focal + (center-focal)*(oldScale/newScale) = (0.7,0.3) + ((0.5,0.5)-(0.7,0.3))*0.5 = (0.6,0.4).
        // An inverted-ratio implementation would instead move the center to (0.3, 0.7) -- pinning
        // this intermediate value, rather than only a round trip, is what actually discriminates
        // the two directions (see `folium/reader-first-mvp` verify report W-2).
        assertEquals(2f, zoomedIn.zoom.scale)
        assertEquals(0.6f, zoomedIn.zoom.center.x, 0.001f)
        assertEquals(0.4f, zoomedIn.zoom.center.y, 0.001f)
    }

    @Test fun zoomingInThenOutAboutTheSameFocalPointReturnsToTheOriginalCenterAndScale() {
        val focal = PageSpacePoint(0.7f, 0.3f)
        val original = HorizontalViewportReducer.reduce(
            HorizontalViewportState.initial(pageCount = 5),
            GestureIntent.ZoomBy(1.6f, PageSpacePoint(0.5f, 0.5f))
        )
        val zoomedIn = HorizontalViewportReducer.reduce(original, GestureIntent.ZoomBy(2f, focal))
        val zoomedBackOut = HorizontalViewportReducer.reduce(zoomedIn, GestureIntent.ZoomBy(0.5f, focal))

        assertTrue(abs(original.zoom.center.x - zoomedBackOut.zoom.center.x) < 0.001f)
        assertTrue(abs(original.zoom.center.y - zoomedBackOut.zoom.center.y) < 0.001f)
        assertEquals(original.zoom.scale, zoomedBackOut.zoom.scale, 0.001f)
    }

    @Test fun panningWhileZoomedInMovesTheCenterAgainstTheDragAndScalesWithTheZoom() {
        val zoomedIn = HorizontalViewportReducer.reduce(
            HorizontalViewportState.initial(pageCount = 5),
            GestureIntent.ZoomBy(4f, PageSpacePoint(0.5f, 0.5f))
        )
        val panned = HorizontalViewportReducer.reduce(zoomedIn, GestureIntent.PanBy(0.2f, -0.4f))

        // Dragging the page right by a fifth of the viewport moves the visible window left by that
        // same fifth measured in viewport units, which at scale 4 is 0.05 of the page.
        assertEquals(0.45f, panned.zoom.center.x, 0.001f)
        assertEquals(0.6f, panned.zoom.center.y, 0.001f)
        assertEquals(zoomedIn.zoom.scale, panned.zoom.scale)
        assertEquals(zoomedIn.generation + 1, panned.generation)
    }

    @Test fun panningClampsToThePageBoundsAndDoesNotRollGenerationWhenAlreadyAgainstThem() {
        val zoomedIn = HorizontalViewportReducer.reduce(
            HorizontalViewportState.initial(pageCount = 5),
            GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f))
        )
        val pannedToTheEdge = HorizontalViewportReducer.reduce(zoomedIn, GestureIntent.PanBy(-9f, -9f))
        assertEquals(0.75f, pannedToTheEdge.zoom.center.x, 0.001f)
        assertEquals(0.75f, pannedToTheEdge.zoom.center.y, 0.001f)

        val pannedFurther = HorizontalViewportReducer.reduce(pannedToTheEdge, GestureIntent.PanBy(-9f, -9f))
        assertEquals(pannedToTheEdge.zoom, pannedFurther.zoom)
        assertEquals(pannedToTheEdge.generation, pannedFurther.generation)
    }

    @Test fun panningAtTheMinimumZoomIsANoOpBecauseTheWholePageIsAlreadyVisible() {
        val state = HorizontalViewportState.initial(pageCount = 5)
        val panned = HorizontalViewportReducer.reduce(state, GestureIntent.PanBy(0.5f, 0.5f))
        assertEquals(state, panned)
    }

    @Test fun resetZoomReturnsToTheDefaultTransformAndRollsGenerationOnlyWhenSomethingChanges() {
        val zoomedIn = HorizontalViewportReducer.reduce(
            HorizontalViewportState.initial(pageCount = 5),
            GestureIntent.ZoomBy(2f, PageSpacePoint(0.2f, 0.2f))
        )
        val reset = HorizontalViewportReducer.reduce(zoomedIn, GestureIntent.ResetZoom)
        assertEquals(MIN_ZOOM_SCALE, reset.zoom.scale)
        assertEquals(PageSpacePoint(0.5f, 0.5f), reset.zoom.center)
        assertEquals(zoomedIn.generation + 1, reset.generation)

        val resetAgain = HorizontalViewportReducer.reduce(reset, GestureIntent.ResetZoom)
        assertEquals(reset.generation, resetAgain.generation)
    }

    @Test fun chromeTransitionsAreDeterministicAndDoNotRollGeneration() {
        val state = HorizontalViewportState.initial(pageCount = 5)
        val hidden = HorizontalViewportReducer.reduce(state, GestureIntent.HideChrome)
        assertEquals(false, hidden.chromeVisible)
        assertEquals(state.generation, hidden.generation)

        val shownAgain = HorizontalViewportReducer.reduce(hidden, GestureIntent.ToggleChrome)
        assertEquals(true, shownAgain.chromeVisible)
        assertEquals(hidden.generation, shownAgain.generation)

        val stillShown = HorizontalViewportReducer.reduce(shownAgain, GestureIntent.ShowChrome)
        assertEquals(true, stillShown.chromeVisible)
        assertEquals(shownAgain.generation, stillShown.generation)
    }

    @Test fun viewportResizedRollsGenerationWithoutTouchingPageZoomOrChrome() {
        val state = HorizontalViewportState.initial(pageCount = 5).copy(currentPage = 2)
        val resized = HorizontalViewportReducer.reduce(state, GestureIntent.ViewportResized)
        assertEquals(state.currentPage, resized.currentPage)
        assertEquals(state.zoom, resized.zoom)
        assertEquals(state.chromeVisible, resized.chromeVisible)
        assertEquals(state.generation + 1, resized.generation)
    }

    @Test fun everyGenerationRollingIntentActuallyProducesADistinctGenerationAcrossRepeatedApplications() {
        var state = HorizontalViewportState.initial(pageCount = 5)
        val seenGenerations = mutableSetOf(state.generation)
        val intents = listOf(
            GestureIntent.PageForward,
            GestureIntent.ZoomBy(1.5f, PageSpacePoint(0.5f, 0.5f)),
            GestureIntent.ViewportResized,
            GestureIntent.PageBack,
            GestureIntent.ResetZoom
        )
        intents.forEach { intent ->
            val next = HorizontalViewportReducer.reduce(state, intent)
            assertNotEquals(state.generation, next.generation)
            seenGenerations.add(next.generation)
            state = next
        }
        assertEquals(intents.size + 1, seenGenerations.size)
    }
}
