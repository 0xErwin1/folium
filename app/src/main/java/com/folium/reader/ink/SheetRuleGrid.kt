package com.folium.reader.ink

/**
 * The sheet's own writing grid: 32 design pixels between rules, the T-Hoja artboard's own line
 * height for both a ruled sheet's drawn rules and a text box's own lines (`canvas.json`,
 * `nota-t-hoja`: "Los renglones son la guía de escritura y todo el texto cae sobre ellos, a 32."). A
 * design pixel is `1 / StrokeSpace.UNITS_PER_SHEET_UNIT` of a sheet unit, so [SPACING_SHEET_UNITS] is
 * shared by [InkCommittedStrokesView]'s own ruled background and [TextLayoutEngine]'s own line
 * placement rather than each keeping its own copy of the same constant.
 */
internal object SheetRuleGrid {
    const val LINE_HEIGHT_DESIGN_PX: Float = 32f
    const val SPACING_SHEET_UNITS: Float = LINE_HEIGHT_DESIGN_PX / StrokeSpace.UNITS_PER_SHEET_UNIT

    /** [y] moved down to the nearest rule at or above it, the grid a new text box's own top snaps to. */
    fun snappedDown(y: Float): Float = kotlin.math.floor(y / SPACING_SHEET_UNITS) * SPACING_SHEET_UNITS

    /**
     * The smallest multiple of [LINE_HEIGHT_DESIGN_PX] that is at least `1.25 *` [textSizeDesignPx],
     * so every baseline of a text box set at any size still lands on a rule: 16px text keeps the
     * single-rule 32px line height the design draws (`canvas.json`, `nota-t-hoja`), while a larger
     * size steps up to the next whole rule rather than crowding its own lines together.
     */
    fun lineHeightForTextSize(textSizeDesignPx: Float): Float {
        val minimumLineHeight = textSizeDesignPx * 1.25f
        val ruleCount = kotlin.math.ceil(minimumLineHeight / LINE_HEIGHT_DESIGN_PX)
        return ruleCount * LINE_HEIGHT_DESIGN_PX
    }
}
