package com.folium.reader.ink

import com.folium.reader.core.ink.SheetTextAlignment
import com.folium.reader.core.ink.SheetTextBox
import com.folium.reader.core.ink.SheetTextFont
import com.folium.reader.core.ink.SheetTextStyle

/**
 * The attributes the selection-scoped Text panel shows for the text box(es) held by the SELECT
 * tool's own current selection: [font], [style], [alignment] and [colorArgb] are `null` once the
 * selected boxes disagree on that one attribute, so the panel shows no option selected for it rather
 * than picking one box's own value arbitrarily. [sizePt] always holds the first selected box's own
 * value regardless of whether the others agree — a stepper has no way to show "no value selected" the
 * way a row of discrete options does.
 */
internal data class SelectedTextAttributes(
    val font: SheetTextFont?,
    val sizePt: Float,
    val style: SheetTextStyle?,
    val colorArgb: Int?,
    val alignment: SheetTextAlignment? = null
)

/**
 * [boxes]' own [SelectedTextAttributes], or `null` for an empty selection — the selection-scoped Text
 * panel never opens without at least one text box selected, so a caller only ever sees `null` by
 * construction rather than as a state it has to render.
 */
internal fun selectedTextAttributesOf(boxes: List<SheetTextBox>): SelectedTextAttributes? {
    if (boxes.isEmpty()) return null

    return SelectedTextAttributes(
        font = boxes.map { it.font }.distinct().singleOrNull(),
        sizePt = boxes.first().sizePt,
        style = boxes.map { it.style }.distinct().singleOrNull(),
        colorArgb = boxes.map { it.colorArgb }.distinct().singleOrNull(),
        alignment = boxes.map { it.alignment }.distinct().singleOrNull()
    )
}
