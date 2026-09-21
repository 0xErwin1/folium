package com.folium.reader.ink

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.text.Layout
import android.text.Spanned
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.folium.reader.core.ink.SheetEdit
import com.folium.reader.core.ink.SheetItem
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetTextAlignment
import com.folium.reader.core.ink.SheetTextBox
import com.folium.reader.core.ink.SheetTextFont
import com.folium.reader.core.ink.SheetTextStyle
import com.folium.reader.core.ink.StrokeId
import kotlin.math.roundToInt

/** [SheetTextRecordCodec]'s own limit, mirrored here since that constant is `internal` to `:reader-core` and not visible across the module boundary. */
internal const val TEXT_MAX_BYTES: Int = 64 * 1024

/**
 * Where a session's own box sits and how it is styled. The geometry — [topLeft] and
 * [widthSheetUnits] — is fixed for the whole session; [font], [sizePt], [style], [colorArgb] and
 * [alignment] are not: [updateAttributes] replaces them live while the session stays open, since the
 * text panel's own FONT, SIZE, STYLE, ALIGNMENT and COLOR sections apply immediately to whichever box
 * is being placed or edited.
 */
internal data class TextEditingPlacement(
    val topLeft: SheetPoint,
    val widthSheetUnits: Float,
    val font: SheetTextFont,
    val sizePt: Float,
    val style: SheetTextStyle,
    val colorArgb: Int,
    val alignment: SheetTextAlignment = SheetTextAlignment.LEFT
)

/**
 * Owns the one [EditText] a [InkSurfaceTool.TEXT] session types into: an ordinary Android view, not a
 * Compose field, added directly as [host]'s own child and positioned exactly over the text box it
 * places or edits, at the same typeface, size and line height [TextLayoutEngine] draws with, scaled by
 * the live [SheetViewport] rather than the device's own density — the same design-pixel convention
 * [InkCommittedStrokesView] draws committed text at, so the editor never visibly jumps against what it
 * sits over.
 *
 * One instance lives for as long as [host] does; [open] starts a session, [reposition] keeps the
 * editor aligned through a pan, a zoom or a keyboard inset, and [commit] ends it, handing back the
 * [TextCommitDecision] the caller turns into a [com.folium.reader.core.ink.SheetEdit].
 */
internal class TextEditingSession(
    private val host: ViewGroup,
    private val layoutEngine: TextLayoutEngine
) {
    private var editText: EditText? = null
    private var original: SheetTextBox? = null
    private var placement: TextEditingPlacement? = null

    val isOpen: Boolean get() = editText != null
    val editingId: StrokeId? get() = original?.id

    /**
     * The open session's own box, live through [updateAttributes], while it holds an existing box
     * under edit; `null` while it holds a brand-new box instead, or with no session open. The Text
     * panel scoped to an existing box under edit reads this rather than the surface's own
     * [PenSettings][com.folium.reader.ink.PenSettings]-backed defaults, which apply to a brand-new box
     * only.
     */
    fun originalAttributesOrNull(): SelectedTextAttributes? {
        val active = placement ?: return null
        if (original == null) return null

        return SelectedTextAttributes(
            font = active.font, sizePt = active.sizePt, style = active.style,
            colorArgb = active.colorArgb, alignment = active.alignment
        )
    }

    /** Starts a session over [original] (`null` for a brand-new box), styled and coloured per [placement], scaled by [viewport]; [displayColorArgb] is [placement]'s own colour resolved against the live theme, since the editor shows a concrete colour on screen while [placement.colorArgb] may still be a THEME sentinel. */
    fun open(original: SheetTextBox?, placement: TextEditingPlacement, viewport: SheetViewport, displayColorArgb: Int) {
        discardView()

        this.original = original
        this.placement = placement

        val field = EditText(host.context).apply {
            setBackgroundColor(0)
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            gravity = Gravity.TOP or placement.alignment.toHorizontalGravity()
            isSingleLine = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters = arrayOf(Utf8ByteLimitInputFilter(TEXT_MAX_BYTES))
            typeface = layoutEngine.typefaceFor(placement.font, placement.style)
            layoutEngine.applyScaleIndependentMetrics(paint)
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            setTextColor(displayColorArgb)
            setText(original?.text.orEmpty())
            setSelection(text.length)
        }

        editText = field
        host.addView(field, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        reposition(viewport)
        field.requestFocus()

        // A programmatic focus never raises the keyboard by itself, and the request is dropped until the field is attached and focused.
        field.post { inputMethodManager()?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun inputMethodManager(): InputMethodManager? =
        host.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    /** The editor's own current bounds in view pixels, or `null` while no session is open. */
    fun boundsViewPx(): ViewRect? {
        val field = editText ?: return null
        return ViewRect(field.x, field.y, field.x + field.width, field.y + field.height)
    }

    /** Re-scales and repositions the editor for [viewport]: a pan, a zoom, or a keyboard inset scrolling the sheet all call this rather than [open] again. */
    fun reposition(viewport: SheetViewport) {
        val field = editText ?: return
        val active = placement ?: return

        val topLeftViewPx = viewport.sheetToView(active.topLeft)
        val widthViewPx = (active.widthSheetUnits * viewport.scale).roundToInt().coerceAtLeast(1)
        val designPxToViewPx = viewport.scale / StrokeSpace.UNITS_PER_SHEET_UNIT
        val lineSpacingAddViewPx = layoutEngine.lineSpacingAddDesignPx(active.font, active.sizePt, active.style) * designPxToViewPx

        // The host is a FrameLayout, which measures children through MarginLayoutParams: keep the params it generated at addView and only change their width.
        field.layoutParams = field.layoutParams.apply { width = widthViewPx }
        field.x = topLeftViewPx.x
        field.y = topLeftViewPx.y
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, layoutEngine.textSizeDesignPx(active.sizePt) * designPxToViewPx)
        field.setLineSpacing(lineSpacingAddViewPx, 1f)
        field.setPadding(0, lineSpacingAddViewPx.roundToInt().coerceAtLeast(0), 0, 0)
        field.requestLayout()
    }

    /**
     * Replaces the open session's own [font], [sizePt], [style], [alignment] and [colorArgb] live,
     * without closing or reopening the editor: the text panel's own FONT, SIZE, STYLE, ALIGNMENT and
     * COLOR sections call this while a session is open, rather than only taking effect on the next
     * box. A no-op while no session is open. [displayColorArgb] is [colorArgb] resolved against the
     * live theme, the same convention [open] follows.
     */
    fun updateAttributes(
        font: SheetTextFont,
        sizePt: Float,
        style: SheetTextStyle,
        colorArgb: Int,
        displayColorArgb: Int,
        viewport: SheetViewport,
        alignment: SheetTextAlignment
    ) {
        val field = editText ?: return
        val active = placement ?: return

        placement = active.copy(font = font, sizePt = sizePt, style = style, colorArgb = colorArgb, alignment = alignment)
        field.typeface = layoutEngine.typefaceFor(font, style)
        field.setTextColor(displayColorArgb)
        field.gravity = Gravity.TOP or alignment.toHorizontalGravity()
        reposition(viewport)
    }

    /**
     * Ends the session, returning the [SheetEdit.ReplaceItems] to apply, or `null` for a
     * [TextCommitDecision.Noop]. [newId] and [newSequence] are only ever called for a box that is
     * actually added: an outright removal or a no-op never mints either.
     */
    fun commit(newId: () -> StrokeId, newSequence: () -> Long): SheetEdit.ReplaceItems? {
        val field = editText ?: return null
        val active = placement ?: return null
        val startedFrom = original

        val decision = decideTextCommit(
            original = startedFrom,
            newText = field.text.toString(),
            font = active.font,
            sizePt = active.sizePt,
            style = active.style,
            colorArgb = active.colorArgb,
            topLeft = active.topLeft,
            widthSheetUnits = active.widthSheetUnits,
            alignment = active.alignment
        )

        val edit = when (decision) {
            TextCommitDecision.Noop -> null
            is TextCommitDecision.AddNew -> SheetEdit.ReplaceItems(
                removed = emptyList(),
                added = listOf(SheetItem.Text(buildTextBox(decision.text, active, newId(), newSequence())))
            )
            is TextCommitDecision.Replace -> SheetEdit.ReplaceItems(
                removed = listOf(SheetItem.Text(decision.original)),
                added = listOf(SheetItem.Text(buildTextBox(decision.text, active, newId(), newSequence())))
            )
            is TextCommitDecision.RemoveExisting -> SheetEdit.ReplaceItems(
                removed = listOf(SheetItem.Text(decision.original)),
                added = emptyList()
            )
        }

        discardView()
        return edit
    }

    private fun buildTextBox(text: String, placement: TextEditingPlacement, id: StrokeId, sequence: Long): SheetTextBox =
        buildAttributedTextBox(
            id = id,
            topLeft = placement.topLeft,
            widthSheetUnits = placement.widthSheetUnits,
            text = text,
            font = placement.font,
            sizePt = placement.sizePt,
            style = placement.style,
            colorArgb = placement.colorArgb,
            sequence = sequence,
            layoutEngine = layoutEngine,
            alignment = placement.alignment
        )

    private fun discardView() {
        editText?.let(host::removeView)
        editText = null

        // Deferred so that ending one box by tapping another keeps the keyboard up instead of racing a hide against the next show.
        host.post { if (editText == null) inputMethodManager()?.hideSoftInputFromWindow(host.windowToken, 0) }
        original = null
        placement = null
    }
}

/**
 * [this]'s own horizontal [Gravity] flag, combined with [Gravity.TOP] to give the editor's own
 * [EditText] the same left/centre/right alignment [TextLayoutEngine.toLayoutAlignment] gives the
 * committed text, so the editor never visibly re-wraps or re-aligns the moment an edit ends.
 * [Gravity.START] and [Gravity.END] rather than [Gravity.LEFT] and [Gravity.RIGHT], matching
 * [Layout.Alignment.ALIGN_NORMAL] and [Layout.Alignment.ALIGN_OPPOSITE]'s own left-to-right-relative
 * meaning.
 */
private fun SheetTextAlignment.toHorizontalGravity(): Int = when (this) {
    SheetTextAlignment.LEFT -> Gravity.START
    SheetTextAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
    SheetTextAlignment.RIGHT -> Gravity.END
}

/**
 * Blocks any edit that would push [dest]'s own UTF-8 encoding past [maxBytes], rather than truncating
 * it character by character: the pure byte-counting itself lives in [utf8ByteCount], so this class
 * stays a thin `android.text.InputFilter` adapter with nothing of its own worth a JVM test.
 */
private class Utf8ByteLimitInputFilter(private val maxBytes: Int) : InputFilter {
    override fun filter(source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int): CharSequence? {
        val resultingByteCount = utf8ByteCount(dest.subSequence(0, dstart)) +
            utf8ByteCount(source.subSequence(start, end)) +
            utf8ByteCount(dest.subSequence(dend, dest.length))

        return if (resultingByteCount <= maxBytes) null else ""
    }
}
