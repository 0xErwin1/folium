package com.folium.reader.ink

import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetRect
import com.folium.reader.core.ink.strokesIntersecting
import com.folium.reader.ui.FoliumGrid
import kotlin.math.roundToInt

/** One sheet-space point mapped onto a thumbnail bitmap's own pixel grid. */
internal data class ThumbnailPixel(val x: Float, val y: Float)

/**
 * Where a sheet's thumbnail comes from, kept free of `android.*` so the mapping it describes is
 * host-testable on its own: a rectangle of sheet space, the pixel grid it lands on, and which
 * strokes fall inside it. [SheetThumbnailRenderer] is the only caller that turns this into an actual
 * bitmap.
 *
 * The region is exactly one cover tall — [FoliumGrid.COVER_ASPECT] is width over height, so a region
 * the full sheet width wide and `1 / COVER_ASPECT` sheet-units tall shares the cover's own
 * proportion, and mapping it onto a bitmap [WIDTH_PX] wide needs one scale factor for both axes
 * rather than a separate one per axis.
 */
internal object SheetThumbnailGeometry {
    /** The thumbnail's own fixed width; every sheet's thumbnail is rendered at this size. */
    const val WIDTH_PX = 360

    private const val MIN_STROKE_WIDTH_PX = 1f

    /** The bitmap height that keeps [WIDTH_PX] at the cover's own proportion. */
    fun heightPx(widthPx: Int = WIDTH_PX): Int = (widthPx / FoliumGrid.COVER_ASPECT).roundToInt()

    /** The sheet-space rectangle a thumbnail shows: the full page width, one cover's height tall. */
    fun region(): SheetRect = SheetRect(left = 0f, top = 0f, right = 1f, bottom = 1f / FoliumGrid.COVER_ASPECT)

    /** [point] in sheet space, scaled onto a [widthPx]-wide thumbnail bitmap. */
    fun toPixel(point: SheetPoint, widthPx: Int = WIDTH_PX): ThumbnailPixel =
        ThumbnailPixel(point.x * widthPx, point.y * widthPx)

    /** A stroke's own [widthSheetUnits] in thumbnail pixels, never thinner than one pixel. */
    fun strokeWidthPx(widthSheetUnits: Float, widthPx: Int = WIDTH_PX): Float =
        maxOf(MIN_STROKE_WIDTH_PX, widthSheetUnits * widthPx)

    /** Every stroke of [strokes] whose bounds reach into [region], in draw order. */
    fun strokesForThumbnail(strokes: List<InkStroke>): List<InkStroke> =
        strokesIntersecting(strokes, region()).sortedBy { it.sequence }
}
