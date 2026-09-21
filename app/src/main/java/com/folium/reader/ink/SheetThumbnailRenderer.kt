package com.folium.reader.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.InkTool
import com.folium.reader.core.ink.SheetItem
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetTextBox

/**
 * Renders one sheet's thumbnail directly on a plain [Canvas]: the paper, then every item
 * [SheetThumbnailGeometry] keeps within the thumbnail's region, each stroke drawn as its own [Path]
 * polyline rather than through `androidx.ink`'s own renderer and each text box through
 * [TextLayoutEngine]. A closed sheet has nothing left in progress to justify that renderer's cost; a
 * flat stroke-and-fill draw reproduces what a dry stroke already looks like.
 *
 * Deliberately outside the app's theme, like [com.folium.reader.ui.FoliumPaper]: a thumbnail is a
 * book cover, not chrome, so it stays light paper with dark ink regardless of the active appearance
 * rather than turning illegible — pale ink on pale paper — the moment a sheet closes under a dark or
 * e-ink theme. A stroke or a text box stored under its own THEME choice therefore resolves to
 * [THUMBNAIL_INK_COLOR], the fixed dark ink this cover always reads against, never the live theme ink
 * [SheetPane] draws that same item with.
 */
internal class SheetThumbnailRenderer(context: Context) {

    private val textLayoutEngine = TextLayoutEngine(context)

    /** A fresh `ARGB_8888` bitmap, [widthPx] wide, showing every item of [items] in draw order. */
    fun render(items: List<SheetItem>, widthPx: Int = SheetThumbnailGeometry.WIDTH_PX): Bitmap {
        val heightPx = SheetThumbnailGeometry.heightPx(widthPx)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAPER_COLOR)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        for (item in SheetThumbnailGeometry.itemsForThumbnail(items)) {
            when (item) {
                is SheetItem.Stroke -> drawStrokeItem(canvas, item.stroke, widthPx, paint)
                is SheetItem.Text -> drawTextBox(canvas, item.textBox, widthPx)
            }
        }

        return bitmap
    }

    private fun drawStrokeItem(canvas: Canvas, stroke: InkStroke, widthPx: Int, paint: Paint) {
        val resolvedColorArgb = resolveStrokeColor(stroke.colorArgb, THUMBNAIL_INK_COLOR, stroke.tool)
        paint.color = if (stroke.tool == InkTool.HIGHLIGHTER) highlighterBrushColor(resolvedColorArgb) else resolvedColorArgb
        paint.strokeWidth = SheetThumbnailGeometry.strokeWidthPx(stroke.widthSheetUnits, widthPx)
        drawStroke(canvas, stroke, widthPx, paint)
    }

    /**
     * Draws [box] at [widthPx]-thumbnail scale: [widthPx] design pixels per sheet unit, rather than
     * [InkCommittedStrokesView]'s own [StrokeSpace.UNITS_PER_SHEET_UNIT], so the layout is rebuilt at
     * that scale rather than reused from the live surface's own cache.
     */
    private fun drawTextBox(canvas: Canvas, box: SheetTextBox, widthPx: Int) {
        val resolvedColorArgb = resolveTextColor(box.colorArgb, THUMBNAIL_INK_COLOR)
        val scale = widthPx / StrokeSpace.UNITS_PER_SHEET_UNIT
        val built = textLayoutEngine.layout(box.text, box.style, box.widthSheetUnits, resolvedColorArgb)

        val checkpoint = canvas.save()
        canvas.translate(box.topLeft.x * widthPx, box.topLeft.y * widthPx + built.topOffsetDesignPx * scale)
        canvas.scale(scale, scale)
        built.layout.draw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    /** A single-sample stroke has no path to draw, so it is drawn as the dot its round cap makes of a point. */
    private fun drawStroke(canvas: Canvas, stroke: InkStroke, widthPx: Int, paint: Paint) {
        val samples = stroke.samples
        if (samples.size == 1) {
            val point = SheetThumbnailGeometry.toPixel(SheetPoint(samples[0].x, samples[0].y), widthPx)
            canvas.drawPoint(point.x, point.y, paint)
            return
        }

        val path = Path()
        samples.forEachIndexed { index, sample ->
            val point = SheetThumbnailGeometry.toPixel(SheetPoint(sample.x, sample.y), widthPx)
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        canvas.drawPath(path, paint)
    }

    private companion object {
        const val PAPER_COLOR = Color.WHITE
        const val THUMBNAIL_INK_COLOR = 0xFF141414.toInt()
    }
}
