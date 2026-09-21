package com.folium.reader.ink

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.folium.reader.R
import com.folium.reader.core.ink.SheetTextAlignment
import com.folium.reader.core.ink.SheetTextFont
import com.folium.reader.core.ink.SheetTextStyle
import kotlin.math.roundToInt

/** 1pt is 1 design pixel — `1 / StrokeSpace.UNITS_PER_SHEET_UNIT` of a sheet unit — the same scale the T-Hoja artboard's own 16px paragraph text already assumes. */
private const val POINTS_PER_DESIGN_PX = 1f

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
 * StrokeSpace.UNITS_PER_SHEET_UNIT` of a sheet unit, the same scale a point size is stored in — so the
 * same box measures and draws identically everywhere: [InkCommittedStrokesView],
 * [SheetThumbnailRenderer] and [TextEditingSession] all build through this one engine rather than each
 * laying text out its own way.
 *
 * A line's own height is always a whole multiple of [SheetRuleGrid.LINE_HEIGHT_DESIGN_PX], the ruled
 * sheet's own rule spacing (`canvas.json`, `nota-t-hoja`: "todo el texto cae sobre" the 32px grid): see
 * [SheetRuleGrid.lineHeightForTextSize]. [TextPaint.ascent] and [TextPaint.descent] give this size's
 * own natural, single-line height; the gap between that and the chosen line height is folded into
 * [StaticLayout]'s own line spacing, added after every line, and, once more, into
 * [TextBoxLayout.topOffsetDesignPx] before the first line — the same amount both times, so every line,
 * the first included, ends up exactly that line height tall and every line's own baseline lands
 * [TextPaint.descent] design pixels above the rule that closes it.
 */
internal class TextLayoutEngine(context: Context) {
    private val appContext = context.applicationContext

    private val serifNormal: Typeface by lazy { loadFont(R.font.gelasio, Typeface.SERIF) }
    private val serifBold: Typeface by lazy { loadFont(R.font.gelasio_bold, Typeface.DEFAULT_BOLD) }
    private val serifItalic: Typeface by lazy { loadFont(R.font.gelasio_italic, Typeface.SERIF) }

    private val sansNormal: Typeface by lazy { loadFont(R.font.schibsted_grotesk, Typeface.SANS_SERIF) }
    private val sansBold: Typeface by lazy { loadFont(R.font.schibsted_grotesk_bold, Typeface.DEFAULT_BOLD) }
    private val sansItalic: Typeface by lazy { loadFont(R.font.schibsted_grotesk_italic, Typeface.SANS_SERIF) }

    /**
     * [fontResId]'s own font resource: [R.font.gelasio_bold] and [R.font.schibsted_grotesk_bold] are
     * each a `font-family` XML resource that instantiates its own variable font at the real 700 `wght`
     * axis value, rather than letting the platform fake a heavier weight from the regular instance —
     * every other id here is a plain font file loaded at its own default axis value instead, since that
     * default is already the regular weight this build wants.
     */
    private fun loadFont(fontResId: Int, fallback: Typeface): Typeface =
        ResourcesCompat.getFont(appContext, fontResId) ?: fallback

    /**
     * [font]'s own typeface at [style]: SERIF and SANS each load their own regular, 700-weight or
     * italic font file — never a synthetic bold or a skewed regular — while MONO has no file of its
     * own and instead asks the platform for its own bold or italic instance of [Typeface.MONOSPACE].
     * [SheetTextStyle.ITALIC] never combines with bold: the text panel offers one of the three as a
     * single choice.
     */
    fun typefaceFor(font: SheetTextFont, style: SheetTextStyle): Typeface = when (font) {
        SheetTextFont.SERIF -> when (style) {
            SheetTextStyle.NORMAL -> serifNormal
            SheetTextStyle.BOLD -> serifBold
            SheetTextStyle.ITALIC -> serifItalic
        }
        SheetTextFont.SANS -> when (style) {
            SheetTextStyle.NORMAL -> sansNormal
            SheetTextStyle.BOLD -> sansBold
            SheetTextStyle.ITALIC -> sansItalic
        }
        SheetTextFont.MONO -> when (style) {
            SheetTextStyle.NORMAL -> Typeface.MONOSPACE
            SheetTextStyle.BOLD -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            SheetTextStyle.ITALIC -> Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
        }
    }

    /** [sizePt] converted to design pixels: a 1:1 mapping, see [POINTS_PER_DESIGN_PX]. */
    fun textSizeDesignPx(sizePt: Float): Float = sizePt * POINTS_PER_DESIGN_PX

    /** A [TextPaint] for [font], [sizePt] and [style]; [colorArgb] is applied as-is, already resolved by the caller through [resolveTextColor]. */
    fun paintFor(font: SheetTextFont, sizePt: Float, style: SheetTextStyle, colorArgb: Int): TextPaint =
        TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = typefaceFor(font, style)
            textSize = textSizeDesignPx(sizePt)
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

    /** [sizePt]'s own extra spacing folded into every line, and into the first line's own top offset; see this class's own doc. */
    fun lineSpacingAddDesignPx(font: SheetTextFont, sizePt: Float, style: SheetTextStyle): Float {
        val paint = paintFor(font, sizePt, style, colorArgb = 0)
        val lineHeight = SheetRuleGrid.lineHeightForTextSize(textSizeDesignPx(sizePt))
        return lineHeight - (paint.descent() - paint.ascent())
    }

    /**
     * Lays [text] out at [widthSheetUnits] wide, styled per [font], [sizePt] and [style], aligned per
     * [alignment] and coloured per [colorArgb]. An empty [text] still lays out as one empty line, so a
     * freshly placed, not-yet-typed box still measures a sensible height.
     */
    fun layout(
        text: String,
        font: SheetTextFont,
        sizePt: Float,
        style: SheetTextStyle,
        widthSheetUnits: Float,
        colorArgb: Int,
        alignment: SheetTextAlignment = SheetTextAlignment.LEFT
    ): TextBoxLayout {
        val paint = paintFor(font, sizePt, style, colorArgb)
        val widthDesignPx = (widthSheetUnits * StrokeSpace.UNITS_PER_SHEET_UNIT).roundToInt().coerceAtLeast(1)
        val spacingAdd = lineSpacingAddDesignPx(font, sizePt, style).coerceAtLeast(0f)

        val staticLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, widthDesignPx)
            .setAlignment(alignment.toLayoutAlignment())
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

/** [this]'s own [Layout.Alignment], inside the box's own width: never [Layout.Alignment.ALIGN_CENTER] confused with a paragraph-relative alignment, since a text box is always left-to-right here. */
internal fun SheetTextAlignment.toLayoutAlignment(): Layout.Alignment = when (this) {
    SheetTextAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
    SheetTextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
    SheetTextAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
}
