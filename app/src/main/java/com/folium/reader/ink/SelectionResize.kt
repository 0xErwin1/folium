package com.folium.reader.ink

import com.folium.reader.core.ink.SelectionResizeScale
import com.folium.reader.core.ink.SheetItem
import com.folium.reader.core.ink.StrokeId
import com.folium.reader.core.ink.scaleStrokes
import com.folium.reader.core.ink.scaleTextBoxPosition

/**
 * Whether a SELECT-tool resize drag against [items] changes a single text box's own width, rather
 * than scaling the selection as a whole: true only when [items] holds exactly one item and that item
 * is a [SheetItem.Text]. [InkDrawingSurface] resizes that one box's own width through
 * [com.folium.reader.core.ink.textBoxWidthResize] instead of calling [scaleSelectionItems] for it.
 */
internal fun isSingleTextBoxResize(items: List<SheetItem>): Boolean =
    items.size == 1 && items.single() is SheetItem.Text

/**
 * [items] scaled by [scale], each item's own kind mapped through its own rule: a stroke through
 * [scaleStrokes], and a text box through [scaleTextBoxPosition] — only its own top-left moves, its
 * width, height, text and style all carried over unchanged, since a resize dragged against a
 * selection wider than one text box repositions the box rather than rewrapping or resizing its text
 * (`rail-spec.md` task instructions: "text size and box width unchanged"). Never called when
 * [isSingleTextBoxResize] is true. A fresh id from [newId] and a fresh sequence from [newSequence] are
 * drawn once per item, in [items]' own order, so [items] must already be sorted by ascending
 * [SheetItem.sequence] for the result to keep the same relative z-order.
 */
internal fun scaleSelectionItems(
    items: List<SheetItem>,
    scale: SelectionResizeScale,
    newId: () -> StrokeId,
    newSequence: () -> Long
): List<SheetItem> = items.map { item ->
    when (item) {
        is SheetItem.Stroke ->
            SheetItem.Stroke(scaleStrokes(listOf(item.stroke), scale.anchor, scale.scaleX, scale.scaleY, newId, newSequence).single())
        is SheetItem.Text ->
            SheetItem.Text(scaleTextBoxPosition(item.textBox, scale.anchor, scale.scaleX, scale.scaleY, newId, newSequence))
    }
}
