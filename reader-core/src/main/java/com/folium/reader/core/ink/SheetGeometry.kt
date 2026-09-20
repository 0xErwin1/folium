package com.folium.reader.core.ink

import kotlin.math.max
import kotlin.math.min

/**
 * A point in sheet space. One "sheet unit" is the reference column width, so `1.0` of `x` is one
 * such width regardless of what that width measures in pixels or points; `y` reuses the exact same
 * unit. Both axes are unbounded and may be negative: this type alone does not know whether it sits
 * on a fixed-width column, an infinite whiteboard, or anything in between, that constraint is
 * [SheetExtent]'s job, applied on top of this otherwise unconstrained coordinate space.
 *
 * A stroke drawn on a book page reuses this exact model, with the page's own shape bounding where
 * ink is allowed to land rather than the sheet being endless.
 */
data class SheetPoint(val x: Float, val y: Float)

/** An axis-aligned rectangle in sheet space, in the same unit as [SheetPoint]. */
data class SheetRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(right >= left) { "right must be >= left, was left=$left right=$right" }
        require(bottom >= top) { "bottom must be >= top, was top=$top bottom=$bottom" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun intersects(other: SheetRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun contains(point: SheetPoint): Boolean =
        point.x in left..right && point.y in top..bottom

    fun union(other: SheetRect): SheetRect = SheetRect(
        left = min(left, other.left),
        top = min(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom)
    )

    /** Grows every side by [by], which may be negative to shrink, clamped so it never inverts. */
    fun inflate(by: Float): SheetRect {
        val halfWidth = max(width / 2f + by, 0f)
        val halfHeight = max(height / 2f + by, 0f)
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        return SheetRect(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
    }
}
