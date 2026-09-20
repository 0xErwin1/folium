package com.folium.reader.ink

import java.text.NumberFormat
import java.util.Locale

/** The five fixed highlight hexes the highlighter panel offers (`rail-spec.md` 2.2, RESALTA panel). */
internal object HighlighterColors {
    const val YELLOW_ARGB: Int = 0xFFF2D24B.toInt()
    const val GREEN_ARGB: Int = 0xFF9AD08A.toInt()
    const val PINK_ARGB: Int = 0xFFF0A3B5.toInt()
    const val BLUE_ARGB: Int = 0xFF9CC8EC.toInt()
    const val GREY_ARGB: Int = 0xFFCFCFC8.toInt()
}

/**
 * How the highlighter panel's COLOR section stores a colour choice (`rail-spec.md` 2.2, RESALTA
 * panel). Unlike [PenColorChoice], none of these stands for "the theme's own ink": a highlight is
 * always one of these five fixed washes, [storedArgb] its own opaque colour, with the alpha wash
 * applied only when a brush is built from it (see [highlighterBrushColor]).
 */
enum class HighlighterColorChoice(val storedArgb: Int, val testTag: String) {
    YELLOW(HighlighterColors.YELLOW_ARGB, SheetPaneTestTags.SELECTOR_HIGHLIGHT_COLOUR_YELLOW),
    GREEN(HighlighterColors.GREEN_ARGB, SheetPaneTestTags.SELECTOR_HIGHLIGHT_COLOUR_GREEN),
    PINK(HighlighterColors.PINK_ARGB, SheetPaneTestTags.SELECTOR_HIGHLIGHT_COLOUR_PINK),
    BLUE(HighlighterColors.BLUE_ARGB, SheetPaneTestTags.SELECTOR_HIGHLIGHT_COLOUR_BLUE),
    GREY(HighlighterColors.GREY_ARGB, SheetPaneTestTags.SELECTOR_HIGHLIGHT_COLOUR_GREY)
}

/**
 * The choices the highlighter panel's colour row offers under [eInk]: on a monochrome appearance
 * every colour but [HighlighterColorChoice.GREY] falls to the same tone and does not distinguish, so
 * only GREY is offered there (`rail-spec.md` 2.2, RESALTA panel helper text).
 */
internal fun highlightColourOptions(eInk: Boolean): List<HighlighterColorChoice> =
    if (eInk) listOf(HighlighterColorChoice.GREY) else HighlighterColorChoice.entries

/**
 * The colour [choice] actually reads as under [eInk]: GREY regardless of the stored [choice] while
 * e-ink is active, without touching the stored choice itself, so a later switch back to a colour
 * appearance shows the colour the user actually picked rather than GREY.
 */
internal fun effectiveHighlightColour(choice: HighlighterColorChoice, eInk: Boolean): HighlighterColorChoice =
    if (eInk) HighlighterColorChoice.GREY else choice

/** The highlighter stepper's own range and step, in whole millimetres (`rail-spec.md` task instructions: 2..20mm, default 8). */
internal const val HIGHLIGHTER_WIDTH_MIN_MM: Int = 2
internal const val HIGHLIGHTER_WIDTH_MAX_MM: Int = 20
internal const val HIGHLIGHTER_WIDTH_STEP_MM: Int = 1
internal const val HIGHLIGHTER_WIDTH_DEFAULT_MM: Int = 8

/** Clamps a stepper step to the highlighter width's own range, so a bound is never overshot regardless of the direction stepped from. */
internal fun clampHighlighterWidthMm(widthMm: Int): Int = widthMm.coerceIn(HIGHLIGHTER_WIDTH_MIN_MM, HIGHLIGHTER_WIDTH_MAX_MM)

/** The stepper's own value text: a whole number of millimetres in the locale's own digits, with a trailing " mm" unit. */
internal fun formatHighlighterWidthMm(widthMm: Int, locale: Locale = Locale.getDefault()): String =
    "${NumberFormat.getIntegerInstance(locale).format(widthMm)} mm"

/**
 * The fraction of full opacity a highlighter stroke is painted with: a wash rather than a solid
 * fill, so text drawn under it stays legible (`rail-spec.md` Q6: opacity is not specified by the
 * design, this is the task's own choice).
 */
internal const val HIGHLIGHTER_ALPHA: Float = 0.4f

/**
 * The pixel colour a highlighter brush is built with, given the opaque colour [storedArgb] a stroke
 * is stored under: [HIGHLIGHTER_ALPHA] applied to its alpha channel, so a later change to that
 * constant re-washes every highlighter stroke without touching stored data or rebuilding any mesh
 * from samples.
 */
internal fun highlighterBrushColor(storedArgb: Int): Int {
    val alpha = (HIGHLIGHTER_ALPHA * 255f).toInt().coerceIn(0, 255)
    return (alpha shl 24) or (storedArgb and 0x00FFFFFF)
}
