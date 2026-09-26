package com.folium.reader.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import kotlin.math.roundToInt

/**
 * Draws a page's committed ink, [render], over the page where [layoutIn] lays the page out in this
 * cell, as vectors on the cell's own canvas rather than as a bitmap, so it costs nothing against the
 * reader's raster budget and stays sharp at any zoom. Draws nothing while [hidden], which a page
 * whose live drawing surface is mounted sets so its ink is never drawn twice.
 */
@Composable
internal fun PageInkLayer(
    render: PageInkRender?,
    layoutIn: (ReaderViewport) -> ViewportLayout,
    hidden: Boolean,
    modifier: Modifier = Modifier
) {
    if (render == null || hidden) return

    val renderer = remember { CanvasStrokeRenderer.create() }
    val polylinePaint = remember { pageInkPolylinePaint() }

    Canvas(modifier.fillMaxSize()) {
        val viewport = ReaderViewport.of(size.width.roundToInt(), size.height.roundToInt()) ?: return@Canvas
        val frame = pageInkDrawFrame(layoutIn(viewport))

        drawIntoCanvas { canvas -> drawPageInk(canvas.nativeCanvas, render, frame, renderer, polylinePaint) }
    }
}
