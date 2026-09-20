package com.folium.reader.ink

import android.graphics.Matrix
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import com.folium.reader.core.ink.InkTip

/** How far a stroke's rendered edge may deviate from its true input path, in stroke-space units; small relative to the pen widths in [InkPenWidths]. */
private const val BRUSH_EPSILON_STROKE_UNITS = 0.1f

/**
 * The `androidx.ink` 1.0.0 stock brush family that best matches [tip].
 *
 * 1.0.0 ships no dedicated pencil family — [androidx.ink.brush.StockBrushes.pencilUnstable] exists
 * but is explicitly unstable — so [InkTip.PENCIL] reuses the marker family, the same as
 * [InkTip.BALLPOINT], until a stable pencil brush ships.
 */
fun brushFamilyFor(tip: InkTip): BrushFamily = when (tip) {
    InkTip.BALLPOINT -> StockBrushes.marker()
    InkTip.FOUNTAIN -> StockBrushes.pressurePen()
    InkTip.PENCIL -> StockBrushes.marker()
}

/** The [Brush] to author a pen stroke with, sized from a sheet-unit width via [StrokeSpace]. */
fun brushFor(tip: InkTip, colorArgb: Int, widthSheetUnits: Float): Brush = Brush.createWithColorIntArgb(
    family = brushFamilyFor(tip),
    colorIntArgb = colorArgb,
    size = StrokeSpace.sheetToStrokeSpace(widthSheetUnits),
    epsilon = BRUSH_EPSILON_STROKE_UNITS
)

/**
 * The matrix `InProgressStrokesView.startStroke` needs as `motionEventToWorldTransform`, paired with
 * an identity `strokeToWorldTransform`, so a stroke's recorded samples land directly in
 * [StrokeSpace] rather than in this view's own pixel space: `world == stroke space` for every stroke
 * this app starts. Built fresh from [viewport] at the moment a stroke starts; a later pan or zoom
 * never affects a stroke already in progress, since the second-pointer-cancels rule in
 * [InkGestureArbiter] never lets an in-progress stroke and a live pan/zoom overlap.
 */
fun motionEventToStrokeSpaceTransform(viewport: SheetViewport): Matrix {
    val strokeUnitsPerViewPx = StrokeSpace.UNITS_PER_SHEET_UNIT / viewport.scale
    val translateX = StrokeSpace.sheetToStrokeSpace(viewport.topLeft.x)
    val translateY = StrokeSpace.sheetToStrokeSpace(viewport.topLeft.y)

    return Matrix().apply {
        setScale(strokeUnitsPerViewPx, strokeUnitsPerViewPx)
        postTranslate(translateX, translateY)
    }
}

/** The inverse direction of [motionEventToStrokeSpaceTransform]: maps [StrokeSpace] coordinates to this view's pixel space, for [androidx.ink.rendering.android.canvas.CanvasStrokeRenderer]. */
fun strokeSpaceToViewTransform(viewport: SheetViewport): Matrix {
    val viewPxPerStrokeUnit = viewport.scale / StrokeSpace.UNITS_PER_SHEET_UNIT

    return Matrix().apply {
        setScale(viewPxPerStrokeUnit, viewPxPerStrokeUnit)
        postTranslate(-viewport.topLeft.x * viewport.scale, -viewport.topLeft.y * viewport.scale)
    }
}
