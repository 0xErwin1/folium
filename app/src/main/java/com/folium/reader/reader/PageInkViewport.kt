package com.folium.reader.reader

import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.ink.SheetViewport
import com.folium.reader.ink.StrokeSpace

/**
 * The pinned [SheetViewport] that lays a page's ink over the page exactly where [layout] draws it:
 * one page-ink unit is the page's on-screen width, so the scale is [ViewportLayout.pageWidth] pixels
 * per unit, and the page's top-left corner lands on [ViewportLayout.originX]/[ViewportLayout.originY].
 *
 * Page-ink units are isotropic ([com.folium.reader.core.ink.PageInkExtent]), so the page's bottom
 * edge lands on `originY + pageHeight` only because the layout keeps the page's own aspect; nothing
 * here clamps, since the reader, not the ink layer, owns zoom and pan.
 */
fun pageInkViewport(layout: ViewportLayout): SheetViewport = SheetViewport.pinned(
    viewWidthPx = layout.viewport.widthPx.toFloat(),
    viewHeightPx = layout.viewport.heightPx.toFloat(),
    scale = layout.pageWidth,
    topLeft = SheetPoint(-layout.originX / layout.pageWidth, -layout.originY / layout.pageWidth)
)

/**
 * [pageInkViewport] for [layout], or `null` when [layout] places no measurable page, a page width
 * that is zero, negative or not finite, as a layout taken before the page is measured may.
 */
fun pageInkViewportOrNull(layout: ViewportLayout): SheetViewport? {
    if (!layout.pageWidth.isFinite() || layout.pageWidth <= 0f) return null

    return pageInkViewport(layout)
}

/**
 * How to draw a page's committed ink over [layout] on a plain canvas: scale stroke-space coordinates
 * by [scale], then translate by [translateX]/[translateY], and clip to [clip], the page's own
 * rectangle. Derived from [pageInkViewport], so read-only ink lands exactly where the live surface
 * draws the same strokes.
 */
data class PageInkDrawFrame(val scale: Float, val translateX: Float, val translateY: Float, val clip: ViewportRect)

fun pageInkDrawFrame(layout: ViewportLayout): PageInkDrawFrame {
    val viewport = pageInkViewport(layout)

    return PageInkDrawFrame(
        scale = viewport.scale / StrokeSpace.UNITS_PER_SHEET_UNIT,
        translateX = -viewport.topLeft.x * viewport.scale,
        translateY = -viewport.topLeft.y * viewport.scale,
        clip = ViewportRect(layout.originX, layout.originY, layout.pageWidth, layout.pageHeight)
    )
}
