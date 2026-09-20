package com.folium.reader.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.SheetPoint

/**
 * Renders one sheet's thumbnail directly on a plain [Canvas]: the paper, then every stroke
 * [SheetThumbnailGeometry] keeps within the thumbnail's region, each drawn as its own [Path]
 * polyline rather than through `androidx.ink`'s own renderer. A closed sheet has nothing left in
 * progress to justify that renderer's cost; a flat stroke-and-fill draw reproduces what a dry stroke
 * already looks like.
 */
internal object SheetThumbnailRenderer {

    private const val PAPER_COLOR = Color.WHITE

    /** A fresh `ARGB_8888` bitmap, [widthPx] wide, showing every stroke of [strokes] in draw order. */
    fun render(strokes: List<InkStroke>, widthPx: Int = SheetThumbnailGeometry.WIDTH_PX): Bitmap {
        val heightPx = SheetThumbnailGeometry.heightPx(widthPx)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAPER_COLOR)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        for (stroke in SheetThumbnailGeometry.strokesForThumbnail(strokes)) {
            paint.color = stroke.colorArgb
            paint.strokeWidth = SheetThumbnailGeometry.strokeWidthPx(stroke.widthSheetUnits, widthPx)
            drawStroke(canvas, stroke, widthPx, paint)
        }

        return bitmap
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
}
