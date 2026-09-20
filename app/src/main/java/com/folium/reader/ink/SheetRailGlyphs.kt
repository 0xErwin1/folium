package com.folium.reader.ink

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The verbatim SVG path data for each rail tool's glyph, drawn on a 22x22 viewBox
 * (`D3/T-Lapiz.dc.html:98-108`). Every path is a literal copy of the design's own `d` attribute so
 * the glyph a designer inspects there is the one this rail draws.
 */
private object SheetRailGlyphPaths {
    const val VIEW = "M11 3V19 M3 11H19 M8.5 5.5L11 3L13.5 5.5 M8.5 16.5L11 19L13.5 16.5 M5.5 8.5L3 11L5.5 13.5 M16.5 8.5L19 11L16.5 13.5"
    const val PEN = "M4 18L6.5 17L17 6.5L15.5 5L5 15.5L4 18Z"
    const val HIGHLIGHT = "M4 18H9 M6 15L15.5 5.5L17.5 7.5L8 17"
    const val ERASER = "M5 17H17 M6 14L12 5L16.5 8.5L11 17"
}

/** [size]-agnostic units the paths above are authored in; every glyph is drawn on a square of this many units. */
private const val GLYPH_VIEWBOX_UNITS = 22f
private val GLYPH_STROKE_WIDTH = 1.6.dp

private fun svgPath(data: String): Path = PathParser().parsePathString(data).toPath()

private val ViewPath = svgPath(SheetRailGlyphPaths.VIEW)
private val PenPath = svgPath(SheetRailGlyphPaths.PEN)
private val HighlightPath = svgPath(SheetRailGlyphPaths.HIGHLIGHT)
private val EraserPath = svgPath(SheetRailGlyphPaths.ERASER)

/**
 * Draws [path] — authored against a [GLYPH_VIEWBOX_UNITS]-unit square — scaled to fill this
 * [DrawScope], stroked at a constant [GLYPH_STROKE_WIDTH] regardless of that scale.
 */
private fun DrawScope.drawRailGlyph(path: Path, tint: Color) {
    val scaleFactor = size.width / GLYPH_VIEWBOX_UNITS
    scale(scaleFactor, scaleFactor, pivot = Offset.Zero) {
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = GLYPH_STROKE_WIDTH.toPx() / scaleFactor, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/** The VIEW tool's own mark ("VISTA", `D3/T-Lapiz.dc.html:98-108`). */
internal fun DrawScope.drawViewRailGlyph(tint: Color) = drawRailGlyph(ViewPath, tint)

/** The PEN tool's own mark ("LÁPIZ", `D3/T-Lapiz.dc.html:98-108`). */
internal fun DrawScope.drawPenRailGlyph(tint: Color) = drawRailGlyph(PenPath, tint)

/** The HIGHLIGHTER tool's own mark ("RESALTA", `rail-spec.md` 1.1). */
internal fun DrawScope.drawHighlightRailGlyph(tint: Color) = drawRailGlyph(HighlightPath, tint)

/** The ERASER tool's own mark ("GOMA", `D3/T-Lapiz.dc.html:98-108`). */
internal fun DrawScope.drawEraserRailGlyph(tint: Color) = drawRailGlyph(EraserPath, tint)
