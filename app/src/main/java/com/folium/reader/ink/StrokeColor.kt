package com.folium.reader.ink

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
 * The pixel colour a stroke stored as [storedArgb] renders with, given the current theme's ink
 * [themeInkArgb]: [STROKE_THEME_INK_SENTINEL_ARGB] resolves to the theme's own ink so the stroke keeps
 * reading across a theme change; every other stored colour renders exactly as stored.
 */
fun resolveStrokeColor(storedArgb: Int, themeInkArgb: Int): Int =
    if (storedArgb == STROKE_THEME_INK_SENTINEL_ARGB) themeInkArgb else storedArgb
