package com.folium.reader.ink

import com.folium.reader.core.ink.SheetItem
import com.folium.reader.core.ink.StrokeId
import com.folium.reader.core.ink.translateStrokes
import com.folium.reader.core.ink.translateTextBox

/**
 * Whether [items] holds at least one [SheetItem.Text]: [InkDrawingSurface.startSelectionEdit] reads
 * this to decide whether a [SelectionEditKind.Move] drag against the current selection snaps its own
 * vertical component to [SheetRuleGrid], so ink moved together with a text box stays aligned with the
 * box's own baseline rather than drifting off it.
 */
internal fun containsTextBox(items: List<SheetItem>): Boolean = items.any { it is SheetItem.Text }

/**
 * [items] moved by ([dx], [dy]) in sheet units, each item's own kind moved through [translateStrokes]
 * or [translateTextBox]: a fresh id from [newId] and a fresh sequence from [newSequence] are drawn once
 * per item, in [items]' own order, so [items] must already be sorted by ascending [SheetItem.sequence]
 * for the result to keep the same relative z-order across strokes and text boxes alike — the same
 * convention [translateStrokes] already follows for a strokes-only list.
 */
internal fun translateSelectionItems(
    items: List<SheetItem>,
    dx: Float,
    dy: Float,
    newId: () -> StrokeId,
    newSequence: () -> Long
): List<SheetItem> = items.map { item ->
    when (item) {
        is SheetItem.Stroke -> SheetItem.Stroke(translateStrokes(listOf(item.stroke), dx, dy, newId, newSequence).single())
        is SheetItem.Text -> SheetItem.Text(translateTextBox(item.textBox, dx, dy, newId, newSequence))
    }
}
