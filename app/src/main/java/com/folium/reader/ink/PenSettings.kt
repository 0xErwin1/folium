package com.folium.reader.ink

import com.folium.reader.core.ink.InkTip
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * How the pen panel's INK section stores a colour choice (`rail-spec.md` 2.2, LÁPIZ panel). [THEME]
 * is never a concrete colour by itself: it stands for "whatever the current theme's ink is", so a
 * stroke drawn with it stays legible after a light/dark or e-ink theme change instead of freezing to
 * the black that theme happened to have at draw time.
 */
enum class PenColorChoice(val testTag: String) {
    THEME(SheetPaneTestTags.SELECTOR_COLOUR_BLACK),
    RED(SheetPaneTestTags.SELECTOR_COLOUR_RED),
    BLUE(SheetPaneTestTags.SELECTOR_COLOUR_BLUE),
    GREEN(SheetPaneTestTags.SELECTOR_COLOUR_GREEN)
}

/** The three fixed ink hexes the pen panel offers alongside the theme's own ink (`rail-spec.md` 2.2). */
internal object PenColors {
    const val RED_ARGB: Int = 0xFFB3261E.toInt()
    const val BLUE_ARGB: Int = 0xFF1F4E9A.toInt()
    const val GREEN_ARGB: Int = 0xFF2E6B34.toInt()
}

/**
 * The ARGB colour this choice is drawn and stored with: [PenColorChoice.THEME] stores
 * [STROKE_THEME_INK_SENTINEL_ARGB] rather than a resolved ink, so a stroke keeps following the theme
 * after it is committed instead of freezing to whichever ink was active when it was drawn.
 */
internal fun PenColorChoice.storedArgb(): Int = when (this) {
    PenColorChoice.THEME -> STROKE_THEME_INK_SENTINEL_ARGB
    PenColorChoice.RED -> PenColors.RED_ARGB
    PenColorChoice.BLUE -> PenColors.BLUE_ARGB
    PenColorChoice.GREEN -> PenColors.GREEN_ARGB
}

/**
 * The pixel colour this choice currently displays as, given the caller's own ink [themeInkArgb]: read
 * at the moment it is needed, never cached, so a THEME preview always reflects whichever theme is
 * active right now rather than the one active when the choice was made.
 */
internal fun PenColorChoice.resolveArgb(themeInkArgb: Int): Int = resolveStrokeColor(storedArgb(), themeInkArgb)

/** The stepper's own range and step for the pen's width, in tenths of a millimetre (`rail-spec.md` 2.2: "0,5 mm"). */
internal const val PEN_WIDTH_MIN_TENTHS_MM: Int = 1
internal const val PEN_WIDTH_MAX_TENTHS_MM: Int = 30
internal const val PEN_WIDTH_STEP_TENTHS_MM: Int = 1
internal const val PEN_WIDTH_DEFAULT_TENTHS_MM: Int = 5

/** Clamps a stepper step to the pen width's own range, so a bound is never overshot regardless of the direction stepped from. */
internal fun clampPenWidthTenthsMm(tenthsMm: Int): Int =
    tenthsMm.coerceIn(PEN_WIDTH_MIN_TENTHS_MM, PEN_WIDTH_MAX_TENTHS_MM)

/**
 * A sheet's nominal width is 210 mm (`rail-spec.md` task instructions), so a pen width expressed in
 * millimetres converts to the sheet-unit fraction [InkDrawingSurface.setPenWidthSheetUnits] expects
 * by dividing by that constant.
 */
private const val SHEET_NOMINAL_WIDTH_MM = 210f

internal fun mmToSheetUnits(mm: Float): Float = mm / SHEET_NOMINAL_WIDTH_MM

internal fun sheetUnitsToMm(sheetUnits: Float): Float = sheetUnits * SHEET_NOMINAL_WIDTH_MM

/** The stepper's own value text: one decimal, the locale's decimal separator, and a trailing " mm" unit. */
internal fun formatPenWidthMm(tenthsMm: Int, locale: Locale = Locale.getDefault()): String {
    val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    return "${tenthsMm / 10}$separator${tenthsMm % 10} mm"
}

/**
 * The pen's remembered settings: tip, width and colour choice. Everything else the pen panel shows —
 * ENDEREZAR, zoom, and every other tool's panel — has no engine behind it yet (`rail-spec.md` section
 * 6), so only these three are persisted.
 */
data class PenSettings(
    val tip: InkTip,
    val widthTenthsMm: Int,
    val colorChoice: PenColorChoice
) {
    companion object {
        val DEFAULT = PenSettings(
            tip = InkTip.BALLPOINT,
            widthTenthsMm = PEN_WIDTH_DEFAULT_TENTHS_MM,
            colorChoice = PenColorChoice.THEME
        )
    }
}

/**
 * Pure encode/decode for [PenSettings], following [com.folium.reader.core.library.TwoPageSpreadPreferences]'s
 * own shape: a version marker line guards every later line against a format this build does not
 * understand, and any unknown or corrupt value falls back to [PenSettings.DEFAULT] field by field
 * rather than discarding the whole record.
 */
internal object PenSettingsCodec {
    const val VERSION_MARKER = "folium-pen 1"

    fun encode(settings: PenSettings): List<String> = listOf(
        VERSION_MARKER,
        settings.tip.name,
        settings.widthTenthsMm.toString(),
        settings.colorChoice.name
    )

    fun decode(lines: List<String>): PenSettings {
        if (lines.firstOrNull() != VERSION_MARKER) return PenSettings.DEFAULT

        val tip = lines.getOrNull(1)?.let { name -> runCatching { InkTip.valueOf(name) }.getOrNull() }
            ?: PenSettings.DEFAULT.tip
        val widthTenthsMm = lines.getOrNull(2)?.toIntOrNull()?.let(::clampPenWidthTenthsMm)
            ?: PenSettings.DEFAULT.widthTenthsMm
        val colorChoice = lines.getOrNull(3)?.let { name -> runCatching { PenColorChoice.valueOf(name) }.getOrNull() }
            ?: PenSettings.DEFAULT.colorChoice

        return PenSettings(tip, widthTenthsMm, colorChoice)
    }
}
