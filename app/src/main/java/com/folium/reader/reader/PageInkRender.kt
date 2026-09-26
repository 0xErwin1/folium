package com.folium.reader.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.InkTool
import com.folium.reader.core.ink.SheetItem
import com.folium.reader.core.ink.SheetTextBox
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.ink.InkSurfaceMode
import com.folium.reader.ink.StrokeSpace
import com.folium.reader.ink.TextBoxLayout
import com.folium.reader.ink.TextLayoutEngine
import com.folium.reader.ink.highlighterBrushColor
import com.folium.reader.ink.layeredItemsForDraw
import com.folium.reader.ink.resolveStrokeColor
import com.folium.reader.ink.resolveTextColor
import com.folium.reader.ink.toInkStroke

/**
 * The most strokes a page may have and still be drawn through `androidx.ink` meshes. Building one
 * mesh costs about 4ms on the target tablet (see [com.folium.reader.ink.InkMeshBuilder]), so 1000
 * strokes is about four seconds of background work before the page's ink appears; a page past it
 * draws each stroke as a flat polyline instead, the way a sheet's thumbnail does, which builds almost
 * instantly and loses only pressure-varying width.
 */
internal const val PAGE_INK_MESH_STROKE_LIMIT = 1000

/**
 * The ink colour a stroke or text box stored under the THEME choice draws in on a book page. A PDF
 * page renders as its own paper whatever the app's appearance, so, like
 * [com.folium.reader.ink.SheetThumbnailRenderer], the page's ink stays a fixed dark ink rather than
 * following the app theme into pale ink on a white page.
 */
const val PAGE_INK_THEME_INK_ARGB: Int = 0xFF141414.toInt()

/** The ink layer of the page [info] describes, as displayed: the live surface and the cached ink both size it through this. */
internal fun pageInkMode(info: PageInfo): InkSurfaceMode.Page = InkSurfaceMode.Page(info.width, info.height)

/** The text scale a page's cached ink builds its text at: the same [InkSurfaceMode.textDesignPxPerPoint] its live surface uses. */
internal fun pageInkTextDesignPxPerPoint(info: PageInfo): Float = pageInkMode(info).textDesignPxPerPoint

/** One thing drawn for a page's ink, already built, in stroke-space coordinates. */
internal sealed interface PageInkDrawable {
    class Mesh(val stroke: Stroke) : PageInkDrawable

    class Polyline(val path: Path, val colorArgb: Int, val widthStrokeUnits: Float) : PageInkDrawable

    class Text(val layout: TextBoxLayout, val leftDesignPx: Float, val topDesignPx: Float) : PageInkDrawable
}

/** One page's committed ink, built once and ready to draw in shared draw order; see [drawPageInk]. */
class PageInkRender internal constructor(internal val drawables: List<PageInkDrawable>) {
    val isEmpty: Boolean get() = drawables.isEmpty()
}

/**
 * Builds a page's [PageInkRender] off the main thread: strokes through [toInkStroke], the same
 * builder a sheet's committed strokes go through, or as polylines past [meshStrokeLimit]; text boxes
 * through [TextLayoutEngine] at the page's own text scale, coloured once here since [themeInkArgb]
 * never changes. Runs on one thread: the engines kept per text scale are not shared across threads.
 */
internal class PageInkRenderBuilder(
    context: Context,
    private val themeInkArgb: Int = PAGE_INK_THEME_INK_ARGB,
    private val meshStrokeLimit: Int = PAGE_INK_MESH_STROKE_LIMIT
) {
    private val appContext = context.applicationContext
    private val textLayoutEngines = HashMap<Float, TextLayoutEngine>()

    /** [items] drawn on the page [info] describes. */
    fun build(items: List<SheetItem>, info: PageInfo): PageInkRender {
        val meshes = items.count { it is SheetItem.Stroke } <= meshStrokeLimit
        val designPxPerPoint = pageInkTextDesignPxPerPoint(info)
        val textLayoutEngine = textLayoutEngines.getOrPut(designPxPerPoint) { TextLayoutEngine(appContext, designPxPerPoint) }

        val drawables = layeredItemsForDraw(items).map { item ->
            when (item) {
                is SheetItem.Stroke -> if (meshes) PageInkDrawable.Mesh(toInkStroke(item.stroke, themeInkArgb)) else polylineOf(item.stroke)
                is SheetItem.Text -> textOf(item.textBox, textLayoutEngine)
            }
        }

        return PageInkRender(drawables)
    }

    private fun polylineOf(stroke: InkStroke): PageInkDrawable.Polyline {
        val resolved = resolveStrokeColor(stroke.colorArgb, themeInkArgb, stroke.tool)
        val colorArgb = if (stroke.tool == InkTool.HIGHLIGHTER) highlighterBrushColor(resolved) else resolved

        val path = Path()
        stroke.samples.forEachIndexed { index, sample ->
            val x = StrokeSpace.sheetToStrokeSpace(sample.x)
            val y = StrokeSpace.sheetToStrokeSpace(sample.y)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        if (stroke.samples.size == 1) {
            val only = stroke.samples.single()
            path.lineTo(StrokeSpace.sheetToStrokeSpace(only.x), StrokeSpace.sheetToStrokeSpace(only.y))
        }

        return PageInkDrawable.Polyline(path, colorArgb, StrokeSpace.sheetToStrokeSpace(stroke.widthSheetUnits))
    }

    private fun textOf(box: SheetTextBox, textLayoutEngine: TextLayoutEngine): PageInkDrawable.Text {
        val colorArgb = resolveTextColor(box.colorArgb, themeInkArgb)
        val layout = textLayoutEngine.layout(box.text, box.font, box.sizePt, box.style, box.widthSheetUnits, colorArgb, box.alignment)

        return PageInkDrawable.Text(
            layout = layout,
            leftDesignPx = StrokeSpace.sheetToStrokeSpace(box.topLeft.x),
            topDesignPx = StrokeSpace.sheetToStrokeSpace(box.topLeft.y) + layout.topOffsetDesignPx
        )
    }
}

/** A reusable flat-stroke paint for [PageInkDrawable.Polyline]. */
internal fun pageInkPolylinePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

/**
 * Draws [render] on [canvas] as vectors, placed by [frame] and clipped to the page, the same way
 * [com.folium.reader.ink.InkCommittedStrokesView] draws a sheet's committed items: the canvas carries
 * the stroke-to-view matrix, and [renderer] receives that same matrix only to pick its level of detail.
 */
internal fun drawPageInk(canvas: Canvas, render: PageInkRender, frame: PageInkDrawFrame, renderer: CanvasStrokeRenderer, polylinePaint: Paint) {
    val transform = Matrix().apply {
        setScale(frame.scale, frame.scale)
        postTranslate(frame.translateX, frame.translateY)
    }

    val checkpoint = canvas.save()
    canvas.clipRect(frame.clip.left, frame.clip.top, frame.clip.left + frame.clip.width, frame.clip.top + frame.clip.height)
    canvas.concat(transform)

    for (drawable in render.drawables) {
        when (drawable) {
            is PageInkDrawable.Mesh -> renderer.draw(canvas, drawable.stroke, transform)

            is PageInkDrawable.Polyline -> {
                polylinePaint.color = drawable.colorArgb
                polylinePaint.strokeWidth = drawable.widthStrokeUnits
                canvas.drawPath(drawable.path, polylinePaint)
            }

            is PageInkDrawable.Text -> {
                val textCheckpoint = canvas.save()
                canvas.translate(drawable.leftDesignPx, drawable.topDesignPx)
                drawable.layout.layout.draw(canvas)
                canvas.restoreToCount(textCheckpoint)
            }
        }
    }

    canvas.restoreToCount(checkpoint)
}
