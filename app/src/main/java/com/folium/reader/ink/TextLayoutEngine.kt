package com.folium.reader.ink

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.folium.reader.R
import com.folium.reader.core.ink.SheetTextStyle
import kotlin.math.roundToInt

/** BODY's own text size, in design pixels: Georgia/serif paragraph text at the artboard's own 16px (`T-Hoja.dc.html:18`). */
private const val BODY_TEXT_SIZE_DESIGN_PX = 16f

/** TITLE's own text size, in design pixels: the artboard's own 19px, 700-weight heading (`T-Hoja.dc.html:18`). */
private const val TITLE_TEXT_SIZE_DESIGN_PX = 19f

/**
 * One box's own laid-out text, built in design pixels (see [TextLayoutEngine]): [layout] draws
 * directly at that scale, [topOffsetDesignPx] is the extra space above the first line a caller draws
 * or pads with to land its own baseline on a rule, and [heightSheetUnits] is the box's own measured
 * height — [topOffsetDesignPx] plus [StaticLayout.getHeight], converted to sheet units — the value
 * [com.folium.reader.core.ink.SheetTextBox.heightSheetUnits] is stored as.
 */
internal data class TextBoxLayout(
    val layout: StaticLayout,
    val topOffsetDesignPx: Float,
    val heightSheetUnits: Float
)

/**
 * Builds a [com.folium.reader.core.ink.SheetTextBox]'s own text in design pixels — `1 /
 * StrokeSpace.UNITS_PER_SHEET_UNIT` of a sheet unit — independent of device density or zoom, so the
 * same box measures and draws identically everywhere: [InkCommittedStrokesView],
 * [SheetThumbnailRenderer] and [TextEditingSession] all build through this one engine rather than each
 * laying text out its own way.
 *
 * Every line is exactly [SheetRuleGrid.LINE_HEIGHT_DESIGN_PX] design pixels tall, matching the ruled
 * sheet's own rule spacing (`canvas.json`, `nota-t-hoja`: "todo el texto cae sobre" the 32px grid).
 * [TextPaint.ascent] and [TextPaint.descent] give this style's own natural, single-line height; the
 * gap between that and the grid's own 32px is folded into [StaticLayout]'s own line spacing, added
 * after every line, and, once more, into [TextBoxLayout.topOffsetDesignPx] before the first line — the
 * same amount both times, so every line, the first included, ends up exactly 32px tall and every
 * line's own baseline lands [TextPaint.descent] design pixels above the rule that closes it.
 */
internal class TextLayoutEngine(context: Context) {
    private val appContext = context.applicationContext

    private val bodyTypeface: Typeface by lazy {
        ResourcesCompat.getFont(appContext, R.font.gelasio) ?: Typeface.SERIF
    }

    private val titleTypeface: Typeface by lazy {
        ResourcesCompat.getFont(appContext, R.font.schibsted_grotesk_bold) ?: Typeface.DEFAULT_BOLD
    }

    fun typefaceFor(style: SheetTextStyle): Typeface = when (style) {
        SheetTextStyle.BODY -> bodyTypeface
        SheetTextStyle.TITLE -> titleTypeface
    }

    fun textSizeDesignPx(style: SheetTextStyle): Float = when (style) {
        SheetTextStyle.BODY -> BODY_TEXT_SIZE_DESIGN_PX
        SheetTextStyle.TITLE -> TITLE_TEXT_SIZE_DESIGN_PX
    }

    /** A [TextPaint] for [style], sized and typefaced in design pixels; [colorArgb] is applied as-is, already resolved by the caller through [resolveTextColor]. */
    fun paintFor(style: SheetTextStyle, colorArgb: Int): TextPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        typeface = typefaceFor(style)
        textSize = textSizeDesignPx(style)
        color = colorArgb
        applyScaleIndependentMetrics(this)
    }

    /**
     * Makes glyph advances scale linearly with the text size instead of being hinted and rounded to
     * whole pixels. The committed text is laid out in design pixels and drawn through the viewport's
     * matrix, while the editor lays the same text out directly at the on-screen size: without this the
     * two disagree on line widths and a paragraph re-wraps the moment an edit ends.
     */
    fun applyScaleIndependentMetrics(paint: TextPaint) {
        paint.isLinearText = true
        paint.isSubpixelText = true
    }

    /** [style]'s own extra spacing folded into every line, and into the first line's own top offset; see this class's own doc. */
    fun lineSpacingAddDesignPx(style: SheetTextStyle): Float {
        val paint = paintFor(style, colorArgb = 0)
        return SheetRuleGrid.LINE_HEIGHT_DESIGN_PX - (paint.descent() - paint.ascent())
    }

    /**
     * Lays [text] out at [widthSheetUnits] wide, styled and coloured per [style] and [colorArgb]. An
     * empty [text] still lays out as one empty line, so a freshly placed, not-yet-typed box still
     * measures a sensible height.
     */
    fun layout(text: String, style: SheetTextStyle, widthSheetUnits: Float, colorArgb: Int): TextBoxLayout {
        val paint = paintFor(style, colorArgb)
        val widthDesignPx = (widthSheetUnits * StrokeSpace.UNITS_PER_SHEET_UNIT).roundToInt().coerceAtLeast(1)
        val spacingAdd = lineSpacingAddDesignPx(style).coerceAtLeast(0f)

        val staticLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, widthDesignPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setLineSpacing(spacingAdd, 1f)
            .build()

        val heightDesignPx = spacingAdd + staticLayout.height
        val heightSheetUnits = heightDesignPx / StrokeSpace.UNITS_PER_SHEET_UNIT

        return TextBoxLayout(staticLayout, spacingAdd, heightSheetUnits)
    }
}
