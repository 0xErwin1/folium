package com.folium.reader.ink

import com.folium.reader.core.ink.SheetPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EPSILON = 1e-4f

class SheetViewportTest {

    @Test
    fun initialFitsColumnWidthToViewWidthAtZoomOne() {
        val viewport = SheetViewport.initial(viewWidthPx = 400f, viewHeightPx = 800f)

        assertEquals(1f, viewport.zoom, EPSILON)
        assertEquals(400f, viewport.scale, EPSILON)
        assertEquals(SheetPoint(0f, 0f), viewport.topLeft)
    }

    @Test
    fun sheetToViewAndBackRoundTrips() {
        val viewport = SheetViewport.initial(viewWidthPx = 400f, viewHeightPx = 800f).zoomedBy(3f, ViewPoint(100f, 200f))

        val sheetPoint = SheetPoint(0.42f, 5.5f)
        val roundTripped = viewport.viewToSheet(viewport.sheetToView(sheetPoint))

        assertEquals(sheetPoint.x, roundTripped.x, EPSILON)
        assertEquals(sheetPoint.y, roundTripped.y, EPSILON)
    }

    @Test
    fun focalZoomKeepsTheFocalSheetPointUnderTheFocalViewPoint() {
        val viewport = SheetViewport.initial(viewWidthPx = 400f, viewHeightPx = 800f)
        val focal = ViewPoint(120f, 340f)
        val sheetPointUnderFocal = viewport.viewToSheet(focal)

        val zoomed = viewport.zoomedBy(2.5f, focal)
        val viewPointOfSameSheetPoint = zoomed.sheetToView(sheetPointUnderFocal)

        assertEquals(focal.x, viewPointOfSameSheetPoint.x, EPSILON)
        assertEquals(focal.y, viewPointOfSameSheetPoint.y, EPSILON)
    }

    @Test
    fun zoomIsClampedToMaxZoom() {
        val viewport = SheetViewport.initial(viewWidthPx = 400f, viewHeightPx = 800f)

        val zoomed = viewport.zoomedBy(1000f, ViewPoint(0f, 0f))

        assertEquals(SheetViewport.MAX_ZOOM, zoomed.zoom, EPSILON)
    }

    @Test
    fun zoomIsClampedToMinZoom() {
        val viewport = SheetViewport.initial(viewWidthPx = 400f, viewHeightPx = 800f).zoomedBy(4f, ViewPoint(0f, 0f))

        val zoomed = viewport.zoomedBy(0.01f, ViewPoint(0f, 0f))

        assertEquals(SheetViewport.MIN_ZOOM, zoomed.zoom, EPSILON)
    }

    @Test
    fun horizontalPanIsFullyClampedAtZoomOne() {
        val viewport = SheetViewport.initial(viewWidthPx = 400f, viewHeightPx = 800f)

        val pannedRight = viewport.pannedBy(dxPx = 10_000f, dyPx = 0f)
        val pannedLeft = viewport.pannedBy(dxPx = -10_000f, dyPx = 0f)

        assertEquals(0f, pannedRight.topLeft.x, EPSILON)
        assertEquals(0f, pannedLeft.topLeft.x, EPSILON)
    }

    @Test
    fun horizontalPanIsClampedSoTheColumnNeverLeavesTheView() {
        val viewport = SheetViewport.initial(viewWidthPx = 400f, viewHeightPx = 800f).zoomedBy(2f, ViewPoint(0f, 0f))
        val maxLeftX = 1f - 400f / viewport.scale

        val pannedPastRightEdge = viewport.pannedBy(dxPx = 100_000f, dyPx = 0f)
        val pannedPastLeftEdge = viewport.pannedBy(dxPx = -100_000f, dyPx = 0f)

        assertEquals(maxLeftX, pannedPastRightEdge.topLeft.x, EPSILON)
        assertEquals(0f, pannedPastLeftEdge.topLeft.x, EPSILON)
    }

    @Test
    fun verticalPanNeverGoesAboveSheetTop() {
        val viewport = SheetViewport.initial(viewWidthPx = 400f, viewHeightPx = 800f)

        val pannedUp = viewport.pannedBy(dxPx = 0f, dyPx = -10_000f)

        assertEquals(0f, pannedUp.topLeft.y, EPSILON)
    }

    @Test
    fun verticalPanIsClampedToOneViewHeightPastContentBottom() {
        val viewport = SheetViewport.initial(viewWidthPx = 100f, viewHeightPx = 200f, contentBottom = 10f)
        val oneViewHeightInSheetUnits = 200f / viewport.scale
        val maxTopY = 10f + oneViewHeightInSheetUnits

        val pannedPastBottom = viewport.pannedBy(dxPx = 0f, dyPx = 100_000f)

        assertEquals(maxTopY, pannedPastBottom.topLeft.y, EPSILON)
    }

    @Test
    fun withContentBottomReClampsAnAlreadyPannedViewport() {
        val viewport = SheetViewport.initial(viewWidthPx = 100f, viewHeightPx = 200f, contentBottom = 100f)
            .pannedBy(dxPx = 0f, dyPx = 100_000f)

        val shrunk = viewport.withContentBottom(0f)

        val oneViewHeightInSheetUnits = 200f / shrunk.scale
        assertEquals(oneViewHeightInSheetUnits, shrunk.topLeft.y, EPSILON)
    }

    @Test
    fun resizedKeepsTopLeftWhenTheClampStillAllowsIt() {
        val viewport = SheetViewport.initial(viewWidthPx = 400f, viewHeightPx = 800f, contentBottom = 50f)
            .zoomedBy(2f, ViewPoint(0f, 0f))
            .pannedBy(dxPx = 0f, dyPx = -50f)

        val resized = viewport.resized(newWidthPx = 500f, newHeightPx = 900f)

        assertEquals(viewport.topLeft.x, resized.topLeft.x, EPSILON)
        assertEquals(viewport.topLeft.y, resized.topLeft.y, EPSILON)
    }

    @Test
    fun lengthToSheetUnitsDividesByScale() {
        val viewport = SheetViewport.initial(viewWidthPx = 400f, viewHeightPx = 800f).zoomedBy(2f, ViewPoint(0f, 0f))

        assertEquals(12f / viewport.scale, viewport.lengthToSheetUnits(12f), EPSILON)
    }

    @Test
    fun contentBottomIsNeverNegative() {
        val viewport = SheetViewport.initial(viewWidthPx = 400f, viewHeightPx = 800f, contentBottom = -5f)

        assertTrue(viewport.contentBottom >= 0f)
    }

    @Test
    fun zoomedToSetsTheZoomDirectly() {
        val viewport = SheetViewport.initial(viewWidthPx = 400f, viewHeightPx = 800f)

        val zoomed = viewport.zoomedTo(4f, ViewPoint(0f, 0f))

        assertEquals(4f, zoomed.zoom, EPSILON)
    }

    @Test
    fun zoomedToKeepsTheFocalSheetPointUnderTheFocalViewPoint() {
        val viewport = SheetViewport.initial(viewWidthPx = 400f, viewHeightPx = 800f)
        val focal = ViewPoint(120f, 340f)
        val sheetPointUnderFocal = viewport.viewToSheet(focal)

        val zoomed = viewport.zoomedTo(3f, focal)
        val viewPointOfSameSheetPoint = zoomed.sheetToView(sheetPointUnderFocal)

        assertEquals(focal.x, viewPointOfSameSheetPoint.x, EPSILON)
        assertEquals(focal.y, viewPointOfSameSheetPoint.y, EPSILON)
    }

    @Test
    fun zoomedToIsClampedToTheZoomRange() {
        val viewport = SheetViewport.initial(viewWidthPx = 400f, viewHeightPx = 800f)

        assertEquals(SheetViewport.MAX_ZOOM, viewport.zoomedTo(1000f, ViewPoint(0f, 0f)).zoom, EPSILON)
        assertEquals(SheetViewport.MIN_ZOOM, viewport.zoomedTo(0.01f, ViewPoint(0f, 0f)).zoom, EPSILON)
    }

    @Test
    fun fittedToWidthResetsZoomToOneKeepingTheCurrentTop() {
        val viewport = SheetViewport.initial(viewWidthPx = 400f, viewHeightPx = 800f, contentBottom = 50f)
            .zoomedBy(4f, ViewPoint(0f, 0f))
            .pannedBy(dxPx = 0f, dyPx = 100f)

        val fitted = viewport.fittedToWidth()

        assertEquals(SheetViewport.MIN_ZOOM, fitted.zoom, EPSILON)
        assertEquals(0f, fitted.topLeft.x, EPSILON)
        assertEquals(viewport.topLeft.y, fitted.topLeft.y, EPSILON)
    }
}
