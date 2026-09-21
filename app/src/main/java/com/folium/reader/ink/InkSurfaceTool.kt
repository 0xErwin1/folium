package com.folium.reader.ink

/**
 * The drawing mode the sheet surface is currently in, independent of [com.folium.reader.core.ink.InkTool]:
 * [PEN] commits a stroke as [com.folium.reader.core.ink.InkTool.PEN] and [HIGHLIGHTER] as
 * [com.folium.reader.core.ink.InkTool.HIGHLIGHTER], the only two the model records today. [SHAPE]
 * also commits as [com.folium.reader.core.ink.InkTool.PEN] — a straightened line, arrow, box or
 * ellipse is ordinary pen ink to the model, only its geometry is built from a drag's two endpoints
 * rather than from freehand samples. [VIEW] draws nothing at all: every pointer pans and zooms the
 * sheet instead, see [InkGestureArbiter]. [SELECT] draws nothing either: it only marks a set of
 * already-committed strokes without changing any of them. [TEXT] places or edits a typed
 * [com.folium.reader.core.ink.SheetTextBox] rather than drawing any ink at all.
 */
enum class InkSurfaceTool { PEN, HIGHLIGHTER, SHAPE, ERASER, VIEW, SELECT, TEXT }

/**
 * How a [InkSurfaceTool.SELECT] gesture decides which strokes it marks (`rail-spec.md` 2.2, ELEGIR
 * panel's own MODO): [TAP] takes the topmost stroke — or joined shape — under the pointer's down
 * point, exactly [com.folium.reader.core.ink.strokeGroupAtTap]'s own rule; [LASSO] and [BOX] instead
 * take every stroke more than half inside the shape a drag traces out, through
 * [com.folium.reader.core.ink.selectByLasso] and [com.folium.reader.core.ink.selectByRectangle]. Any
 * mode still falls back to a tap once its own gesture never leaves the touch slop.
 */
enum class PenSelectMode { TAP, LASSO, BOX }

/**
 * What the eraser tool removes on each gesture (`rail-spec.md` 2.2, GOMA panel): [WHOLE_STROKE] takes
 * an entire stroke the moment the eraser touches it, the default and the cheaper of the two on e-ink
 * since it costs one refresh; [PARTIAL] only removes the ink the eraser's own path actually passed
 * over, splitting a touched stroke around it through [PartialEraseSession].
 */
enum class InkEraserMode { WHOLE_STROKE, PARTIAL }

/**
 * Whether a freehand stroke straightens into a line, arrow, box or ellipse once
 * [com.folium.reader.core.ink.recognizeShape] reads it as one, rather than keeping it as ordinary
 * handwriting (`rail-spec.md` 2.2, LÁPIZ panel's own ENDEREZAR, and its own HIGHLIGHT-panel
 * counterpart): [NEVER] never straightens anything; [ON_HOLD] straightens only once the pointer
 * comes to rest at the stroke's own end, so ordinary handwriting is left alone; [ALWAYS] straightens
 * every recognised stroke the moment it lifts, handwriting included, which is why it is not the
 * default. Both [InkSurfaceTool.PEN] and [InkSurfaceTool.HIGHLIGHTER] have their own, independent
 * setting of this type — see [straightenModeFor] for which one governs a given stroke; the shape
 * tool's own drag is never affected, since it already commits straight by construction.
 */
enum class InkStraightenMode { NEVER, ON_HOLD, ALWAYS }

/**
 * Which of the pen's or the highlighter's own straighten setting governs a stroke drawn with [tool]:
 * [penMode] for [InkSurfaceTool.PEN], [highlighterMode] for [InkSurfaceTool.HIGHLIGHTER], and
 * [InkStraightenMode.NEVER] for every other tool, none of which ever straightens.
 */
internal fun straightenModeFor(tool: InkSurfaceTool, penMode: InkStraightenMode, highlighterMode: InkStraightenMode): InkStraightenMode =
    when (tool) {
        InkSurfaceTool.PEN -> penMode
        InkSurfaceTool.HIGHLIGHTER -> highlighterMode
        else -> InkStraightenMode.NEVER
    }

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
