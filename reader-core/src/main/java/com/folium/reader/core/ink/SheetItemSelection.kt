package com.folium.reader.core.ink

import kotlin.math.max
import kotlin.math.min

/**
 * [InkSelection.kt]'s own tap, lasso, box, bounds, move and resize rules, generalised to a mix of
 * [InkStroke]s and [SheetTextBox]s through [SheetItem]. Kept in its own file, with names distinct
 * from the stroke-only functions they mirror, because a `List<InkStroke>` and a `List<SheetItem>`
 * overload of the same function name share one JVM signature once generics are erased and would
 * clash; every existing stroke-only function keeps its own name and signature unchanged.
 */

/**
 * How finely [isTextBoxMostlyInsidePolygon] samples a text box's own bounding rectangle to estimate
 * how much of its area lies inside a lasso or box selection, rather than doing exact polygon
 * clipping: an [SHEET_ITEM_AREA_SAMPLE_GRID] x [SHEET_ITEM_AREA_SAMPLE_GRID] grid of sample points,
 * each the centre of its own cell. A text box's own bounds are always an axis-aligned rectangle, so
 * this can only misjudge area within one grid cell's width of the polygon boundary crossing the box —
 * at most `1 / SHEET_ITEM_AREA_SAMPLE_GRID`, 12.5%, of the box's own width or height along whichever
 * edge the boundary crosses, and typically far less since most of a cell still falls unambiguously on
 * one side.
 */
private const val SHEET_ITEM_AREA_SAMPLE_GRID = 8

/** The minimum width in sheet units a text box's own resize may shrink it to; reuses [MIN_DIAGONAL_SHEET_UNITS], the same floor [selectionResizeScale] and [recognizeShape] already require of comparable geometry. */
private const val MIN_TEXT_WIDTH_SHEET_UNITS = MIN_DIAGONAL_SHEET_UNITS

/**
 * The item(s) a tap at [point] selects, generalising [strokeGroupAtTap] over [items]: a stroke is hit
 * by that same rule, expanded to its joined shape; a text box is hit when [point] lies within
 * [toleranceSheetUnits] of its own [SheetTextBox.bounds]. When both a stroke and a text box are hit,
 * the topmost by [SheetItem.sequence] wins. Empty when nothing is hit.
 */
fun itemGroupAtTap(items: List<SheetItem>, point: SheetPoint, toleranceSheetUnits: Float): Set<StrokeId> {
    val strokes = items.filterIsInstance<SheetItem.Stroke>().map { it.stroke }
    val textBoxes = items.filterIsInstance<SheetItem.Text>().map { it.textBox }

    val hitStrokeIds = strokesHitBy(listOf(point), toleranceSheetUnits, strokes)
    val topmostStroke = strokes.filter { it.id in hitStrokeIds }.maxByOrNull { it.sequence }
    val topmostText = textBoxes
        .filter { it.bounds.inflate(toleranceSheetUnits).contains(point) }
        .maxByOrNull { it.sequence }

    return when {
        topmostText != null && (topmostStroke == null || topmostText.sequence > topmostStroke.sequence) ->
            setOf(topmostText.id)
        topmostStroke != null -> strokeGroupAtTap(strokes, point, toleranceSheetUnits)
        else -> emptySet()
    }
}

/**
 * The items among [items] a lasso selects: a stroke by [selectByLasso]'s own length-weighted rule, a
 * text box by [isTextBoxMostlyInsidePolygon]'s area-sampling rule. Fewer than 3 points in [polygon]
 * selects nothing.
 */
fun selectItemsByLasso(items: List<SheetItem>, polygon: List<SheetPoint>): Set<StrokeId> {
    if (polygon.size < 3) return emptySet()
    return selectItemsByPolygon(items, polygon)
}

/** [selectItemsByLasso]'s own rule, against the rectangle whose opposite corners are [corner1] and [corner2]. */
fun selectItemsByRectangle(items: List<SheetItem>, corner1: SheetPoint, corner2: SheetPoint): Set<StrokeId> {
    val left = min(corner1.x, corner2.x)
    val top = min(corner1.y, corner2.y)
    val right = max(corner1.x, corner2.x)
    val bottom = max(corner1.y, corner2.y)

    val polygon = listOf(
        SheetPoint(left, top),
        SheetPoint(right, top),
        SheetPoint(right, bottom),
        SheetPoint(left, bottom)
    )
    return selectItemsByPolygon(items, polygon)
}

private fun selectItemsByPolygon(items: List<SheetItem>, polygon: List<SheetPoint>): Set<StrokeId> {
    val strokes = items.filterIsInstance<SheetItem.Stroke>().map { it.stroke }
    val textBoxes = items.filterIsInstance<SheetItem.Text>().map { it.textBox }

    val selected = selectByLasso(strokes, polygon).toMutableSet()
    for (textBox in textBoxes) {
        if (isTextBoxMostlyInsidePolygon(textBox, polygon)) selected += textBox.id
    }
    return selected
}

private fun isTextBoxMostlyInsidePolygon(textBox: SheetTextBox, polygon: List<SheetPoint>): Boolean {
    val bounds = textBox.bounds
    val grid = SHEET_ITEM_AREA_SAMPLE_GRID
    var insideCount = 0

    for (row in 0 until grid) {
        for (col in 0 until grid) {
            val fractionX = (col + 0.5f) / grid
            val fractionY = (row + 0.5f) / grid
            val sample = SheetPoint(bounds.left + bounds.width * fractionX, bounds.top + bounds.height * fractionY)
            if (isInsidePolygon(sample, polygon)) insideCount++
        }
    }

    return insideCount > (grid * grid) / 2
}

/** The smallest [SheetRect] containing every one of [items]' own [SheetItem.bounds]; `null` for an empty selection. */
fun itemSelectionBounds(items: List<SheetItem>): SheetRect? = items.map { it.bounds }.reduceOrNull(SheetRect::union)

/**
 * A copy of [textBox] moved by ([dx], [dy]) in sheet units, keeping every other field, with a fresh
 * id from [newId] and a fresh sequence from [newSequence] — the same convention [translateStrokes]
 * follows for a stroke.
 */
fun translateTextBox(textBox: SheetTextBox, dx: Float, dy: Float, newId: () -> StrokeId, newSequence: () -> Long): SheetTextBox =
    SheetTextBox(
        id = newId(),
        topLeft = SheetPoint(textBox.topLeft.x + dx, textBox.topLeft.y + dy),
        widthSheetUnits = textBox.widthSheetUnits,
        heightSheetUnits = textBox.heightSheetUnits,
        text = textBox.text,
        font = textBox.font,
        sizePt = textBox.sizePt,
        style = textBox.style,
        colorArgb = textBox.colorArgb,
        sequence = newSequence(),
        alignment = textBox.alignment
    )

/**
 * A copy of [textBox] with its own [SheetTextBox.topLeft] mapped `anchor + (topLeft - anchor) *
 * (scaleX, scaleY)` — the same anchor-and-per-axis-factor mapping [scaleStrokes] applies to a
 * stroke's own samples — while [SheetTextBox.widthSheetUnits], [SheetTextBox.heightSheetUnits] and
 * every other field stay exactly as [textBox] held them: a resize dragged against a selection holding
 * more than [textBox] alone repositions a text box without changing its own text size or wrap width,
 * unlike [textBoxWidthResize], which only ever applies to a selection that is exactly one text box.
 * Takes a fresh id from [newId] and a fresh sequence from [newSequence], the same convention
 * [translateTextBox] follows.
 */
fun scaleTextBoxPosition(
    textBox: SheetTextBox,
    anchor: SheetPoint,
    scaleX: Float,
    scaleY: Float,
    newId: () -> StrokeId,
    newSequence: () -> Long
): SheetTextBox = SheetTextBox(
    id = newId(),
    topLeft = SheetPoint(
        anchor.x + (textBox.topLeft.x - anchor.x) * scaleX,
        anchor.y + (textBox.topLeft.y - anchor.y) * scaleY
    ),
    widthSheetUnits = textBox.widthSheetUnits,
    heightSheetUnits = textBox.heightSheetUnits,
    text = textBox.text,
    font = textBox.font,
    sizePt = textBox.sizePt,
    style = textBox.style,
    colorArgb = textBox.colorArgb,
    sequence = newSequence(),
    alignment = textBox.alignment
)

/** The new left and right edges, in sheet units, that a text box's own width-resize drag produced; see [textBoxWidthResize]. */
data class TextBoxWidthResize(val left: Float, val right: Float)

/**
 * The new left/right edges dragging [corner] of [textBox] to [dragPoint] represents: only the box's
 * own width changes, clamped so it never falls under [MIN_TEXT_WIDTH_SHEET_UNITS]. Its top edge — and
 * so its [SheetTextBox.topLeft].y — is never moved by this helper regardless of which corner is
 * dragged, and its bottom edge is not part of the result at all, because [SheetTextBox.heightSheetUnits]
 * is re-measured by the caller from the rewrapped text rather than resized here.
 */
fun textBoxWidthResize(textBox: SheetTextBox, corner: SelectionCorner, dragPoint: SheetPoint): TextBoxWidthResize {
    val bounds = textBox.bounds
    val draggingLeftEdge = corner == SelectionCorner.TOP_LEFT || corner == SelectionCorner.BOTTOM_LEFT

    return if (draggingLeftEdge) {
        val right = bounds.right
        TextBoxWidthResize(left = min(dragPoint.x, right - MIN_TEXT_WIDTH_SHEET_UNITS), right = right)
    } else {
        val left = bounds.left
        TextBoxWidthResize(left = left, right = max(dragPoint.x, left + MIN_TEXT_WIDTH_SHEET_UNITS))
    }
}
