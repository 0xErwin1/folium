package com.folium.reader.ink

import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetTextBox

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
 * [style] at [colorArgb], [original] being the box being edited or `null` for a brand-new placement.
 * [newText] is compared to [original]'s own stored text verbatim: trimming or otherwise normalising it
 * is the editor's own job, not this decision's.
 */
internal fun decideTextCommit(
    original: SheetTextBox?,
    newText: String,
    style: com.folium.reader.core.ink.SheetTextStyle,
    colorArgb: Int,
    topLeft: SheetPoint,
    widthSheetUnits: Float
): TextCommitDecision {
    val isBlank = newText.isBlank()

    if (original == null) {
        return if (isBlank) TextCommitDecision.Noop else TextCommitDecision.AddNew(newText)
    }

    if (isBlank) return TextCommitDecision.RemoveExisting(original)

    val unchanged = newText == original.text &&
        style == original.style &&
        colorArgb == original.colorArgb &&
        topLeft == original.topLeft &&
        widthSheetUnits == original.widthSheetUnits

    return if (unchanged) TextCommitDecision.Noop else TextCommitDecision.Replace(original, newText)
}
