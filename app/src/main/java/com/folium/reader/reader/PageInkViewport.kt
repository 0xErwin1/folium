package com.folium.reader.reader

import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.ink.SheetViewport

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
