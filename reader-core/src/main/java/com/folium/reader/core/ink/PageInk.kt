package com.folium.reader.core.ink

private const val MM_PER_INCH = 25.4f
private const val POINTS_PER_INCH = 72f

/**
 * Where ink may land on one book page, in page-ink units: the same isotropic [SheetPoint] space a
 * sheet uses, with one unit equal to the page's own width. `x` runs from 0 to 1 across the page and
 * `y` from 0 to [heightUnits] down it, so a circle drawn on the page stays round whatever its shape.
 *
 * Ink is stored in these units rather than in pixels or points, so it stays bound to the page
 * rectangle across zoom, rotation and re-rendering at any resolution.
 *
 * [aspect] is the page's width divided by its height, as the page is displayed.
 */
data class PageInkExtent(val aspect: Float) {
    init {
        require(aspect.isFinite() && aspect > 0f) { "aspect must be finite and positive, was $aspect" }
    }

    /** The page's height in page-ink units: `1 / aspect`. */
    val heightUnits: Float get() = 1f / aspect

    /** The whole page, in page-ink units. */
    val bounds: SheetRect get() = SheetRect(0f, 0f, 1f, heightUnits)

    /** [point] moved onto the nearest spot of the page; a point already on it is returned unchanged. */
    fun clamp(point: SheetPoint): SheetPoint =
        SheetPoint(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, heightUnits))

    /** Whether [rect] lies fully on the page; touching an edge counts as inside. */
    fun allows(rect: SheetRect): Boolean =
        rect.left >= 0f && rect.top >= 0f && rect.right <= 1f && rect.bottom <= heightUnits

    /** Converts [point] from page-ink units to page fractions, where both axes run from 0 to 1. */
    fun toPageSpace(point: SheetPoint): SheetPoint = SheetPoint(point.x, point.y * aspect)

    /** Converts [point] from page fractions, where both axes run from 0 to 1, to page-ink units. */
    fun fromPageSpace(point: SheetPoint): SheetPoint = SheetPoint(point.x, point.y / aspect)

    companion object {
        /** The extent of a page [widthPt] wide and [heightPt] tall, as displayed. */
        fun of(widthPt: Float, heightPt: Float): PageInkExtent {
            require(widthPt > 0f && heightPt > 0f) { "page size must be positive, was ${widthPt}x$heightPt" }

            return PageInkExtent(widthPt / heightPt)
        }

        /**
         * The printed width, in millimetres, of a page [widthPt] PDF points wide: an A4 page's 595pt is
         * about 210mm. It plays the role for a page that the fixed 210mm nominal width plays for a sheet,
         * so a pen width chosen in millimetres draws the same physical width on the printed page.
         */
        fun nominalWidthMm(widthPt: Float): Float = widthPt * MM_PER_INCH / POINTS_PER_INCH

        /** A length of [mm] millimetres on a page [widthPt] points wide, in page-ink units. */
        fun mmToUnits(mm: Float, widthPt: Float): Float = mm / nominalWidthMm(widthPt)

        /** The inverse of [mmToUnits]. */
        fun unitsToMm(units: Float, widthPt: Float): Float = units * nominalWidthMm(widthPt)
    }
}
