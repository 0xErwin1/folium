package com.folium.reader.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetRect
import com.folium.reader.core.ink.SheetTemplate
import com.folium.reader.core.ink.StrokeId
import com.folium.reader.core.ink.strokesIntersecting
import kotlin.math.ceil
import kotlin.math.floor

private const val RULE_SPACING_SHEET_UNITS: Float = 32f / StrokeSpace.UNITS_PER_SHEET_UNIT

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

    var colors: InkSurfaceColors = InkSurfaceColors(paper = 0xFFFFFFFF.toInt(), field = 0xFFE0E0E0.toInt(), rule = 0xFFB0B0B0.toInt())
        set(value) {
            field = value
            invalidate()
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

        val models = builtStrokes.values.map { it.first }
        val visibleModels = strokesIntersecting(models, visibleRect).sortedBy { it.sequence }
        val transform = strokeSpaceToViewTransform(viewport)

        for (model in visibleModels) {
            val builtStroke = builtStrokes.getValue(model.id).second
            renderer.draw(canvas, builtStroke, transform)
        }
    }
}
