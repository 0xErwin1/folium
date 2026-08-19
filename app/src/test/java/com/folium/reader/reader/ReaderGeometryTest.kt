package com.folium.reader.reader

import com.folium.reader.core.pdf.HorizontalViewportState
import com.folium.reader.core.pdf.HorizontalViewportZoom
import com.folium.reader.core.pdf.PageFitMode
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.RenderPriority
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
        val layout = ReaderGeometry.layout(viewport, portraitPage, zoom(1f), PageFitMode.PAGE)

        assertEquals(500f, layout.pageWidth, 0.01f)
        assertEquals(1000f, layout.pageHeight, 0.01f)
        assertEquals(250f, layout.originX, 0.01f)
        assertEquals(0f, layout.originY, 0.01f)
        assertEquals(PageSpaceRect(0f, 0f, 1f, 1f), ReaderGeometry.visibleRegion(layout))
    }

    @Test fun pageAndViewportPointsRoundTripThroughLetterboxZoomAndPan() {
        val layout = ReaderGeometry.layout(viewport, portraitPage, zoom(3f, cx = .35f, cy = .65f), PageFitMode.PAGE)
        val pagePoint = PageSpacePoint(.27f, .72f)
        val viewportPoint = ReaderGeometry.pageToViewport(layout, pagePoint)

        assertEquals(pagePoint, ReaderGeometry.viewportToPage(layout, viewportPoint))
    }

    @Test fun pointsOutsideALetterboxedPageMissUnlessClampedForAGestureFocal() {
        val layout = ReaderGeometry.layout(viewport, portraitPage, zoom(1f), PageFitMode.PAGE)
        val outside = ViewportPoint(20f, 500f)

        assertEquals(null, ReaderGeometry.viewportToPage(layout, outside))
        assertEquals(PageSpacePoint(0f, .5f), ReaderGeometry.viewportToPage(layout, outside, clampToPage = true))
    }

    @Test fun zoomingInEnlargesThePageAndNarrowsTheVisibleRegionOnTheOverflowingAxisOnly() {
        val layout = ReaderGeometry.layout(viewport, portraitPage, zoom(2f), PageFitMode.PAGE)

        assertEquals(1000f, layout.pageWidth, 0.01f)
        assertEquals(2000f, layout.pageHeight, 0.01f)

        val region = ReaderGeometry.visibleRegion(layout)
        assertEquals(0f, region.left, 0.001f)
        assertEquals(1f, region.right, 0.001f)
        assertEquals(0.25f, region.top, 0.001f)
        assertEquals(0.75f, region.bottom, 0.001f)
    }

    @Test fun anOffCentreZoomShiftsTheVisibleRegionWithoutEverLeavingThePage() {
        val layout = ReaderGeometry.layout(viewport, portraitPage, zoom(4f, cy = 0.2f), PageFitMode.PAGE)
        val region = ReaderGeometry.visibleRegion(layout)

        assertEquals(0.075f, region.top, 0.001f)
        assertEquals(0.325f, region.bottom, 0.001f)
        assertTrue("region must stay inside the page", region.left >= 0f && region.right <= 1f)
    }

    @Test fun aRegionIsAlwaysRequestedAtItsOwnOnScreenPixelSizeSoNothingIsRasterizedLargerThanTheViewport() {
        val unzoomed = ReaderGeometry.layout(viewport, portraitPage, zoom(1f), PageFitMode.PAGE)
        val unzoomedSpec = ReaderGeometry.requestSpec(unzoomed, ReaderGeometry.visibleRegion(unzoomed))
        assertEquals(500, unzoomedSpec.width)
        assertEquals(1000, unzoomedSpec.height)

        val zoomed = ReaderGeometry.layout(viewport, portraitPage, zoom(2f), PageFitMode.PAGE)
        val zoomedSpec = ReaderGeometry.requestSpec(zoomed, ReaderGeometry.visibleRegion(zoomed))
        assertEquals(1000, zoomedSpec.width)
        assertEquals(1000, zoomedSpec.height)
        assertEquals(PageSpaceRect(0f, 0.25f, 1f, 0.75f), zoomedSpec.pageSpace)
    }

    @Test fun aRegionRasterizedBeforeAZoomIsPlacedSoItsContentStaysUnderTheSamePagePoint() {
        val before = ReaderGeometry.layout(viewport, portraitPage, zoom(1f), PageFitMode.PAGE)
        val wholePage = ReaderGeometry.visibleRegion(before)

        val after = ReaderGeometry.layout(viewport, portraitPage, zoom(2f), PageFitMode.PAGE)
        val placed = ReaderGeometry.destination(after, wholePage)

        assertEquals(0f, placed.left, 0.01f)
        assertEquals(-500f, placed.top, 0.01f)
        assertEquals(1000f, placed.width, 0.01f)
        assertEquals(2000f, placed.height, 0.01f)
    }

    @Test fun aFreshlyRasterizedRegionIsPlacedExactlyOverTheViewportItWasRequestedFor() {
        val layout = ReaderGeometry.layout(viewport, pageAspect = 1f, zoom = zoom(3f, cx = 0.4f, cy = 0.7f), PageFitMode.PAGE)
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
        val layout = ReaderGeometry.layout(viewport, portraitPage, zoom(3f, cx = 0.3f, cy = 0.7f), PageFitMode.PAGE)
        val region = ReaderGeometry.visibleRegion(layout)
        val placed = ReaderGeometry.destination(layout, region)

        assertEquals(0f, region.left, 0.001f)
        assertEquals(50f, placed.left, 0.01f)
        assertEquals(950f, placed.width, 0.01f)

        assertEquals(0f, placed.top, 0.01f)
        assertEquals(viewport.heightPx.toFloat(), placed.height, 0.01f)
    }

    @Test fun aLandscapePageIsFittedByHeightAndCentredHorizontally() {
        val layout = ReaderGeometry.layout(viewport, pageAspect = 2f, zoom = zoom(1f), PageFitMode.PAGE)

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
                        val layout = ReaderGeometry.layout(measured, aspect, zoom(scale, centre, centre), PageFitMode.PAGE)
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

    /**
     * The two fit modes only ever differ on the vertical axis: neither lets a page be wider than
     * the viewport at the fitted scale, which is why a single vertical fraction is enough to
     * describe how much of a page is reachable.
     */
    @Test fun fitWidthFillsTheViewportWidthAndLetsThePageOverflowVertically() {
        val wide = ReaderViewport(widthPx = 2560, heightPx = 1600)

        val fitPage = ReaderGeometry.layout(wide, portraitPage, zoom(1f), PageFitMode.PAGE)
        assertEquals(800f, fitPage.pageWidth, 0.01f)
        assertEquals(1600f, fitPage.pageHeight, 0.01f)
        assertEquals(1f, ReaderGeometry.visibleHeightFraction(wide, portraitPage, PageFitMode.PAGE), 0.0001f)

        val fitWidth = ReaderGeometry.layout(wide, portraitPage, zoom(1f), PageFitMode.WIDTH)
        assertEquals(2560f, fitWidth.pageWidth, 0.01f)
        assertEquals(5120f, fitWidth.pageHeight, 0.01f)
        assertEquals(0f, fitWidth.originX, 0.01f)
        assertEquals(0.3125f, ReaderGeometry.visibleHeightFraction(wide, portraitPage, PageFitMode.WIDTH), 0.0001f)
    }

    /**
     * A page that already fits the width in fit-page mode — the ordinary book on a tall phone — is
     * laid out identically in both modes, so choosing fit-width cannot make it any larger.
     */
    @Test fun aPortraitPageOnATallPhoneIsLaidOutIdenticallyInBothFitModes() {
        val phone = ReaderViewport(widthPx = 1080, heightPx = 2400)
        val a4 = 0.7078f

        assertEquals(
            ReaderGeometry.layout(phone, a4, zoom(1f), PageFitMode.PAGE),
            ReaderGeometry.layout(phone, a4, zoom(1f), PageFitMode.WIDTH)
        )
        assertEquals(1f, ReaderGeometry.visibleHeightFraction(phone, a4, PageFitMode.WIDTH), 0.0001f)
    }

    @Test fun fitWidthNeverRequestsARasterLargerThanTheViewportAcrossEveryReachableState() {
        val viewports = listOf(ReaderViewport(1080, 2400), ReaderViewport(2560, 1600), ReaderViewport(1, 1))
        val aspects = listOf(0.2f, 0.5f, 0.7071f, 1f, 1.4142f, 5f)
        val scales = listOf(1f, 1.5f, 3.3f, 5f)
        var checked = 0

        for (measured in viewports) {
            for (aspect in aspects) {
                val fraction = ReaderGeometry.visibleHeightFraction(measured, aspect, PageFitMode.WIDTH)
                assertTrue("a fraction must describe part of a page, was $fraction", fraction > 0f && fraction <= 1f)

                for (scale in scales) {
                    val extentY = 0.5f * fraction / scale
                    for (centreY in listOf(extentY, 0.5f, 1f - extentY)) {
                        val layout = ReaderGeometry.layout(measured, aspect, zoom(scale, 0.5f, centreY), PageFitMode.WIDTH)
                        val spec = ReaderGeometry.requestSpec(layout, ReaderGeometry.visibleRegion(layout))

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
        val specs = ReaderGeometry.specForPage(viewport, state.zoom, PageFitMode.PAGE, { RenderPriority.VISIBLE }) { index -> if (index == 1) 2f else 0.5f }

        assertEquals(500, specs(0).width)
        assertEquals(1000, specs(0).height)
        assertEquals(1000, specs(1).width)
        assertEquals(500, specs(1).height)
    }

    /**
     * The page either side of the one being read is the page a turn lands on, so it is requested at
     * the size it will be drawn at. Anything smaller is a raster the reader sees upscaled for as long
     * as the sharp one takes to arrive.
     */
    @Test fun aNearPageIsRequestedAtTheSizeItWillBeDrawnAt() {
        val state = HorizontalViewportState.initial(pageCount = 3)
        val visible = ReaderGeometry.specForPage(viewport, state.zoom, PageFitMode.PAGE, { RenderPriority.VISIBLE }) { 0.5f }(0)
        val near = ReaderGeometry.specForPage(viewport, state.zoom, PageFitMode.PAGE, { RenderPriority.NEAR }) { 0.5f }(0)

        assertEquals(visible, near)
    }

    @Test fun aPrefetchPageIsRequestedAtAQuarterEachEdgeOfWhatAVisiblePageWouldBe() {
        val state = HorizontalViewportState.initial(pageCount = 3)
        val visible = ReaderGeometry.specForPage(viewport, state.zoom, PageFitMode.PAGE, { RenderPriority.VISIBLE }) { 0.5f }(0)
        val prefetch = ReaderGeometry.specForPage(viewport, state.zoom, PageFitMode.PAGE, { RenderPriority.PREFETCH }) { 0.5f }(0)

        assertEquals(visible.width / 4, prefetch.width)
        assertEquals(visible.height / 4, prefetch.height)
    }

    @Test fun aDownscaledSpecIsNeverRequestedBelowOnePixelOnEitherEdge() {
        val tinyViewport = ReaderViewport(3, 3)
        val state = HorizontalViewportState.initial(pageCount = 3)
        val prefetch = ReaderGeometry.specForPage(tinyViewport, state.zoom, PageFitMode.PAGE, { RenderPriority.PREFETCH }) { 0.5f }(0)

        assertTrue(prefetch.width >= 1)
        assertTrue(prefetch.height >= 1)
    }

    @Test fun theBaseTierSpecCoversTheWholePageAtALongestEdgeOf768Pixels() {
        val portrait = ReaderGeometry.baseTierSpec(0.5f)
        assertEquals(384, portrait.width)
        assertEquals(768, portrait.height)
        assertEquals(PageSpaceRect(0f, 0f, 1f, 1f), portrait.pageSpace)

        val landscape = ReaderGeometry.baseTierSpec(2f)
        assertEquals(768, landscape.width)
        assertEquals(384, landscape.height)

        val square = ReaderGeometry.baseTierSpec(1f)
        assertEquals(768, square.width)
        assertEquals(768, square.height)
    }

    @Test fun theBaseTierSpecIsIndependentOfViewportSizeAndZoom() {
        val a4 = 0.7078f
        val fromANarrowPhone = ReaderGeometry.baseTierSpec(a4)
        val fromAWideTablet = ReaderGeometry.baseTierSpec(a4)

        assertEquals(fromANarrowPhone, fromAWideTablet)
    }

    @Test fun everyReachableAspectProducesAPositiveBaseTierSpec() {
        val aspects = listOf(0.01f, 0.2f, 0.5f, 0.7071f, 1f, 1.4142f, 5f, 100f)
        aspects.forEach { aspect ->
            val spec = ReaderGeometry.baseTierSpec(aspect)
            assertTrue("width must be positive for aspect=$aspect", spec.width >= 1)
            assertTrue("height must be positive for aspect=$aspect", spec.height >= 1)
        }
    }
}
