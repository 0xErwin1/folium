package com.folium.reader.ink

/** The drawing mode the sheet surface is currently in, independent of [com.folium.reader.core.ink.InkTool]: the model only ever records a committed stroke as [com.folium.reader.core.ink.InkTool.PEN]. */
enum class InkSurfaceTool { PEN, ERASER }

/**
 * The three pen widths the surface offers, in sheet units. Each is defined as the sheet-unit
 * equivalent of a pixel width at the design's 1000-pixel-wide reference sheet, which is exactly
 * [StrokeSpace.UNITS_PER_SHEET_UNIT], so `StrokeSpace.sheetToStrokeSpace(width)` reproduces that
 * reference pixel width exactly.
 */
object InkPenWidths {
    const val THIN_SHEET_UNITS: Float = 2f / StrokeSpace.UNITS_PER_SHEET_UNIT
    const val MEDIUM_SHEET_UNITS: Float = 4f / StrokeSpace.UNITS_PER_SHEET_UNIT
    const val THICK_SHEET_UNITS: Float = 7f / StrokeSpace.UNITS_PER_SHEET_UNIT
}

/**
 * The ARGB colors the sheet surface paints its own background elements with, plus [themeInk]: the
 * current theme's own ink, which [resolveStrokeColor] resolves [STROKE_THEME_INK_SENTINEL_ARGB] to for
 * both the in-progress brush and every committed stroke drawn under the pen panel's THEME choice. Set
 * by the host rather than read from the Compose theme, since this package draws on a plain
 * [android.view.View] canvas and never touches Compose.
 */
data class InkSurfaceColors(val paper: Int, val field: Int, val rule: Int, val themeInk: Int) {
    companion object {
        /**
         * Never meant to reach the screen: [SheetPane] sets the theme's own colours on a fresh
         * [InkDrawingSurface] in the `AndroidView` factory, before its first draw, so no frame ever
         * paints with this placeholder.
         */
        val NEUTRAL_PLACEHOLDER = InkSurfaceColors(
            paper = 0xFFFFFFFF.toInt(),
            field = 0xFFE0E0E0.toInt(),
            rule = 0xFFB0B0B0.toInt(),
            themeInk = STROKE_THEME_INK_SENTINEL_ARGB
        )
    }
}
