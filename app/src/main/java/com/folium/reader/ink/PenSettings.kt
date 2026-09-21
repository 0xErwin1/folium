package com.folium.reader.ink

import com.folium.reader.core.ink.InkShape
import com.folium.reader.core.ink.InkTip
import com.folium.reader.core.ink.InkTool
import com.folium.reader.core.ink.SheetTextAlignment
import com.folium.reader.core.ink.SheetTextFont
import com.folium.reader.core.ink.SheetTextStyle
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
 * The reverse of [PenColorChoice.storedArgb]: the choice [storedArgb] itself was drawn under, or
 * `null` once it matches none of the four — never expected for a text box's own stored colour, since
 * every one is set from this same enum, but a caller reads it as "no option selected" rather than
 * risk picking one arbitrarily.
 */
internal fun penColorChoiceForStoredArgb(storedArgb: Int): PenColorChoice? =
    PenColorChoice.entries.find { it.storedArgb() == storedArgb }

/**
 * The pixel colour this choice currently displays as, given the caller's own ink [themeInkArgb]: read
 * at the moment it is needed, never cached, so a THEME preview always reflects whichever theme is
 * active right now rather than the one active when the choice was made.
 */
internal fun PenColorChoice.resolveArgb(themeInkArgb: Int): Int = resolveStrokeColor(storedArgb(), themeInkArgb, InkTool.PEN)

/** The stepper's own range and step for the pen's width, in tenths of a millimetre (`rail-spec.md` 2.2: "0,5 mm"). */
internal const val PEN_WIDTH_MIN_TENTHS_MM: Int = 1
internal const val PEN_WIDTH_MAX_TENTHS_MM: Int = 30
internal const val PEN_WIDTH_STEP_TENTHS_MM: Int = 1
internal const val PEN_WIDTH_DEFAULT_TENTHS_MM: Int = 5

/** Clamps a stepper step to the pen width's own range, so a bound is never overshot regardless of the direction stepped from. */
internal fun clampPenWidthTenthsMm(tenthsMm: Int): Int =
    tenthsMm.coerceIn(PEN_WIDTH_MIN_TENTHS_MM, PEN_WIDTH_MAX_TENTHS_MM)

/**
 * The text panel's own SIZE stepper: the design writes no bounds or step (`T-Selectores.dc.html`,
 * TEXTO panel), only that 16pt sits at 28% of the track, which this 8..36 range reproduces exactly.
 */
internal const val TEXT_SIZE_MIN_PT: Int = 8
internal const val TEXT_SIZE_MAX_PT: Int = 36
internal const val TEXT_SIZE_STEP_PT: Int = 1
internal const val TEXT_SIZE_DEFAULT_PT: Int = 16

/** Clamps a stepper step to the text size's own range, so a bound is never overshot regardless of the direction stepped from. */
internal fun clampTextSizePt(pt: Int): Int = pt.coerceIn(TEXT_SIZE_MIN_PT, TEXT_SIZE_MAX_PT)

/** The value text shown at the SIZE header's own right edge (`T-Selectores.dc.html`, TEXTO panel: `16 pt`). */
internal fun formatTextSizePt(pt: Int): String = "$pt pt"

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
 * The pen's remembered settings: tip, width, colour choice, the eraser's own size, the highlighter's
 * own width and colour choice, the shape tool's own figure, and the shape tool's own width and colour
 * choice — independent of the pen's, since the artboard's Forma panel offers its own GROSOR and COLOR
 * rather than reusing the pen's (`rail-spec.md` 2.2, FORMA panel) — whether the tool rail is
 * collapsed to its hidden tab, which the design leaves remembered rather than resetting every time a
 * sheet is opened (`nota-t-oculta`), and the pen's own ENDEREZAR straightening mode, alongside the
 * highlighter's own, independent straightening mode, the select tool's own MODE (`rail-spec.md`
 * 2.2, ELEGIR panel), and the text tool's own FONT, SIZE, STYLE, ALIGNMENT and COLOR
 * (`T-Selectores.dc.html`, TEXTO panel). Everything else the pen panel shows — zoom and every other
 * tool's panel — has no engine behind it yet (`rail-spec.md` section 6), so only these are persisted.
 */
data class PenSettings(
    val tip: InkTip,
    val widthTenthsMm: Int,
    val colorChoice: PenColorChoice,
    val eraserSizeMm: Int,
    val highlighterWidthMm: Int = HIGHLIGHTER_WIDTH_DEFAULT_MM,
    val highlighterColorChoice: HighlighterColorChoice = HighlighterColorChoice.YELLOW,
    val shape: InkShape = InkShape.LINE,
    val shapeWidthTenthsMm: Int = PEN_WIDTH_DEFAULT_TENTHS_MM,
    val shapeColorChoice: PenColorChoice = PenColorChoice.THEME,
    val eraserMode: InkEraserMode = InkEraserMode.WHOLE_STROKE,
    val railHidden: Boolean = false,
    val straightenMode: InkStraightenMode = InkStraightenMode.ON_HOLD,
    val highlighterStraightenMode: InkStraightenMode = InkStraightenMode.ON_HOLD,
    val selectMode: PenSelectMode = PenSelectMode.LASSO,
    val textFont: SheetTextFont = SheetTextFont.SERIF,
    val textSizePt: Int = TEXT_SIZE_DEFAULT_PT,
    val textStyle: SheetTextStyle = SheetTextStyle.NORMAL,
    val textColorChoice: PenColorChoice = PenColorChoice.THEME,
    val textAlignment: SheetTextAlignment = SheetTextAlignment.LEFT
) {
    companion object {
        val DEFAULT = PenSettings(
            tip = InkTip.BALLPOINT,
            widthTenthsMm = PEN_WIDTH_DEFAULT_TENTHS_MM,
            colorChoice = PenColorChoice.THEME,
            eraserSizeMm = ERASER_SIZE_DEFAULT_MM,
            highlighterWidthMm = HIGHLIGHTER_WIDTH_DEFAULT_MM,
            highlighterColorChoice = HighlighterColorChoice.YELLOW,
            shape = InkShape.LINE,
            shapeWidthTenthsMm = PEN_WIDTH_DEFAULT_TENTHS_MM,
            shapeColorChoice = PenColorChoice.THEME,
            eraserMode = InkEraserMode.WHOLE_STROKE,
            railHidden = false,
            straightenMode = InkStraightenMode.ON_HOLD,
            highlighterStraightenMode = InkStraightenMode.ON_HOLD,
            selectMode = PenSelectMode.LASSO,
            textFont = SheetTextFont.SERIF,
            textSizePt = TEXT_SIZE_DEFAULT_PT,
            textStyle = SheetTextStyle.NORMAL,
            textColorChoice = PenColorChoice.THEME,
            textAlignment = SheetTextAlignment.LEFT
        )
    }
}

/**
 * Pure encode/decode for [PenSettings], following [com.folium.reader.core.library.TwoPageSpreadPreferences]'s
 * own shape: a version marker line guards every later line against a format this build does not
 * understand, and any unknown or corrupt value falls back to [PenSettings.DEFAULT] field by field
 * rather than discarding the whole record. The eraser size line, the two highlighter lines that
 * follow it, the shape line after those, the two shape-width/-colour lines after that, the eraser
 * mode line after those, the rail-hidden line after that, and the straighten-mode line after that,
 * are each read as absent rather than corrupt when they are simply missing, so content written before
 * the eraser, highlighter, shape, shape-width/-colour, eraser-mode, rail-hidden, straighten-mode,
 * highlighter-straighten-mode, select-mode, text-font, text-size, text-style, text-colour or
 * text-alignment state existed still decodes. Content written before straightening existed decodes to
 * [InkStraightenMode.ON_HOLD] — the design's own selected option — rather than
 * [InkStraightenMode.NEVER], for both the pen's and the highlighter's own mode. Content written before
 * the select tool existed decodes to [PenSelectMode.LASSO], the design's own selected option for the
 * ELEGIR panel's MODO. Content written before the text tool existed, or before it grew its own FONT
 * and SIZE sections, decodes to [SheetTextFont.SERIF], [TEXT_SIZE_DEFAULT_PT], [SheetTextStyle.NORMAL]
 * and [PenColorChoice.THEME], the text panel's own defaults: no build has ever shipped a text-style
 * line with [SheetTextStyle]'s earlier `BODY`/`TITLE` entries, so there is nothing to translate here.
 * The text-alignment line is the newest, appended after every other line so a file written by any
 * earlier build — including one with the text tool's FONT, SIZE, STYLE and COLOR already in place —
 * still decodes, falling back to [SheetTextAlignment.LEFT], the ALINEACIÓN section's own default.
 */
internal object PenSettingsCodec {
    const val VERSION_MARKER = "folium-pen 1"

    fun encode(settings: PenSettings): List<String> = listOf(
        VERSION_MARKER,
        settings.tip.name,
        settings.widthTenthsMm.toString(),
        settings.colorChoice.name,
        settings.eraserSizeMm.toString(),
        settings.highlighterWidthMm.toString(),
        settings.highlighterColorChoice.name,
        settings.shape.name,
        settings.shapeWidthTenthsMm.toString(),
        settings.shapeColorChoice.name,
        settings.eraserMode.name,
        settings.railHidden.toString(),
        settings.straightenMode.name,
        settings.highlighterStraightenMode.name,
        settings.selectMode.name,
        settings.textFont.name,
        settings.textSizePt.toString(),
        settings.textStyle.name,
        settings.textColorChoice.name,
        settings.textAlignment.name
    )

    fun decode(lines: List<String>): PenSettings {
        if (lines.firstOrNull() != VERSION_MARKER) return PenSettings.DEFAULT

        val tip = lines.getOrNull(1)?.let { name -> runCatching { InkTip.valueOf(name) }.getOrNull() }
            ?: PenSettings.DEFAULT.tip
        val widthTenthsMm = lines.getOrNull(2)?.toIntOrNull()?.let(::clampPenWidthTenthsMm)
            ?: PenSettings.DEFAULT.widthTenthsMm
        val colorChoice = lines.getOrNull(3)?.let { name -> runCatching { PenColorChoice.valueOf(name) }.getOrNull() }
            ?: PenSettings.DEFAULT.colorChoice
        val eraserSizeMm = lines.getOrNull(4)?.toIntOrNull()?.let(::clampEraserSizeMm)
            ?: PenSettings.DEFAULT.eraserSizeMm
        val highlighterWidthMm = lines.getOrNull(5)?.toIntOrNull()?.let(::clampHighlighterWidthMm)
            ?: PenSettings.DEFAULT.highlighterWidthMm
        val highlighterColorChoice = lines.getOrNull(6)
            ?.let { name -> runCatching { HighlighterColorChoice.valueOf(name) }.getOrNull() }
            ?: PenSettings.DEFAULT.highlighterColorChoice
        val shape = lines.getOrNull(7)?.let { name -> runCatching { InkShape.valueOf(name) }.getOrNull() }
            ?: PenSettings.DEFAULT.shape
        val shapeWidthTenthsMm = lines.getOrNull(8)?.toIntOrNull()?.let(::clampPenWidthTenthsMm)
            ?: PenSettings.DEFAULT.shapeWidthTenthsMm
        val shapeColorChoice = lines.getOrNull(9)
            ?.let { name -> runCatching { PenColorChoice.valueOf(name) }.getOrNull() }
            ?: PenSettings.DEFAULT.shapeColorChoice
        val eraserMode = lines.getOrNull(10)
            ?.let { name -> runCatching { InkEraserMode.valueOf(name) }.getOrNull() }
            ?: PenSettings.DEFAULT.eraserMode
        val railHidden = lines.getOrNull(11)?.toBooleanStrictOrNull() ?: PenSettings.DEFAULT.railHidden
        val straightenMode = lines.getOrNull(12)
            ?.let { name -> runCatching { InkStraightenMode.valueOf(name) }.getOrNull() }
            ?: PenSettings.DEFAULT.straightenMode
        val highlighterStraightenMode = lines.getOrNull(13)
            ?.let { name -> runCatching { InkStraightenMode.valueOf(name) }.getOrNull() }
            ?: PenSettings.DEFAULT.highlighterStraightenMode
        val selectMode = lines.getOrNull(14)
            ?.let { name -> runCatching { PenSelectMode.valueOf(name) }.getOrNull() }
            ?: PenSettings.DEFAULT.selectMode
        val textFont = lines.getOrNull(15)
            ?.let { name -> runCatching { SheetTextFont.valueOf(name) }.getOrNull() }
            ?: PenSettings.DEFAULT.textFont
        val textSizePt = lines.getOrNull(16)?.toIntOrNull()?.let(::clampTextSizePt)
            ?: PenSettings.DEFAULT.textSizePt
        val textStyle = lines.getOrNull(17)
            ?.let { name -> runCatching { SheetTextStyle.valueOf(name) }.getOrNull() }
            ?: PenSettings.DEFAULT.textStyle
        val textColorChoice = lines.getOrNull(18)
            ?.let { name -> runCatching { PenColorChoice.valueOf(name) }.getOrNull() }
            ?: PenSettings.DEFAULT.textColorChoice
        val textAlignment = lines.getOrNull(19)
            ?.let { name -> runCatching { SheetTextAlignment.valueOf(name) }.getOrNull() }
            ?: PenSettings.DEFAULT.textAlignment

        return PenSettings(
            tip, widthTenthsMm, colorChoice, eraserSizeMm, highlighterWidthMm, highlighterColorChoice,
            shape, shapeWidthTenthsMm, shapeColorChoice, eraserMode, railHidden, straightenMode, highlighterStraightenMode,
            selectMode, textFont, textSizePt, textStyle, textColorChoice, textAlignment
        )
    }
}
