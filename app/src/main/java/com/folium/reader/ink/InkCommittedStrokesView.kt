package com.folium.reader.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.InkTool
import com.folium.reader.core.ink.SheetItem
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetRect
import com.folium.reader.core.ink.SheetTemplate
import com.folium.reader.core.ink.SheetTextBox
import com.folium.reader.core.ink.StrokeId
import kotlin.math.ceil
import kotlin.math.floor

/** The selection outline's and the live lasso/box preview's own dash pattern, in device-independent pixels: an "on" dash a little longer than the gap, so a thin selection rectangle still reads as a line rather than a row of dots. */
private const val SELECTION_DASH_ON_DP: Float = 4f
private const val SELECTION_DASH_OFF_DP: Float = 3f

/** A resize handle's own drawn size, in device-independent pixels: a small square, well under its own much larger touch target (`rail-spec.md` 2.2, ELEGIR panel: "Dragging a corner resizes"). */
private const val SELECTION_HANDLE_SIZE_DP: Float = 8f

/**
 * Draws every committed (dry) stroke on a sheet, directly on this view's own hardware canvas —
 * never into an offscreen software bitmap — alongside the paper, the field outside it, and this
 * sheet's ruling. A sibling [InkDrawingSurface] draws only the in-progress stroke on top of this.
 *
 * Every mutating setter here invalidates and is meant to be called from the UI thread; the built
 * `androidx.ink` [Stroke] objects this view holds come from [InkMeshBuilder], one tile's worth of
 * strokes at a time.
 *
 * [textDesignPxPerPoint] is the owning surface's own [InkSurfaceMode.textDesignPxPerPoint], so a
 * committed text box draws at the size its editor showed.
 */
class InkCommittedStrokesView(context: Context, textDesignPxPerPoint: Float) : View(context) {

    private val renderer = CanvasStrokeRenderer.create()
    private val builtStrokes = LinkedHashMap<StrokeId, Pair<InkStroke, Stroke>>()

    private val textLayoutEngine = TextLayoutEngine(context, textDesignPxPerPoint)

    /** One [TextBoxLayout] per live text box, keyed by [SheetTextBox.id] and rebuilt only when that box's own data changes: an edit always mints a fresh id (see [InkDrawingSurface]), so a cache hit here means the box is unchanged since its last frame. */
    private val textLayoutCache = LinkedHashMap<StrokeId, Pair<SheetTextBox, TextBoxLayout>>()

    private val fieldPaint = Paint()
    private val paperPaint = Paint()
    private val rulePaint = Paint().apply { strokeWidth = 1f }
    private val eraserFootprintPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeWidth = resources.displayMetrics.density
    }

    /**
     * The selection outline's and the live lasso/box preview's own paint: a thin dashed line in view
     * pixels, so neither the dash nor the line width scales with zoom (`rail-spec.md` 2.2, ELEGIR
     * panel: "Selected block outline: border: 1px dashed {{c.ink}}").
     */
    private val selectionPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeWidth = resources.displayMetrics.density
        val density = resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(SELECTION_DASH_ON_DP * density, SELECTION_DASH_OFF_DP * density), 0f)
    }

    /** The resize handles' own paint: filled ink squares, view-space sized the same way [selectionPaint] is. */
    private val selectionHandlePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /** The eraser's own footprint while a gesture is in progress, or `null` between gestures. */
    data class EraserFootprint(val centerXPx: Float, val centerYPx: Float, val radiusPx: Float)

    /**
     * A live move or resize drag against the current selection: [hiddenIds] are the originals' own
     * [InkStroke.id]s and [SheetTextBox.id]s, kept out of [drawCommittedItems] for as long as this is
     * set. [strokes] are the dragged strokes' own already-built meshes, drawn through [transform] on
     * top of the ordinary stroke-to-view transform — a cheap per-frame matrix change rather than a
     * mesh rebuild. [textBoxes] are the dragged text box(es), already resolved to this frame's own
     * preview geometry by [InkDrawingSurface] — a plain translate for a move, or an anchor/scale
     * mapping or a live rewrap for a resize — and so drawn under the ordinary stroke-to-view transform
     * alone, never [transform] itself, which would otherwise stretch their own glyphs along with the
     * strokes. [strokes] stays set after the drag lifts, frozen at its own final [transform], until the
     * moved or resized strokes' own new meshes have finished building off the UI thread, so the drag's
     * own result never shows a gap between the drag's last frame and the first frame of the real,
     * committed strokes; [textBoxes] is cleared as soon as the commit's own text boxes are already
     * showing through [InkDrawingSurface.refreshTextBoxesOnCommittedView], since a text box needs no
     * asynchronous build to show correctly.
     */
    data class SelectionDragPreview(
        val hiddenIds: Set<StrokeId>,
        val strokes: List<Stroke>,
        val transform: Matrix,
        val textBoxes: List<SheetTextBox> = emptyList()
    )

    /** Set by [InkDrawingSurface] while an erase gesture is live; `null` removes it with no animation. */
    var eraserFootprint: EraserFootprint? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The SELECT tool's own live lasso preview while a gesture is dragging, in sheet space; empty between gestures. */
    var selectionLassoPreview: List<SheetPoint> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** The SELECT tool's own live box preview while a gesture is dragging, in sheet space; `null` between gestures. */
    var selectionBoxPreview: SheetRect? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The SELECT tool's own current selection, as its bounding box in sheet space; `null` when nothing is selected. */
    var selectionOutline: SheetRect? = null
        set(value) {
            field = value
            invalidate()
        }

    /** See [SelectionDragPreview]; `null` outside a live move or resize drag and once its own no-gap mesh swap has finished. */
    var selectionDragPreview: SelectionDragPreview? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * The shape tool's own live preview, built through the exact same [toInkStroke] path a committed
     * stroke is, so what a drag shows here and what [InkDrawingSurface] commits on lift are
     * pixel-identical. Drawn over every committed stroke but never added to [builtStrokes] itself:
     * this is scratch state for one in-progress drag, cleared the moment it lifts or cancels.
     */
    var shapePreview: List<Stroke> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Every live text box, drawn alongside [builtStrokes] through [drawCommittedItems]; see [layeredItemsForDraw] for their shared draw order. */
    var textBoxes: List<SheetTextBox> = emptyList()
        set(value) {
            field = value
            val liveIds = value.mapTo(mutableSetOf(), SheetTextBox::id)
            textLayoutCache.keys.retainAll(liveIds)
            invalidate()
        }

    var viewport: SheetViewport = SheetViewport.initial(viewWidthPx = 1f, viewHeightPx = 1f)
        set(value) {
            field = value
            invalidate()
        }

    var template: SheetTemplate = SheetTemplate.BLANK
        set(value) {
            field = value
            invalidate()
        }

    /** Whether the field, the paper and its rules are painted; `false` over a book page, whose PDF shows through underneath. */
    var drawsPaper: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** Where committed ink and its live previews may show, in view pixels; `null` leaves them unclipped, as on a sheet. */
    var inkClipPx: ViewRect? = null
        set(value) {
            field = value
            invalidate()
        }

    var colors: InkSurfaceColors = InkSurfaceColors.NEUTRAL_PLACEHOLDER
        set(value) {
            val inkChanged = value.themeInk != field.themeInk
            field = value
            if (inkChanged) recolorThemeInkStrokes()
            invalidate()
        }

    /**
     * Re-colours every committed stroke drawn under the pen panel's THEME choice to [colors]'s new
     * ink, without rebuilding any stroke's mesh from its samples: `Stroke.copy(brush)` reuses the
     * existing shape whenever the new brush's size, epsilon and family match the old one's, which a
     * colour-only change always does.
     */
    private fun recolorThemeInkStrokes() {
        for (id in builtStrokes.keys.toList()) {
            val (model, built) = builtStrokes.getValue(id)
            if (model.tool != InkTool.PEN || !isThemeInk(model.colorArgb)) continue

            val brush = brushFor(model.tip, resolveStrokeColor(model.colorArgb, colors.themeInk, model.tool), model.widthSheetUnits)
            builtStrokes[id] = model to built.copy(brush)
        }
    }

    /** Publishes a built stroke, replacing any earlier build of the same [InkStroke.id]. */
    fun putBuiltStroke(model: InkStroke, builtStroke: Stroke) {
        builtStrokes[model.id] = model to builtStroke
        invalidate()
    }

    fun removeStrokes(ids: Collection<StrokeId>) {
        for (id in ids) builtStrokes.remove(id)
        invalidate()
    }

    fun clearStrokes() {
        builtStrokes.clear()
        invalidate()
    }

    val builtStrokeCount: Int get() = builtStrokes.size

    override fun onDraw(canvas: Canvas) {
        if (drawsPaper) drawPaper(canvas)

        val checkpoint = canvas.save()
        inkClipPx?.let { clip -> canvas.clipRect(clip.left, clip.top, clip.right, clip.bottom) }

        drawCommittedItems(canvas)
        drawSelectionDragPreview(canvas)
        drawShapePreview(canvas)

        canvas.restoreToCount(checkpoint)

        drawEraserFootprint(canvas)
        drawSelectionLassoPreview(canvas)
        drawSelectionBoxPreview(canvas)
        drawSelectionOutline(canvas)
        drawSelectionHandles(canvas)
    }

    private fun drawPaper(canvas: Canvas) {
        fieldPaint.color = colors.field
        canvas.drawColor(colors.field)

        val paperLeftPx = viewport.sheetToView(SheetPoint(0f, viewport.topLeft.y)).x
        val paperRightPx = viewport.sheetToView(SheetPoint(1f, viewport.topLeft.y)).x
        paperPaint.color = colors.paper
        canvas.drawRect(paperLeftPx, 0f, paperRightPx, height.toFloat(), paperPaint)

        if (template == SheetTemplate.RULED) drawRules(canvas, paperLeftPx, paperRightPx)
    }

    /**
     * [SelectionDragPreview.strokes], transformed by [SelectionDragPreview.transform] on top of the
     * ordinary stroke-to-view transform: [renderer] still receives the ordinary transform alone for
     * its own level-of-detail decision, exactly as [drawCommittedStrokes] and [drawShapePreview] pass
     * it, since [SelectionDragPreview.transform] only ever repositions or rescales what is already
     * built, and never changes how finely it should have been tessellated. [SelectionDragPreview.textBoxes]
     * draw separately, under the ordinary transform alone: see [SelectionDragPreview]'s own doc for why.
     */
    private fun drawSelectionDragPreview(canvas: Canvas) {
        val preview = selectionDragPreview ?: return
        val transform = strokeSpaceToViewTransform(viewport)

        if (preview.strokes.isNotEmpty()) {
            val checkpoint = canvas.save()
            canvas.concat(preview.transform)
            canvas.concat(transform)

            for (stroke in preview.strokes) renderer.draw(canvas, stroke, transform)

            canvas.restoreToCount(checkpoint)
        }

        if (preview.textBoxes.isNotEmpty()) {
            val checkpoint = canvas.save()
            canvas.concat(transform)

            for (box in preview.textBoxes) drawTextBox(canvas, box)

            canvas.restoreToCount(checkpoint)
        }
    }

    private fun drawShapePreview(canvas: Canvas) {
        if (shapePreview.isEmpty()) return

        val transform = strokeSpaceToViewTransform(viewport)
        val checkpoint = canvas.save()
        canvas.concat(transform)

        for (stroke in shapePreview) renderer.draw(canvas, stroke, transform)

        canvas.restoreToCount(checkpoint)
    }

    private fun drawEraserFootprint(canvas: Canvas) {
        val footprint = eraserFootprint ?: return
        eraserFootprintPaint.color = colors.themeInk
        canvas.drawCircle(footprint.centerXPx, footprint.centerYPx, footprint.radiusPx, eraserFootprintPaint)
    }

    /** Drawn in view pixels, outside [drawCommittedStrokes]'s own transformed canvas, so the dash and line width stay constant regardless of [viewport]'s own zoom, the same technique [drawEraserFootprint] already uses. */
    private fun drawSelectionLassoPreview(canvas: Canvas) {
        if (selectionLassoPreview.size < 2) return

        selectionPaint.color = colors.themeInk
        val path = Path()
        val first = viewport.sheetToView(selectionLassoPreview.first())
        path.moveTo(first.x, first.y)
        for (point in selectionLassoPreview.drop(1)) {
            val viewPoint = viewport.sheetToView(point)
            path.lineTo(viewPoint.x, viewPoint.y)
        }
        canvas.drawPath(path, selectionPaint)
    }

    private fun drawSelectionBoxPreview(canvas: Canvas) {
        val rect = selectionBoxPreview ?: return
        drawSheetRectOutline(canvas, rect)
    }

    private fun drawSelectionOutline(canvas: Canvas) {
        val rect = selectionOutline ?: return
        drawSheetRectOutline(canvas, rect)
    }

    /** Drawn whenever a selection exists, in view pixels so their own size never scales with zoom, the same technique [drawSelectionLassoPreview] uses for the outline's own dash. */
    private fun drawSelectionHandles(canvas: Canvas) {
        val rect = selectionOutline ?: return
        selectionHandlePaint.color = colors.themeInk

        val viewRect = viewport.sheetToView(rect)
        val half = SELECTION_HANDLE_SIZE_DP * resources.displayMetrics.density / 2f
        val corners = listOf(
            ViewPoint(viewRect.left, viewRect.top),
            ViewPoint(viewRect.right, viewRect.top),
            ViewPoint(viewRect.left, viewRect.bottom),
            ViewPoint(viewRect.right, viewRect.bottom)
        )

        for (corner in corners) {
            canvas.drawRect(corner.x - half, corner.y - half, corner.x + half, corner.y + half, selectionHandlePaint)
        }
    }

    private fun drawSheetRectOutline(canvas: Canvas, rect: SheetRect) {
        selectionPaint.color = colors.themeInk
        val viewRect = viewport.sheetToView(rect)
        canvas.drawRect(viewRect.left, viewRect.top, viewRect.right, viewRect.bottom, selectionPaint)
    }

    private fun drawRules(canvas: Canvas, paperLeftPx: Float, paperRightPx: Float) {
        rulePaint.color = colors.rule

        val visibleTopSheetY = viewport.topLeft.y
        val visibleBottomSheetY = viewport.topLeft.y + viewport.viewHeightPx / viewport.scale

        val firstRuleIndex = floor(visibleTopSheetY / SheetRuleGrid.SPACING_SHEET_UNITS).toInt()
        val lastRuleIndex = ceil(visibleBottomSheetY / SheetRuleGrid.SPACING_SHEET_UNITS).toInt()

        for (index in firstRuleIndex..lastRuleIndex) {
            val ruleSheetY = index * SheetRuleGrid.SPACING_SHEET_UNITS
            if (ruleSheetY < 0f) continue

            val ruleViewY = viewport.sheetToView(SheetPoint(0f, ruleSheetY)).y
            canvas.drawLine(paperLeftPx, ruleViewY, paperRightPx, ruleViewY, rulePaint)
        }
    }

    /**
     * Draws every committed stroke and every live text box, in [layeredItemsForDraw]'s own shared
     * order, both under the one stroke-space-to-view transform: a [SheetItem.Text]'s own
     * [SheetTextBox.topLeft] is already in sheet units, and stroke space is exactly 1000 design pixels
     * per sheet unit — [StrokeSpace.UNITS_PER_SHEET_UNIT], the same scale [TextLayoutEngine] builds a
     * box's own layout at — so its text draws directly under this transform with no separate scale of
     * its own.
     */
    private fun drawCommittedItems(canvas: Canvas) {
        val visibleRect = SheetRect(
            left = 0f,
            top = viewport.topLeft.y,
            right = 1f,
            bottom = viewport.topLeft.y + viewport.viewHeightPx / viewport.scale
        )

        val hiddenIds = selectionDragPreview?.hiddenIds ?: emptySet()
        val strokeItems = builtStrokes.values.map { it.first }.filter { it.id !in hiddenIds }.map(SheetItem::Stroke)
        val textItems = textBoxes.filter { it.id !in hiddenIds }.map(SheetItem::Text)
        val visibleItems = layeredItemsForDraw((strokeItems + textItems).filter { it.bounds.intersects(visibleRect) })
        val transform = strokeSpaceToViewTransform(viewport)

        // The renderer takes the stroke-to-screen matrix only to pick its level of detail: it draws in
        // the canvas's own coordinates, so the canvas has to carry the same matrix.
        val checkpoint = canvas.save()
        canvas.concat(transform)

        for (item in visibleItems) {
            when (item) {
                is SheetItem.Stroke -> renderer.draw(canvas, builtStrokes.getValue(item.id).second, transform)
                is SheetItem.Text -> drawTextBox(canvas, item.textBox)
            }
        }

        canvas.restoreToCount(checkpoint)
    }

    /** [box]'s own cached [TextBoxLayout], built once per distinct box and reused until [textBoxes] drops or replaces it. */
    private fun layoutFor(box: SheetTextBox): TextBoxLayout {
        val cached = textLayoutCache[box.id]
        if (cached != null && cached.first == box) return cached.second

        val built = textLayoutEngine.layout(box.text, box.font, box.sizePt, box.style, box.widthSheetUnits, colorArgb = 0, alignment = box.alignment)
        textLayoutCache[box.id] = box to built
        return built
    }

    /**
     * Draws [box] at its own position in stroke-space (design-pixel) units, recolouring its cached
     * layout's own paint in place for every draw rather than rebuilding it: a colour is a per-frame
     * paint attribute here, never baked into the cached [android.text.StaticLayout]'s own geometry, so
     * a theme change repaints every live THEME-coloured box the same way [recolorThemeInkStrokes] does
     * for a stroke, at no extra cost.
     */
    private fun drawTextBox(canvas: Canvas, box: SheetTextBox) {
        val built = layoutFor(box)
        built.layout.paint.color = resolveTextColor(box.colorArgb, colors.themeInk)

        val leftDesignPx = StrokeSpace.sheetToStrokeSpace(box.topLeft.x)
        val topDesignPx = StrokeSpace.sheetToStrokeSpace(box.topLeft.y) + built.topOffsetDesignPx

        val checkpoint = canvas.save()
        canvas.translate(leftDesignPx, topDesignPx)
        built.layout.draw(canvas)
        canvas.restoreToCount(checkpoint)
    }
}
