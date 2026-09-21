package com.folium.reader.ink

/** One action the SELECT tool's own selection menu offers (`rail-spec.md` 2.2, ELEGIR panel's own menu). */
enum class SelectionMenuAction { CONVERT_TO_TEXT, TEXT, COPY, DELETE }

/** One row of the selection menu: its own action, and whether it is drawn as the menu's primary item (filled ink/paper). */
data class SelectionMenuItem(val action: SelectionMenuAction, val isPrimary: Boolean)

/**
 * The selection menu's own items, in display order: [SelectionMenuAction.CONVERT_TO_TEXT] leads and
 * is the menu's only primary item once [hasConvertToTextHandler] answers true — the host has wired up
 * a [com.folium.reader.core.ink.InkTextRecognizer] seam — and is left out of the menu entirely
 * otherwise, so the item never appears while nothing can act on it. [SelectionMenuAction.TEXT] follows
 * it — leading the menu itself once there is no [SelectionMenuAction.CONVERT_TO_TEXT] — once
 * [hasTextBoxInSelection] answers true, i.e. the current selection holds at least one
 * [com.folium.reader.core.ink.SheetTextBox]; opens the text selector panel for the selected box(es)
 * rather than acting on them directly, so it is never primary itself.
 */
fun selectionMenuItems(hasConvertToTextHandler: Boolean, hasTextBoxInSelection: Boolean = false): List<SelectionMenuItem> = buildList {
    if (hasConvertToTextHandler) add(SelectionMenuItem(SelectionMenuAction.CONVERT_TO_TEXT, isPrimary = true))
    if (hasTextBoxInSelection) add(SelectionMenuItem(SelectionMenuAction.TEXT, isPrimary = false))
    add(SelectionMenuItem(SelectionMenuAction.COPY, isPrimary = false))
    add(SelectionMenuItem(SelectionMenuAction.DELETE, isPrimary = false))
}

/** Where [selectionMenuPlacement] anchored the menu: its own top-left corner in view pixels, and whether it sits above the selection rather than below. */
data class SelectionMenuPlacement(val leftPx: Int, val topPx: Int, val above: Boolean)

/**
 * Anchors the selection menu under the selection's own bottom-left corner (`rail-spec.md` 2.2, ELEGIR
 * panel's own menu anatomy), offset right by [marginStartPx]; flips above the selection instead once
 * [contentHeightPx] would not fit below, and clamps the result so the menu never leaves the pane
 * horizontally or vertically. [contentWidthPx] and [contentHeightPx] are the menu's own measured size,
 * leader included, since the leader and the box are laid out as one column.
 */
fun selectionMenuPlacement(
    selectionLeftPx: Int,
    selectionTopPx: Int,
    selectionBottomPx: Int,
    paneWidthPx: Int,
    paneHeightPx: Int,
    marginStartPx: Int,
    contentWidthPx: Int,
    contentHeightPx: Int
): SelectionMenuPlacement {
    val idealLeft = selectionLeftPx + marginStartPx
    val maxLeft = (paneWidthPx - contentWidthPx).coerceAtLeast(0)
    val left = idealLeft.coerceIn(0, maxLeft)

    val fitsBelow = selectionBottomPx + contentHeightPx <= paneHeightPx
    val idealTop = if (fitsBelow) selectionBottomPx else selectionTopPx - contentHeightPx
    val maxTop = (paneHeightPx - contentHeightPx).coerceAtLeast(0)
    val top = idealTop.coerceIn(0, maxTop)

    return SelectionMenuPlacement(left, top, above = !fitsBelow)
}
