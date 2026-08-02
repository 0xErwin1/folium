package com.folium.reader.reader

import com.folium.reader.core.pdf.HorizontalViewportZoom
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.RenderSpec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The measured drawing area of the reader, in device pixels. */
data class ReaderViewport(val widthPx: Int, val heightPx: Int) {
    init { require(widthPx > 0 && heightPx > 0) }

    companion object {
        /** A viewport Compose has not measured yet has no geometry, and nothing may be requested for it. */
        fun of(widthPx: Int, heightPx: Int): ReaderViewport? =
            if (widthPx > 0 && heightPx > 0) ReaderViewport(widthPx, heightPx) else null
    }
}

/**
 * Where a single page sits in viewport pixels at the current zoom: [pageWidth]/[pageHeight] are the
 * whole page's on-screen size, and [originX]/[originY] the viewport coordinate of its top-left
 * corner, which is negative on whichever axis the page overflows the viewport.
 */
data class ViewportLayout(
    val viewport: ReaderViewport,
    val originX: Float,
    val originY: Float,
    val pageWidth: Float,
    val pageHeight: Float
)

/** A destination rectangle in viewport pixels. */
data class ViewportRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * The pure viewport mathematics behind the reader: it maps between page space (normalized `0f..1f`
 * on both axes, the coordinate system `:reader-core` states requests in) and viewport pixels.
 *
 * Every rasterized region carries the page-space rectangle it covers, and [destination] places any
 * such region under the *current* layout rather than the one it was requested for. That is what
 * lets a page rasterized before a zoom stay on screen, correctly positioned and merely soft, until
 * its replacement at the new scale arrives — and what guarantees the replacement lands in exactly
 * the same place instead of jumping.
 */
object ReaderGeometry {

    fun layout(viewport: ReaderViewport, pageAspect: Float, zoom: HorizontalViewportZoom): ViewportLayout {
        require(pageAspect > 0f && pageAspect.isFinite()) { "pageAspect must be positive and finite, was $pageAspect" }

        val viewportWidth = viewport.widthPx.toFloat()
        val viewportHeight = viewport.heightPx.toFloat()

        val fittedWidth = min(viewportWidth, viewportHeight * pageAspect)
        val pageWidth = fittedWidth * zoom.scale
        val pageHeight = (fittedWidth / pageAspect) * zoom.scale

        return ViewportLayout(
            viewport = viewport,
            originX = axisOrigin(viewportWidth, pageWidth, zoom.center.x),
            originY = axisOrigin(viewportHeight, pageHeight, zoom.center.y),
            pageWidth = pageWidth,
            pageHeight = pageHeight
        )
    }

    fun visibleRegion(layout: ViewportLayout): PageSpaceRect {
        val (left, right) = axisRegion(layout.originX, layout.pageWidth, layout.viewport.widthPx.toFloat())
        val (top, bottom) = axisRegion(layout.originY, layout.pageHeight, layout.viewport.heightPx.toFloat())
        return PageSpaceRect(left, top, right, bottom)
    }

    /**
     * The pixel size [region] should be rasterized at: exactly the size it occupies on screen, so
     * the result is never upscaled and never larger than the viewport that will display it.
     */
    fun requestSpec(layout: ViewportLayout, region: PageSpaceRect): RenderSpec = RenderSpec(
        width = pixelExtent(region.right - region.left, layout.pageWidth, layout.viewport.widthPx),
        height = pixelExtent(region.bottom - region.top, layout.pageHeight, layout.viewport.heightPx),
        pageSpace = region
    )

    fun destination(layout: ViewportLayout, region: PageSpaceRect): ViewportRect = ViewportRect(
        left = layout.originX + region.left * layout.pageWidth,
        top = layout.originY + region.top * layout.pageHeight,
        width = (region.right - region.left) * layout.pageWidth,
        height = (region.bottom - region.top) * layout.pageHeight
    )

    /**
     * The per-page spec function `:reader-core`'s request coordinator drives its submissions from.
     * Pages within one document are free to differ in shape, so each page's own aspect ratio
     * decides its request rather than the current page's.
     */
    fun specForPage(
        viewport: ReaderViewport,
        zoom: HorizontalViewportZoom,
        pageAspect: (Int) -> Float
    ): (Int) -> RenderSpec = { pageIndex ->
        val layout = layout(viewport, pageAspect(pageIndex), zoom)
        requestSpec(layout, visibleRegion(layout))
    }

    /**
     * A page smaller than the viewport is centred outright: the reducer clamps the zoom centre
     * against a square half-extent, which on a letterboxed axis is tighter than that axis actually
     * needs, and honouring it there would pin a page that entirely fits off to one side.
     */
    private fun axisOrigin(viewportExtent: Float, pageExtent: Float, center: Float): Float =
        if (pageExtent <= viewportExtent) (viewportExtent - pageExtent) / 2f
        else viewportExtent / 2f - center * pageExtent

    private fun axisRegion(origin: Float, pageExtent: Float, viewportExtent: Float): Pair<Float, Float> {
        val low = ((0f - origin) / pageExtent).coerceIn(0f, 1f)
        val high = ((viewportExtent - origin) / pageExtent).coerceIn(0f, 1f)
        return if (high > low) low to high else 0f to 1f
    }

    private fun pixelExtent(regionExtent: Float, pageExtent: Float, viewportExtent: Int): Int =
        (regionExtent * pageExtent).roundToInt().coerceIn(1, max(1, viewportExtent))
}
