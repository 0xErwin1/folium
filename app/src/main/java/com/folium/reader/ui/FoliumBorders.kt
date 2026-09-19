package com.folium.reader.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/**
 * Which edge [Modifier.foliumRule] paints.
 */
internal enum class FoliumRuleEdge { TOP, BOTTOM, START, END }

/**
 * Converts this [Dp] stroke to the whole number of physical pixels the design system draws it at.
 *
 * `Modifier.border` and `BorderStroke` round a stroke up with `ceil(width.toPx())`, so on any
 * density that is not a whole number a 1dp hairline and a 2dp ink rule both grow by the same one
 * pixel: at 1.0625, the density measured on the target tablet, that turns the 2px-versus-1px
 * contrast the design system is built on into 3px versus 2px. Rounding to the nearest pixel keeps
 * that contrast instead, and a floor of one pixel keeps a hairline from disappearing on a sub-1
 * density.
 */
internal fun Dp.foliumStrokePx(density: Density): Int =
    with(density) { toPx() }.roundToInt().coerceAtLeast(1)

/**
 * The rectangle a [strokePx]-wide stroke draws along, inset by half its width from [size].
 *
 * A stroke centred on the element's own edge has half its width clipped away by whatever sits
 * above it in the tree, so the rectangle it strokes has to sit inside the bounds by half the
 * stroke width on every side. When [size] is smaller than twice [strokePx] that inset would turn
 * negative; centring the degenerate zero-size rectangle instead keeps the stroke inside the
 * element without producing a negative size.
 */
internal fun foliumBorderGeometry(size: Size, strokePx: Float): Rect {
    val insetSize = Size(
        width = (size.width - strokePx).coerceAtLeast(0f),
        height = (size.height - strokePx).coerceAtLeast(0f)
    )

    val insetOffset = Offset(
        x = (size.width - insetSize.width) / 2f,
        y = (size.height - insetSize.height) / 2f
    )

    return Rect(insetOffset, insetSize)
}

/**
 * A rectangular border drawn at exactly [width] rounded to the nearest pixel, inside the element's
 * own bounds so the border never changes how much space the element occupies.
 *
 * Every shape this design system uses is a plain rectangle (see `FoliumShapes`), so there is no
 * corner radius to carry here. The border draws after the element's own content, the same order
 * `Modifier.border` uses, so it always sits above a background painted underneath it instead of
 * being covered by one.
 */
internal fun Modifier.foliumBorder(width: Dp, color: Color): Modifier =
    drawWithContent {
        drawContent()

        val strokePx = width.foliumStrokePx(this).toFloat()
        val rect = foliumBorderGeometry(size, strokePx)

        drawRect(
            color = color,
            topLeft = rect.topLeft,
            size = rect.size,
            style = Stroke(width = strokePx)
        )
    }

/**
 * A single rule along one [edge] of the element, drawn at exactly [width] rounded to the nearest
 * pixel and aligned to the pixel grid.
 *
 * `drawLine` centres its stroke on the coordinates it is given, so a rule drawn at the literal
 * edge (y = 0, for a top rule) has half its width clipped away and the other half anti-aliased
 * across two pixel rows when the stroke is not a whole pixel wide. Moving the centreline inward by
 * half the rounded stroke width keeps the whole rule inside the element and confines it to exactly
 * [FoliumRuleEdge]'s number of pixel rows or columns.
 */
internal fun Modifier.foliumRule(edge: FoliumRuleEdge, width: Dp, color: Color): Modifier =
    drawWithContent {
        drawContent()

        val strokePx = width.foliumStrokePx(this).toFloat()
        val half = strokePx / 2f

        val (start, end) = when (edge) {
            FoliumRuleEdge.TOP -> Offset(0f, half) to Offset(size.width, half)
            FoliumRuleEdge.BOTTOM -> Offset(0f, size.height - half) to Offset(size.width, size.height - half)
            FoliumRuleEdge.START -> Offset(half, 0f) to Offset(half, size.height)
            FoliumRuleEdge.END -> Offset(size.width - half, 0f) to Offset(size.width - half, size.height)
        }

        drawLine(color = color, start = start, end = end, strokeWidth = strokePx)
    }
