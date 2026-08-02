package com.folium.reader.reader

import com.folium.reader.core.pdf.HorizontalViewportState
import com.folium.reader.core.pdf.HorizontalViewportZoom
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.core.pdf.PageSpaceRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure viewport mathematics the reader draws and requests renders from: where a page
 * sits, which part of it is visible, what pixel size that part should be rasterized at, and where
 * an already-rasterized region belongs on screen once the viewport has moved on.
 */
class ReaderGeometryTest {

    private val portraitPage = 0.5f
    private val viewport = ReaderViewport(widthPx = 1000, heightPx = 1000)

    private fun zoom(scale: Float, cx: Float = 0.5f, cy: Float = 0.5f) =
        HorizontalViewportZoom(scale, PageSpacePoint(cx, cy))

    @Test fun anUnzoomedPageIsFittedWholeAndCentredInsideTheViewport() {
        val layout = ReaderGeometry.layout(viewport, portraitPage, zoom(1f))

        assertEquals(500f, layout.pageWidth, 0.01f)
        assertEquals(1000f, layout.pageHeight, 0.01f)
        assertEquals(250f, layout.originX, 0.01f)
        assertEquals(0f, layout.originY, 0.01f)
        assertEquals(PageSpaceRect(0f, 0f, 1f, 1f), ReaderGeometry.visibleRegion(layout))
    }

    @Test fun zoomingInEnlargesThePageAndNarrowsTheVisibleRegionOnTheOverflowingAxisOnly() {
        val layout = ReaderGeometry.layout(viewport, portraitPage, zoom(2f))

        assertEquals(1000f, layout.pageWidth, 0.01f)
        assertEquals(2000f, layout.pageHeight, 0.01f)

        val region = ReaderGeometry.visibleRegion(layout)
        assertEquals(0f, region.left, 0.001f)
        assertEquals(1f, region.right, 0.001f)
        assertEquals(0.25f, region.top, 0.001f)
        assertEquals(0.75f, region.bottom, 0.001f)
    }

    @Test fun anOffCentreZoomShiftsTheVisibleRegionWithoutEverLeavingThePage() {
        val layout = ReaderGeometry.layout(viewport, portraitPage, zoom(4f, cy = 0.2f))
        val region = ReaderGeometry.visibleRegion(layout)

        assertEquals(0.075f, region.top, 0.001f)
        assertEquals(0.325f, region.bottom, 0.001f)
        assertTrue("region must stay inside the page", region.left >= 0f && region.right <= 1f)
    }

    @Test fun aRegionIsAlwaysRequestedAtItsOwnOnScreenPixelSizeSoNothingIsRasterizedLargerThanTheViewport() {
        val unzoomed = ReaderGeometry.layout(viewport, portraitPage, zoom(1f))
        val unzoomedSpec = ReaderGeometry.requestSpec(unzoomed, ReaderGeometry.visibleRegion(unzoomed))
        assertEquals(500, unzoomedSpec.width)
        assertEquals(1000, unzoomedSpec.height)

        val zoomed = ReaderGeometry.layout(viewport, portraitPage, zoom(2f))
        val zoomedSpec = ReaderGeometry.requestSpec(zoomed, ReaderGeometry.visibleRegion(zoomed))
        assertEquals(1000, zoomedSpec.width)
        assertEquals(1000, zoomedSpec.height)
        assertEquals(PageSpaceRect(0f, 0.25f, 1f, 0.75f), zoomedSpec.pageSpace)
    }

    @Test fun aRegionRasterizedBeforeAZoomIsPlacedSoItsContentStaysUnderTheSamePagePoint() {
        val before = ReaderGeometry.layout(viewport, portraitPage, zoom(1f))
        val wholePage = ReaderGeometry.visibleRegion(before)

        val after = ReaderGeometry.layout(viewport, portraitPage, zoom(2f))
        val placed = ReaderGeometry.destination(after, wholePage)

        assertEquals(0f, placed.left, 0.01f)
        assertEquals(-500f, placed.top, 0.01f)
        assertEquals(1000f, placed.width, 0.01f)
        assertEquals(2000f, placed.height, 0.01f)
    }

    @Test fun aFreshlyRasterizedRegionIsPlacedExactlyOverTheViewportItWasRequestedFor() {
        val layout = ReaderGeometry.layout(viewport, pageAspect = 1f, zoom = zoom(3f, cx = 0.4f, cy = 0.7f))
        val region = ReaderGeometry.visibleRegion(layout)
        val placed = ReaderGeometry.destination(layout, region)

        assertEquals(0f, placed.left, 0.01f)
        assertEquals(0f, placed.top, 0.01f)
        assertEquals(viewport.widthPx.toFloat(), placed.width, 0.01f)
        assertEquals(viewport.heightPx.toFloat(), placed.height, 0.01f)
    }

    /**
     * The reducer clamps the zoom centre against a square half-extent of `0.5 / scale`, which on a
     * letterboxed axis is looser than that axis really needs. The visible region therefore stops at
     * the page edge rather than running off it, and the page is drawn inset by whatever is left
     * over instead of being stretched to fill the viewport.
     */
    @Test fun aZoomedPageThatStillDoesNotFillTheViewportIsInsetRatherThanStretched() {
        val layout = ReaderGeometry.layout(viewport, portraitPage, zoom(3f, cx = 0.3f, cy = 0.7f))
        val region = ReaderGeometry.visibleRegion(layout)
        val placed = ReaderGeometry.destination(layout, region)

        assertEquals(0f, region.left, 0.001f)
        assertEquals(50f, placed.left, 0.01f)
        assertEquals(950f, placed.width, 0.01f)

        assertEquals(0f, placed.top, 0.01f)
        assertEquals(viewport.heightPx.toFloat(), placed.height, 0.01f)
    }

    @Test fun aLandscapePageIsFittedByHeightAndCentredHorizontally() {
        val layout = ReaderGeometry.layout(viewport, pageAspect = 2f, zoom = zoom(1f))

        assertEquals(1000f, layout.pageWidth, 0.01f)
        assertEquals(500f, layout.pageHeight, 0.01f)
        assertEquals(0f, layout.originX, 0.01f)
        assertEquals(250f, layout.originY, 0.01f)
    }

    @Test fun everyReachableStateProducesARequestableSpecAcrossBothScreenWidths() {
        val viewports = listOf(ReaderViewport(1080, 2160), ReaderViewport(2560, 1600), ReaderViewport(1, 1))
        val aspects = listOf(0.2f, 0.5f, 0.7071f, 1f, 1.4142f, 5f)
        val scales = listOf(1f, 1.0001f, 1.5f, 2f, 3.3f, 5f)
        var checked = 0

        for (measured in viewports) {
            for (aspect in aspects) {
                for (scale in scales) {
                    val extent = 0.5f / scale
                    for (centre in listOf(extent, 0.5f, 1f - extent)) {
                        val layout = ReaderGeometry.layout(measured, aspect, zoom(scale, centre, centre))
                        val region = ReaderGeometry.visibleRegion(layout)
                        val spec = ReaderGeometry.requestSpec(layout, region)

                        assertTrue(spec.width in 1..measured.widthPx)
                        assertTrue(spec.height in 1..measured.heightPx)
                        checked++
                    }
                }
            }
        }

        assertEquals(viewports.size * aspects.size * scales.size * 3, checked)
    }

    @Test fun anUnmeasuredViewportHasNoRequestableGeometryAtAll() {
        assertEquals(null, ReaderViewport.of(0, 800))
        assertEquals(null, ReaderViewport.of(800, 0))
        assertEquals(ReaderViewport(800, 600), ReaderViewport.of(800, 600))
    }

    @Test fun theSpecForAPageIsDerivedFromThatPagesOwnAspectRatio() {
        val state = HorizontalViewportState.initial(pageCount = 3)
        val specs = ReaderGeometry.specForPage(viewport, state.zoom) { index -> if (index == 1) 2f else 0.5f }

        assertEquals(500, specs(0).width)
        assertEquals(1000, specs(0).height)
        assertEquals(1000, specs(1).width)
        assertEquals(500, specs(1).height)
    }
}
