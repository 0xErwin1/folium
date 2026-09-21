package com.folium.reader.ink

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
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
    const val TEXT = "M5 6V4H17V6 M11 4V18 M8.5 18H13.5"
    const val SHAPE = "M3.5 4.5H10.5V10.5H3.5Z M10.5 7.5H17 M14.5 5L17 7.5L14.5 10 M12 13.5H18.5V18.5H12Z"
    const val SELECT = "M11 4C6 4 3.5 7 3.5 10.5C3.5 14 6.5 16 11 16C15.5 16 18.5 14 18.5 10.5C18.5 7 16 4 11 4Z M11 16V19"
    const val ERASER = "M5 17H17 M6 14L12 5L16.5 8.5L11 17"

    /** Double left chevron, the rail's own "collapse" mark (`design5-diff.md`, T-Lapiz/T-Escribir/T-Hoja OCULTAR cell). */
    const val HIDE = "M12 5L6.5 11L12 17 M17 5L11.5 11L17 17"

    /** Double right chevron, the hidden tab's own "reopen" mark (`design5-diff.md`, T-EscribirOculta). */
    const val SHOW = "M10 5L15.5 11L10 17 M5 5L10.5 11L5 17"
}

/** [size]-agnostic units the paths above are authored in; every glyph is drawn on a square of this many units. */
private const val GLYPH_VIEWBOX_UNITS = 22f
private val GLYPH_STROKE_WIDTH = 1.6.dp

private fun svgPath(data: String): Path = PathParser().parsePathString(data).toPath()

private val ViewPath = svgPath(SheetRailGlyphPaths.VIEW)
private val PenPath = svgPath(SheetRailGlyphPaths.PEN)
private val HighlightPath = svgPath(SheetRailGlyphPaths.HIGHLIGHT)
private val TextPath = svgPath(SheetRailGlyphPaths.TEXT)
private val ShapePath = svgPath(SheetRailGlyphPaths.SHAPE)
private val SelectPath = svgPath(SheetRailGlyphPaths.SELECT)
private val EraserPath = svgPath(SheetRailGlyphPaths.ERASER)
private val HidePath = svgPath(SheetRailGlyphPaths.HIDE)
private val ShowPath = svgPath(SheetRailGlyphPaths.SHOW)

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

/** The TEXT tool's own mark ("TEXTO", `rail-spec.md` 1.1). */
internal fun DrawScope.drawTextRailGlyph(tint: Color) = drawRailGlyph(TextPath, tint)

/** The SHAPE tool's own mark ("FORMA", `rail-spec.md` 1.1). */
internal fun DrawScope.drawShapeRailGlyph(tint: Color) = drawRailGlyph(ShapePath, tint)

/** The SELECT tool's own mark ("ELEGIR", `rail-spec.md` 1.1: the same path as the old LAZO rail cell, only the label changed). */
internal fun DrawScope.drawSelectRailGlyph(tint: Color) = drawRailGlyph(SelectPath, tint)

/** The ERASER tool's own mark ("GOMA", `D3/T-Lapiz.dc.html:98-108`). */
internal fun DrawScope.drawEraserRailGlyph(tint: Color) = drawRailGlyph(EraserPath, tint)

/** The rail foot's own "OCULTAR" mark, and the hidden tab's own reopen mark, mirrored (`design5-diff.md`). */
internal fun DrawScope.drawHideRailGlyph(tint: Color) = drawRailGlyph(HidePath, tint)

/** The hidden tab's own "show the rail" mark (`design5-diff.md`, T-EscribirOculta). */
internal fun DrawScope.drawShowRailGlyph(tint: Color) = drawRailGlyph(ShowPath, tint)

/**
 * The verbatim SVG path data for the shape panel's own FIGURA options, drawn on a 56x20 viewBox
 * (`rail-spec.md` 2.2, FORMA panel). Distinct from [SheetRailGlyphPaths] since these are wider than
 * tall, matching Piece A's own `<svg width="56" height="20">` rather than the rail's square glyphs.
 */
private object SheetShapeOptionGlyphPaths {
    const val LINE = "M6 16L50 4"
    const val ARROW = "M6 10H48 M41 4L48 10L41 16"
    const val BOX = "M10 3H46V17H10Z"
    const val ELLIPSE = "M28 3C40 3 48 6 48 10S40 17 28 17S8 14 8 10S16 3 28 3Z"
}

private const val SHAPE_OPTION_GLYPH_VIEWBOX_WIDTH = 56f
private const val SHAPE_OPTION_GLYPH_VIEWBOX_HEIGHT = 20f

private val ShapeOptionLinePath = svgPath(SheetShapeOptionGlyphPaths.LINE)
private val ShapeOptionArrowPath = svgPath(SheetShapeOptionGlyphPaths.ARROW)
private val ShapeOptionBoxPath = svgPath(SheetShapeOptionGlyphPaths.BOX)
private val ShapeOptionEllipsePath = svgPath(SheetShapeOptionGlyphPaths.ELLIPSE)

/**
 * Draws [path] — authored against a [SHAPE_OPTION_GLYPH_VIEWBOX_WIDTH]x[SHAPE_OPTION_GLYPH_VIEWBOX_HEIGHT]
 * viewBox — scaled uniformly to fit this [DrawScope] and centered within it, so the glyph keeps its
 * own aspect ratio regardless of how wide the option cell it sits in ends up being. [dashIntervals],
 * when given, is in the path's own viewBox units, the same convention [drawPenTipOptionGlyph] follows
 * for the pencil tip's own dash pattern.
 */
private fun DrawScope.drawShapeOptionGlyph(path: Path, tint: Color, dashIntervals: FloatArray? = null) {
    val scaleFactor = minOf(size.width / SHAPE_OPTION_GLYPH_VIEWBOX_WIDTH, size.height / SHAPE_OPTION_GLYPH_VIEWBOX_HEIGHT)
    val offsetX = (size.width - SHAPE_OPTION_GLYPH_VIEWBOX_WIDTH * scaleFactor) / 2f
    val offsetY = (size.height - SHAPE_OPTION_GLYPH_VIEWBOX_HEIGHT * scaleFactor) / 2f

    translate(offsetX, offsetY) {
        scale(scaleFactor, scaleFactor, pivot = Offset.Zero) {
            drawPath(
                path = path,
                color = tint,
                style = Stroke(
                    width = GLYPH_STROKE_WIDTH.toPx() / scaleFactor,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = dashIntervals?.let { PathEffect.dashPathEffect(it, phase = 0f) }
                )
            )
        }
    }
}

/** The LÍNEA figure option's own glyph (`rail-spec.md` 2.2, FORMA panel). */
internal fun DrawScope.drawShapeOptionLineGlyph(tint: Color) = drawShapeOptionGlyph(ShapeOptionLinePath, tint)

/** The FLECHA figure option's own glyph. */
internal fun DrawScope.drawShapeOptionArrowGlyph(tint: Color) = drawShapeOptionGlyph(ShapeOptionArrowPath, tint)

/** The CAJA figure option's own glyph. */
internal fun DrawScope.drawShapeOptionBoxGlyph(tint: Color) = drawShapeOptionGlyph(ShapeOptionBoxPath, tint)

/** The ELIPSE figure option's own glyph. */
internal fun DrawScope.drawShapeOptionEllipseGlyph(tint: Color) = drawShapeOptionGlyph(ShapeOptionEllipsePath, tint)

/**
 * The verbatim SVG path data for the pen panel's own PUNTA options, one squiggle shared by all three
 * tips, each drawn with that tip's own stroke (`rail-spec.md` 2.2, LÁPIZ panel). Authored on the same
 * 56x20 viewBox as [SheetShapeOptionGlyphPaths], Piece A's own glyph box.
 */
private const val PEN_TIP_OPTION_GLYPH_PATH = "M2 12C8 4 12 18 18 10S30 4 36 11S48 16 54 8"

private val PenTipOptionPath = svgPath(PEN_TIP_OPTION_GLYPH_PATH)

/** The pencil tip's own dash pattern (`rail-spec.md` 2.2: `stroke-dasharray="1.2 1.6"`), in the path's own viewBox units so it scales with the glyph rather than staying a fixed device size. */
private val PenTipPencilDashIntervals = floatArrayOf(1.2f, 1.6f)

/**
 * Draws [PenTipOptionPath] scaled uniformly to fit this [DrawScope] and centered within it, exactly
 * like [drawShapeOptionGlyph], but at [strokeWidthDp] and, when given, [dashIntervals] rather than a
 * single fixed stroke — the three tips share one path and differ only in how it is stroked.
 */
private fun DrawScope.drawPenTipOptionGlyph(tint: Color, strokeWidthDp: Dp, dashIntervals: FloatArray? = null) {
    val scaleFactor = minOf(size.width / SHAPE_OPTION_GLYPH_VIEWBOX_WIDTH, size.height / SHAPE_OPTION_GLYPH_VIEWBOX_HEIGHT)
    val offsetX = (size.width - SHAPE_OPTION_GLYPH_VIEWBOX_WIDTH * scaleFactor) / 2f
    val offsetY = (size.height - SHAPE_OPTION_GLYPH_VIEWBOX_HEIGHT * scaleFactor) / 2f

    translate(offsetX, offsetY) {
        scale(scaleFactor, scaleFactor, pivot = Offset.Zero) {
            drawPath(
                path = PenTipOptionPath,
                color = tint,
                style = Stroke(
                    width = strokeWidthDp.toPx() / scaleFactor,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = dashIntervals?.let { PathEffect.dashPathEffect(it, phase = 0f) }
                )
            )
        }
    }
}

/** The BOLÍGRAFO tip's own glyph (`rail-spec.md` 2.2: `stroke-width="1.6"` round/round). */
internal fun DrawScope.drawPenTipBallpointGlyph(tint: Color) = drawPenTipOptionGlyph(tint, strokeWidthDp = 1.6.dp)

/** The PLUMA tip's own glyph (`rail-spec.md` 2.2: `stroke-width="3.2"` round/round). */
internal fun DrawScope.drawPenTipFountainGlyph(tint: Color) = drawPenTipOptionGlyph(tint, strokeWidthDp = 3.2.dp)

/** The LÁPIZ tip's own glyph (`rail-spec.md` 2.2: `stroke-width="2.2"`, `stroke-dasharray="1.2 1.6"`). */
internal fun DrawScope.drawPenTipPencilGlyph(tint: Color) =
    drawPenTipOptionGlyph(tint, strokeWidthDp = 2.2.dp, dashIntervals = PenTipPencilDashIntervals)

/**
 * The verbatim SVG path data for the eraser panel's own MODO options (`rail-spec.md` 2.2, GOMA panel).
 * TRAZO ENTERO reuses the pen panel's own squiggle path, dashed rather than solid; PARCIAL is its own
 * path on the same 56x20 viewBox as [SheetShapeOptionGlyphPaths].
 */
private const val ERASER_MODE_PARTIAL_GLYPH_PATH = "M2 12C8 4 12 18 18 10 M38 11C42 14 48 16 54 8"

/** TRAZO ENTERO's own dash pattern (`rail-spec.md` 2.2: `stroke-dasharray="1 4"`), in the path's own viewBox units. */
private val EraserModeWholeStrokeDashIntervals = floatArrayOf(1f, 4f)

private val EraserModePartialPath = svgPath(ERASER_MODE_PARTIAL_GLYPH_PATH)

/** The TRAZO ENTERO mode option's own glyph: the pen panel's own squiggle, drawn dashed. */
internal fun DrawScope.drawEraserModeWholeStrokeGlyph(tint: Color) =
    drawPenTipOptionGlyph(tint, strokeWidthDp = GLYPH_STROKE_WIDTH, dashIntervals = EraserModeWholeStrokeDashIntervals)

/** The PARCIAL mode option's own glyph: two dashes of squiggle, the eraser's own gap already cut into it. */
internal fun DrawScope.drawEraserModePartialGlyph(tint: Color) = drawShapeOptionGlyph(EraserModePartialPath, tint)

/**
 * The verbatim SVG path data for the select panel's own MODO options (`rail-spec.md` 2.2, ELEGIR
 * panel). LAZO and RECUADRO reuse the shape panel's own ellipse and box paths, dashed rather than
 * solid; TOQUE is its own path on the same 56x20 viewBox as [SheetShapeOptionGlyphPaths].
 */
private const val SELECT_MODE_TAP_GLYPH_PATH = "M28 5A5 5 0 1 0 28 15A5 5 0 1 0 28 5 M28 9.5V10.5"

/** LAZO's and RECUADRO's own dash pattern (`rail-spec.md` 2.2: `stroke-dasharray="3 3"`), in the path's own viewBox units. */
private val SelectModeDashIntervals = floatArrayOf(3f, 3f)

private val SelectModeTapPath = svgPath(SELECT_MODE_TAP_GLYPH_PATH)

/** The TOQUE mode option's own glyph. */
internal fun DrawScope.drawSelectModeTapGlyph(tint: Color) = drawShapeOptionGlyph(SelectModeTapPath, tint)

/** The LAZO mode option's own glyph: the shape panel's own ellipse, drawn dashed. */
internal fun DrawScope.drawSelectModeLassoGlyph(tint: Color) = drawShapeOptionGlyph(ShapeOptionEllipsePath, tint, SelectModeDashIntervals)

/** The RECUADRO mode option's own glyph: the shape panel's own box, drawn dashed. */
internal fun DrawScope.drawSelectModeBoxGlyph(tint: Color) = drawShapeOptionGlyph(ShapeOptionBoxPath, tint, SelectModeDashIntervals)
