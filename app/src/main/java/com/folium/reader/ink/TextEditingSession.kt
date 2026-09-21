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
import com.folium.reader.core.ink.SheetTextBox
import com.folium.reader.core.ink.SheetTextStyle
import com.folium.reader.core.ink.StrokeId
import kotlin.math.roundToInt

/** [SheetTextRecordCodec]'s own limit, mirrored here since that constant is `internal` to `:reader-core` and not visible across the module boundary. */
internal const val TEXT_MAX_BYTES: Int = 64 * 1024

/** Where a session's own box sits and how it is styled: fixed for the whole session, since a style or colour change closes and reopens a fresh one rather than restyling live. */
internal data class TextEditingPlacement(
    val topLeft: SheetPoint,
    val widthSheetUnits: Float,
    val style: SheetTextStyle,
    val colorArgb: Int
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

    /** Starts a session over [original] (`null` for a brand-new box), styled and coloured per [placement], scaled by [viewport]; [displayColorArgb] is [placement]'s own colour resolved against the live theme, since the editor shows a concrete colour on screen while [placement.colorArgb] may still be a THEME sentinel. */
    fun open(original: SheetTextBox?, placement: TextEditingPlacement, viewport: SheetViewport, displayColorArgb: Int) {
        discardView()

        this.original = original
        this.placement = placement

        val field = EditText(host.context).apply {
            setBackgroundColor(0)
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            gravity = Gravity.TOP or Gravity.START
            isSingleLine = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters = arrayOf(Utf8ByteLimitInputFilter(TEXT_MAX_BYTES))
            typeface = layoutEngine.typefaceFor(placement.style)
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
        val lineSpacingAddViewPx = layoutEngine.lineSpacingAddDesignPx(active.style) * designPxToViewPx

        // The host is a FrameLayout, which measures children through MarginLayoutParams: keep the params it generated at addView and only change their width.
        field.layoutParams = field.layoutParams.apply { width = widthViewPx }
        field.x = topLeftViewPx.x
        field.y = topLeftViewPx.y
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, layoutEngine.textSizeDesignPx(active.style) * designPxToViewPx)
        field.setLineSpacing(lineSpacingAddViewPx, 1f)
        field.setPadding(0, lineSpacingAddViewPx.roundToInt().coerceAtLeast(0), 0, 0)
        field.requestLayout()
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
            style = active.style,
            colorArgb = active.colorArgb,
            topLeft = active.topLeft,
            widthSheetUnits = active.widthSheetUnits
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

    private fun buildTextBox(text: String, placement: TextEditingPlacement, id: StrokeId, sequence: Long): SheetTextBox {
        val measured = layoutEngine.layout(text, placement.style, placement.widthSheetUnits, placement.colorArgb)
        return SheetTextBox(
            id = id,
            topLeft = placement.topLeft,
            widthSheetUnits = placement.widthSheetUnits,
            heightSheetUnits = measured.heightSheetUnits,
            text = text,
            style = placement.style,
            colorArgb = placement.colorArgb,
            sequence = sequence
        )
    }

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
