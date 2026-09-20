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
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetRect
import com.folium.reader.core.ink.SheetTemplate
import com.folium.reader.core.ink.StrokeId
import com.folium.reader.core.ink.strokesIntersecting
import kotlin.math.ceil
import kotlin.math.floor

private const val RULE_SPACING_SHEET_UNITS: Float = 32f / StrokeSpace.UNITS_PER_SHEET_UNIT

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
 */
class InkCommittedStrokesView(context: Context) : View(context) {

    private val renderer = CanvasStrokeRenderer.create()
    private val builtStrokes = LinkedHashMap<StrokeId, Pair<InkStroke, Stroke>>()

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
     * A live move or resize drag against the current selection: [hiddenIds] are the originals'
     * [InkStroke.id]s, kept out of [drawCommittedStrokes] for as long as this is set, and [strokes]
     * are their own already-built meshes, drawn instead through [transform] on top of the ordinary
     * stroke-to-view transform — a cheap per-frame matrix change rather than a mesh rebuild. Stays set
     * after the drag lifts, frozen at its own final [transform], until the moved or resized strokes'
     * own new meshes have finished building off the UI thread, so the drag's own result never shows a
     * gap between the drag's last frame and the first frame of the real, committed strokes.
     */
    data class SelectionDragPreview(val hiddenIds: Set<StrokeId>, val strokes: List<Stroke>, val transform: Matrix)

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
        fieldPaint.color = colors.field
        canvas.drawColor(colors.field)

        val paperLeftPx = viewport.sheetToView(SheetPoint(0f, viewport.topLeft.y)).x
        val paperRightPx = viewport.sheetToView(SheetPoint(1f, viewport.topLeft.y)).x
        paperPaint.color = colors.paper
        canvas.drawRect(paperLeftPx, 0f, paperRightPx, height.toFloat(), paperPaint)

        if (template == SheetTemplate.RULED) drawRules(canvas, paperLeftPx, paperRightPx)

        drawCommittedStrokes(canvas)
        drawSelectionDragPreview(canvas)
        drawShapePreview(canvas)
        drawEraserFootprint(canvas)
        drawSelectionLassoPreview(canvas)
        drawSelectionBoxPreview(canvas)
        drawSelectionOutline(canvas)
        drawSelectionHandles(canvas)
    }

    /**
     * [SelectionDragPreview.strokes], transformed by [SelectionDragPreview.transform] on top of the
     * ordinary stroke-to-view transform: [renderer] still receives the ordinary transform alone for
     * its own level-of-detail decision, exactly as [drawCommittedStrokes] and [drawShapePreview] pass
     * it, since [SelectionDragPreview.transform] only ever repositions or rescales what is already
     * built, and never changes how finely it should have been tessellated.
     */
    private fun drawSelectionDragPreview(canvas: Canvas) {
        val preview = selectionDragPreview ?: return
        if (preview.strokes.isEmpty()) return

        val transform = strokeSpaceToViewTransform(viewport)
        val checkpoint = canvas.save()
        canvas.concat(preview.transform)
        canvas.concat(transform)

        for (stroke in preview.strokes) renderer.draw(canvas, stroke, transform)

        canvas.restoreToCount(checkpoint)
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

        val firstRuleIndex = floor(visibleTopSheetY / RULE_SPACING_SHEET_UNITS).toInt()
        val lastRuleIndex = ceil(visibleBottomSheetY / RULE_SPACING_SHEET_UNITS).toInt()

        for (index in firstRuleIndex..lastRuleIndex) {
            val ruleSheetY = index * RULE_SPACING_SHEET_UNITS
            if (ruleSheetY < 0f) continue

            val ruleViewY = viewport.sheetToView(SheetPoint(0f, ruleSheetY)).y
            canvas.drawLine(paperLeftPx, ruleViewY, paperRightPx, ruleViewY, rulePaint)
        }
    }

    private fun drawCommittedStrokes(canvas: Canvas) {
        val visibleRect = SheetRect(
            left = 0f,
            top = viewport.topLeft.y,
            right = 1f,
            bottom = viewport.topLeft.y + viewport.viewHeightPx / viewport.scale
        )

        val hiddenIds = selectionDragPreview?.hiddenIds ?: emptySet()
        val models = builtStrokes.values.map { it.first }.filter { it.id !in hiddenIds }
        val visibleModels = layeredForDraw(strokesIntersecting(models, visibleRect))
        val transform = strokeSpaceToViewTransform(viewport)

        // The renderer takes the stroke-to-screen matrix only to pick its level of detail: it draws in
        // the canvas's own coordinates, so the canvas has to carry the same matrix.
        val checkpoint = canvas.save()
        canvas.concat(transform)

        for (model in visibleModels) {
            val builtStroke = builtStrokes.getValue(model.id).second
            renderer.draw(canvas, builtStroke, transform)
        }

        canvas.restoreToCount(checkpoint)
    }
}
