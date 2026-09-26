package com.folium.reader.ink

import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetTextAlignment
import com.folium.reader.core.ink.SheetTextBox
import com.folium.reader.core.ink.SheetTextFont
import com.folium.reader.core.ink.SheetTextStyle
import com.folium.reader.core.ink.StrokeId

/**
 * How far a new text box's own right edge sits from the sheet's own right edge (`x = 1`), in
 * millimetres — a margin rather than running flush to the paper's own edge, the same idea
 * [SELECTION_COPY_OFFSET_MM] already expresses in [InkDrawingSurface] for a copied selection's own
 * offset.
 */
internal const val NEW_TEXT_BOX_RIGHT_MARGIN_MM: Float = 12f

/** The narrowest a new text box may start at, in millimetres, so a tap near the sheet's own right edge never places an unusably thin box. */
internal const val NEW_TEXT_BOX_MIN_WIDTH_MM: Float = 40f

/** [newTextBoxGeometry]'s own result: a fresh box's own left edge and width, in sheet units. */
internal data class NewTextBoxGeometry(val left: Float, val widthSheetUnits: Float)

/**
 * The left edge and width a new text box starts at for a tap at [tapXSheetUnits]: the left edge sits
 * at the tap itself and the box runs to the sheet's own right edge minus [rightMarginSheetUnits],
 * unless that leaves less than [minWidthSheetUnits], in which case the box is shifted left just enough
 * to keep its own minimum width, its right edge still at the same margin from the sheet's own edge.
 */
internal fun newTextBoxGeometry(
    tapXSheetUnits: Float,
    rightMarginSheetUnits: Float,
    minWidthSheetUnits: Float
): NewTextBoxGeometry {
    val right = 1f - rightMarginSheetUnits
    val naturalWidth = right - tapXSheetUnits

    return if (naturalWidth >= minWidthSheetUnits) {
        NewTextBoxGeometry(left = tapXSheetUnits, widthSheetUnits = naturalWidth)
    } else {
        val shiftedLeft = (right - minWidthSheetUnits).coerceAtLeast(0f)
        NewTextBoxGeometry(left = shiftedLeft, widthSheetUnits = right - shiftedLeft)
    }
}

/** [topSheetUnits] snapped down onto [SheetRuleGrid]'s own grid, a new text box's own top edge. */
internal fun snappedTextBoxTop(topSheetUnits: Float): Float = SheetRuleGrid.snappedDown(topSheetUnits)

/** [newTextBoxPlacement]'s result: a fresh box's top-left corner and width, in the surface's ink units. */
internal data class NewTextBoxPlacement(val topLeft: SheetPoint, val widthSheetUnits: Float)

/**
 * Where a new text box opens for a tap at [tap] on a surface drawing in [mode]. Its left edge and
 * width follow [newTextBoxGeometry], with the right margin and minimum width measured in [mode]'s own
 * millimetres, so on a book page they are measured against the page's printed width. Its top snaps to
 * the rule at or above the tap on a sheet ([snappedTextBoxTop]); a book page has no rules to fall on,
 * so there it stays at the tap.
 */
internal fun newTextBoxPlacement(tap: SheetPoint, mode: InkSurfaceMode): NewTextBoxPlacement {
    val geometry = newTextBoxGeometry(
        tapXSheetUnits = tap.x,
        rightMarginSheetUnits = mode.mmToUnits(NEW_TEXT_BOX_RIGHT_MARGIN_MM),
        minWidthSheetUnits = mode.mmToUnits(NEW_TEXT_BOX_MIN_WIDTH_MM)
    )
    val top = if (mode is InkSurfaceMode.Page) tap.y else snappedTextBoxTop(tap.y)

    return NewTextBoxPlacement(SheetPoint(geometry.left, top), geometry.widthSheetUnits)
}

/**
 * How far, in view pixels, the content under an open text editor has to move up so the editor's
 * bottom edge at [editorBottomPx] clears a keyboard [imeBottomPx] tall over a view [viewHeightPx]
 * tall, with [marginPx] to spare; `null` when there is no keyboard or the editor already clears it.
 */
internal fun textEditorImePanPx(editorBottomPx: Float, viewHeightPx: Float, imeBottomPx: Float, marginPx: Float): Float? {
    if (imeBottomPx <= 0f) return null

    val visibleBottomPx = viewHeightPx - imeBottomPx
    if (editorBottomPx <= visibleBottomPx) return null

    return editorBottomPx - visibleBottomPx + marginPx
}

/**
 * Whether [text] fits [maxBytes] once encoded to UTF-8: [SheetTextRecordCodec]'s own limit, checked
 * here before a keystroke ever reaches the editor rather than only once a commit is attempted, so a
 * refused character is never briefly shown and then rejected. A UTF-16 surrogate pair still counts as
 * whatever its own UTF-8 encoding is (up to four bytes), never as two independent code units.
 */
internal fun fitsUtf8ByteLimit(text: CharSequence, maxBytes: Int): Boolean =
    utf8ByteCount(text) <= maxBytes

/** The number of UTF-8 bytes [text] would encode to, without allocating the encoded bytes themselves. */
internal fun utf8ByteCount(text: CharSequence): Int {
    var count = 0
    var index = 0
    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        count += when {
            codePoint <= 0x7F -> 1
            codePoint <= 0x7FF -> 2
            codePoint <= 0xFFFF -> 3
            else -> 4
        }
        index += Character.charCount(codePoint)
    }
    return count
}

/**
 * What committing the text editing session for [target] should do, given the text the editor holds
 * now trimmed of leading/trailing whitespace-only difference is irrelevant here — [newText] is exactly
 * what the editor holds. [target] is `null` while placing a brand-new box.
 */
internal sealed interface TextCommitDecision {
    /** The editor's own text commits nothing: a brand-new box left empty, or an edited box whose text never changed. */
    data object Noop : TextCommitDecision

    /** A brand-new, non-blank box is added. */
    data class AddNew(val text: String) : TextCommitDecision

    /** An existing box's own text, style, colour or geometry changed; it is replaced by a fresh one. */
    data class Replace(val original: SheetTextBox, val text: String) : TextCommitDecision

    /** An existing box was edited down to blank text; it is removed outright. */
    data class RemoveExisting(val original: SheetTextBox) : TextCommitDecision
}

/**
 * Decides [TextCommitDecision] for an editing session ending with [newText] in the editor, styled
 * [font], [sizePt], [style] and [alignment] at [colorArgb], [original] being the box being edited or
 * `null` for a brand-new placement. [newText] is compared to [original]'s own stored text verbatim:
 * trimming or otherwise normalising it is the editor's own job, not this decision's. An edit that
 * leaves the text itself unchanged but changes any one of [font], [sizePt], [style], [alignment] or
 * [colorArgb] is still a [TextCommitDecision.Replace], since the panel's own live attribute changes
 * (see [TextEditingSession.updateAttributes]) apply to the box being edited, not only to a brand-new
 * one.
 */
internal fun decideTextCommit(
    original: SheetTextBox?,
    newText: String,
    font: SheetTextFont,
    sizePt: Float,
    style: SheetTextStyle,
    colorArgb: Int,
    topLeft: SheetPoint,
    widthSheetUnits: Float,
    alignment: SheetTextAlignment = SheetTextAlignment.LEFT
): TextCommitDecision {
    val isBlank = newText.isBlank()

    if (original == null) {
        return if (isBlank) TextCommitDecision.Noop else TextCommitDecision.AddNew(newText)
    }

    if (isBlank) return TextCommitDecision.RemoveExisting(original)

    val unchanged = newText == original.text &&
        font == original.font &&
        sizePt == original.sizePt &&
        style == original.style &&
        colorArgb == original.colorArgb &&
        topLeft == original.topLeft &&
        widthSheetUnits == original.widthSheetUnits &&
        alignment == original.alignment

    return if (unchanged) TextCommitDecision.Noop else TextCommitDecision.Replace(original, newText)
}

/**
 * Builds a [SheetTextBox] at [topLeft] and [widthSheetUnits], holding [text] styled by [font],
 * [sizePt], [style] and [alignment] at [colorArgb], measuring [SheetTextBox.heightSheetUnits] through
 * [layoutEngine] — the one place a box's own attributes are applied to its text, whether a session is
 * committing a brand-new or edited box ([TextEditingSession.commit]) or, once the SELECT tool grows
 * this, an already-committed box is restyled from its own panel.
 */
internal fun buildAttributedTextBox(
    id: StrokeId,
    topLeft: SheetPoint,
    widthSheetUnits: Float,
    text: String,
    font: SheetTextFont,
    sizePt: Float,
    style: SheetTextStyle,
    colorArgb: Int,
    sequence: Long,
    layoutEngine: TextLayoutEngine,
    alignment: SheetTextAlignment = SheetTextAlignment.LEFT
): SheetTextBox {
    val measured = layoutEngine.layout(text, font, sizePt, style, widthSheetUnits, colorArgb, alignment)
    return SheetTextBox(
        id = id,
        topLeft = topLeft,
        widthSheetUnits = widthSheetUnits,
        heightSheetUnits = measured.heightSheetUnits,
        text = text,
        font = font,
        sizePt = sizePt,
        style = style,
        colorArgb = colorArgb,
        sequence = sequence,
        alignment = alignment
    )
}
