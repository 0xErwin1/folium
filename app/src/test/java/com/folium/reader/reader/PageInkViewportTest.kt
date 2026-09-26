package com.folium.reader.reader

import com.folium.reader.core.ink.PageInkExtent
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.pdf.HorizontalViewportZoom
import com.folium.reader.core.pdf.PageFitMode
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.ink.ViewPoint
import org.junit.Assert.assertEquals
import org.junit.Test

private const val EPSILON = 1e-3f

class PageInkViewportTest {

    private val aspect = 0.7f
    private val extent = PageInkExtent(aspect)
    private val viewport = ReaderViewport(widthPx = 1080, heightPx = 1920)

    private fun layoutAt(scale: Float, cx: Float, cy: Float): ViewportLayout =
        ReaderGeometry.layout(viewport, aspect, HorizontalViewportZoom(scale, PageSpacePoint(cx, cy)), PageFitMode.PAGE)

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: ViewPoint) {
        assertEquals(expectedX, actual.x, EPSILON)
        assertEquals(expectedY, actual.y, EPSILON)
    }

    private fun assertCornersMatch(layout: ViewportLayout) {
        val mapping = pageInkViewport(layout)

        assertEquals(layout.pageWidth, mapping.scale, EPSILON)
        assertPoint(layout.originX, layout.originY, mapping.sheetToView(SheetPoint(0f, 0f)))
        assertPoint(
            layout.originX + layout.pageWidth,
            layout.originY + layout.pageHeight,
            mapping.sheetToView(SheetPoint(1f, extent.heightUnits))
        )
    }

    private fun assertMatchesReaderGeometry(layout: ViewportLayout, pagePoint: PageSpacePoint) {
        val mapping = pageInkViewport(layout)
        val expected = ReaderGeometry.pageToViewport(layout, pagePoint)

        val inkPoint = extent.fromPageSpace(SheetPoint(pagePoint.x, pagePoint.y))
        val viewPoint = mapping.sheetToView(inkPoint)
        val back = mapping.viewToSheet(viewPoint)

        assertPoint(expected.x, expected.y, viewPoint)
        assertEquals(inkPoint.x, back.x, EPSILON)
        assertEquals(inkPoint.y, back.y, EPSILON)
    }

    @Test fun aFittedPageMapsItsCornersToTheLayoutOriginAndSize() {
        assertCornersMatch(layoutAt(scale = 1f, cx = 0.5f, cy = 0.5f))
    }

    @Test fun aZoomedAndPannedPageMapsItsCornersToTheLayoutOriginAndSize() {
        assertCornersMatch(layoutAt(scale = 3.5f, cx = 0.3f, cy = 0.8f))
    }

    @Test fun aFittedPageAgreesWithReaderGeometryAndRoundTrips() {
        val layout = layoutAt(scale = 1f, cx = 0.5f, cy = 0.5f)

        assertMatchesReaderGeometry(layout, PageSpacePoint(0.25f, 0.6f))
        assertMatchesReaderGeometry(layout, PageSpacePoint(0.9f, 0.1f))
    }

    @Test fun aZoomedPageAgreesWithReaderGeometryAndRoundTrips() {
        val layout = layoutAt(scale = 4f, cx = 0.7f, cy = 0.2f)

        assertMatchesReaderGeometry(layout, PageSpacePoint(0.65f, 0.22f))
        assertMatchesReaderGeometry(layout, PageSpacePoint(0.1f, 0.95f))
    }

    @Test fun aDrawFrameMapsStrokeSpaceOntoThePageAndClipsToIt() {
        val layout = layoutAt(scale = 3f, cx = 0.4f, cy = 0.6f)
        val frame = pageInkDrawFrame(layout)
        val inkPoint = extent.fromPageSpace(SheetPoint(0.3f, 0.7f))
        val expected = pageInkViewport(layout).sheetToView(inkPoint)

        assertEquals(expected.x, frame.translateX + inkPoint.x * 1000f * frame.scale, EPSILON)
        assertEquals(expected.y, frame.translateY + inkPoint.y * 1000f * frame.scale, EPSILON)

        assertEquals(layout.originX, frame.clip.left, EPSILON)
        assertEquals(layout.originY, frame.clip.top, EPSILON)
        assertEquals(layout.pageWidth, frame.clip.width, EPSILON)
        assertEquals(layout.pageHeight, frame.clip.height, EPSILON)
    }
}
