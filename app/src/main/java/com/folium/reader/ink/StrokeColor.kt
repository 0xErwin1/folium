package com.folium.reader.ink

import com.folium.reader.core.ink.InkTool

/**
 * The [com.folium.reader.core.ink.InkStroke.colorArgb] a stroke drawn with the pen panel's THEME
 * choice is stored under. Opaque black rather than a resolved theme colour, since no themed ink in
 * any of [com.folium.reader.ui.FoliumTheme]'s five appearances is ever exactly this value: storing the
 * sentinel instead of the ink a stroke happened to be drawn under lets a later theme switch, or the
 * same sheet reopened under a different appearance, repaint every such stroke correctly without a
 * format change or a mesh rebuild.
 */
const val STROKE_THEME_INK_SENTINEL_ARGB: Int = 0xFF000000.toInt()

/**
 * Whether [storedArgb] stands for "the theme's ink" rather than for a colour of its own: the sentinel
 * itself, or any opaque achromatic colour. Sheets written before the sentinel existed stored the ink
 * of whichever appearance they were drawn under, a near-black in the light appearances and a
 * near-white in the dark ones, and those strokes must follow the theme exactly like new ones. No
 * pen colour on offer is a grey, so an achromatic stored colour can only have come from the ink.
 */
fun isThemeInk(storedArgb: Int): Boolean {
    val alpha = storedArgb ushr 24
    if (alpha != 0xFF) return false

    val red = (storedArgb shr 16) and 0xFF
    val green = (storedArgb shr 8) and 0xFF
    val blue = storedArgb and 0xFF

    return maxOf(red, green, blue) - minOf(red, green, blue) <= ACHROMATIC_CHANNEL_SPREAD
}

/** How far apart a colour's channels may be and still read as a grey; themed inks are warm by a few units. */
private const val ACHROMATIC_CHANNEL_SPREAD = 12

/**
 * The pixel colour a stroke stored as [storedArgb] renders with, given the current theme's ink
 * [themeInkArgb]: a stored colour that [isThemeInk] resolves to the theme's own ink, so the stroke
 * keeps reading across a theme change; every other stored colour renders exactly as stored.
 *
 * Theme-ink resolution only ever applies to [InkTool.PEN] strokes: a highlighter's own GRIS
 * (`#CFCFC8`) is nearly achromatic — well inside [isThemeInk]'s own spread — and would otherwise be
 * mistaken for a pen stroke drawn under the theme's ink and repainted on every theme switch, which a
 * highlighter's fixed colour must never do.
 */
fun resolveStrokeColor(storedArgb: Int, themeInkArgb: Int, tool: InkTool): Int =
    if (tool == InkTool.PEN && isThemeInk(storedArgb)) themeInkArgb else storedArgb

/**
 * [resolveStrokeColor]'s own sibling for a [com.folium.reader.core.ink.SheetTextBox]: a text box has
 * no [InkTool], so [isThemeInk] alone decides whether [storedArgb] follows the theme, the same rule
 * [resolveStrokeColor] applies for a pen stroke.
 */
fun resolveTextColor(storedArgb: Int, themeInkArgb: Int): Int =
    if (isThemeInk(storedArgb)) themeInkArgb else storedArgb
