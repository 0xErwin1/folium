package com.folium.reader.ink

import android.graphics.Path
import android.graphics.Rect
import com.folium.reader.core.ink.PageInkExtent
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetRect
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What an [InkDrawingSurface] draws on: a [Sheet] it owns outright — paper, rules, its own pan and
 * zoom — or a book [Page] whose PDF is rendered underneath by the reader, which owns the page's zoom
 * and pan and pins the surface's mapping through [InkDrawingSurface.setPageFrame].
 */
sealed interface InkSurfaceMode {
    /** A length of [mm] millimetres on the printed layer, in its ink units. */
    fun mmToUnits(mm: Float): Float

    /**
     * How many design pixels — `1 / StrokeSpace.UNITS_PER_SHEET_UNIT` of a unit — one point of text
     * size spans here. Every text path on this layer, the editor, the committed view and a page's
     * cached ink, sizes text through this one value so a box never changes size between them.
     */
    val textDesignPxPerPoint: Float

    /** A sheet: one unit is the sheet's nominal 210mm width, and one point of text is one design pixel. */
    data object Sheet : InkSurfaceMode {
        override fun mmToUnits(mm: Float): Float = mmToSheetUnits(mm)

        override val textDesignPxPerPoint: Float = 1f
    }

    /**
     * A book page [pageWidthPt] by [pageHeightPt] PDF points, as displayed: one unit is the page's
     * own width, and a millimetre is measured against the page's printed width.
     */
    data class Page(val pageWidthPt: Float, val pageHeightPt: Float) : InkSurfaceMode {
        val extent: PageInkExtent = PageInkExtent.of(pageWidthPt, pageHeightPt)

        override fun mmToUnits(mm: Float): Float = PageInkExtent.mmToUnits(mm, pageWidthPt)

        /** One point of text is a printed point, `25.4 / 72` mm, measured against the page like a pen width. */
        override val textDesignPxPerPoint: Float
            get() = StrokeSpace.sheetToStrokeSpace(mmToUnits(MM_PER_POINT))
    }
}

private const val MM_PER_POINT = 25.4f / 72f

/** Whether a touch going down at [point] starts ink on a page drawn at [pageRectPx]; its edges count as on the page. */
fun pageInkAcceptsDown(point: ViewPoint, pageRectPx: ViewRect): Boolean =
    point.x >= pageRectPx.left && point.x <= pageRectPx.right &&
        point.y >= pageRectPx.top && point.y <= pageRectPx.bottom

/**
 * Whether a selection move or resize may go from [previous] to [next] on a page of [extent]: always
 * when [next] lies on the page, and otherwise only when it hangs off the page no further than
 * [previous] did, so ink that already overhangs an edge (a thick stroke drawn against it) can still be
 * dragged back rather than freezing in place.
 */
fun pageInkAllowsEdit(extent: PageInkExtent, previous: SheetRect, next: SheetRect): Boolean =
    extent.allows(next) || overflowOf(extent, next) <= overflowOf(extent, previous)

private fun overflowOf(extent: PageInkExtent, rect: SheetRect): Float =
    max(0f, -rect.left) + max(0f, -rect.top) + max(0f, rect.right - 1f) + max(0f, rect.bottom - extent.heightUnits)

/**
 * Moves [session] toward [point] as far as [pageInkAllowsEdit] lets it: the whole move when allowed,
 * otherwise only its horizontal or only its vertical part, so a drag pressed against one edge still
 * slides along it; with neither allowed the session stays where it was.
 */
internal fun SelectionEditSession.moveWithinPage(point: SheetPoint, extent: PageInkExtent) {
    val previousPoint = currentSheetPoint
    val previousBounds = previewBounds()
    val candidates = listOf(point, SheetPoint(point.x, previousPoint.y), SheetPoint(previousPoint.x, point.y))

    for (candidate in candidates) {
        onMove(candidate)
        if (pageInkAllowsEdit(extent, previousBounds, previewBounds())) return
    }

    onMove(previousPoint)
}

/** The area of a [widthPx] by [heightPx] view outside [pageRectPx], where in-progress ink is masked out. */
internal fun outsidePageMask(widthPx: Float, heightPx: Float, pageRectPx: ViewRect): Path = Path().apply {
    fillType = Path.FillType.EVEN_ODD
    addRect(0f, 0f, widthPx, heightPx, Path.Direction.CW)
    addRect(pageRectPx.left, pageRectPx.top, pageRectPx.right, pageRectPx.bottom, Path.Direction.CW)
}

/**
 * The left and right edge strips, [stripWidthPx] wide, of a [widthPx] by [heightPx] view that the
 * system's back gesture must leave to the pen, spanning the part of [pageRectPx] on screen. The
 * platform honours at most 200dp of height per edge.
 */
internal fun edgeGestureExclusionRects(widthPx: Int, heightPx: Int, pageRectPx: ViewRect, stripWidthPx: Int): List<Rect> {
    val top = pageRectPx.top.roundToInt().coerceIn(0, heightPx)
    val bottom = pageRectPx.bottom.roundToInt().coerceIn(0, heightPx)
    if (bottom <= top || widthPx <= 0) return emptyList()

    val strip = stripWidthPx.coerceIn(0, widthPx)
    return listOf(Rect(0, top, strip, bottom), Rect(widthPx - strip, top, widthPx, bottom))
}
