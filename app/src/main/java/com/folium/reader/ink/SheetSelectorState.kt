package com.folium.reader.ink

/**
 * The selector panel a [SheetRailTool] opens when its own rail cell is tapped while already active,
 * or `null` for a tool with no panel. A closed enum rather than reusing [SheetRailTool] itself, so a
 * future panel that is not a 1:1 match with a tool — none exists today — is not foreclosed by this
 * type.
 */
internal enum class SheetSelectorPanel { VIEW, PEN, HIGHLIGHT, SHAPE, ERASER }

internal fun SheetRailTool.selectorPanel(): SheetSelectorPanel? = when (this) {
    SheetRailTool.VIEW -> SheetSelectorPanel.VIEW
    SheetRailTool.PEN -> SheetSelectorPanel.PEN
    SheetRailTool.HIGHLIGHT -> SheetSelectorPanel.HIGHLIGHT
    SheetRailTool.SHAPE -> SheetSelectorPanel.SHAPE
    SheetRailTool.ERASER -> SheetSelectorPanel.ERASER
}

/**
 * Which tool the rail currently highlights, which selector panel, if any, is open over it, and
 * whether the rail itself is collapsed to [SheetRailHiddenTab] (`nota-t-oculta`). [railHidden] starts
 * from the same persisted [PenSettings.railHidden] a caller reads at composition; this state then
 * owns it going forward so hiding and showing can react atomically with the panel it closes.
 */
internal data class SheetSelectorState(
    val activeTool: SheetRailTool,
    val openPanel: SheetSelectorPanel?,
    val railHidden: Boolean = false
)

/**
 * Every input the rail's selector panel reacts to (`rail-spec.md` 2.1): a rail cell tapped, a tap
 * outside the panel, the system back gesture, a stroke or erase starting on the drawing surface, or
 * the rail being hidden or shown (`nota-t-oculta`).
 */
internal sealed interface SheetSelectorEvent {
    data class ToolTapped(val tool: SheetRailTool) : SheetSelectorEvent
    data object OutsideTapped : SheetSelectorEvent
    data object BackPressed : SheetSelectorEvent
    data object StrokeStarted : SheetSelectorEvent
    data object RailHidden : SheetSelectorEvent
    data object RailShown : SheetSelectorEvent
}

/**
 * Advances [this] state by one [SheetSelectorEvent], with no side effect of its own: a panel opens
 * only by tapping the tool that is already active, and it closes the same way regardless of
 * which of the four closing events fired (`rail-spec.md` task instructions, panel anatomy). Hiding the
 * rail also closes any open panel, since the panel anchors to a rail cell that is about to disappear;
 * showing it back leaves the active tool and the (already closed) panel untouched (`nota-t-oculta`:
 * "Para cambiar de herramienta hay que abrirla").
 */
internal fun SheetSelectorState.reduce(event: SheetSelectorEvent): SheetSelectorState = when (event) {
    is SheetSelectorEvent.ToolTapped -> when {
        event.tool != activeTool -> copy(activeTool = event.tool, openPanel = null)
        event.tool.selectorPanel() == null -> this
        openPanel == event.tool.selectorPanel() -> copy(openPanel = null)
        else -> copy(openPanel = event.tool.selectorPanel())
    }
    SheetSelectorEvent.OutsideTapped -> copy(openPanel = null)
    SheetSelectorEvent.BackPressed -> copy(openPanel = null)
    SheetSelectorEvent.StrokeStarted -> copy(openPanel = null)
    SheetSelectorEvent.RailHidden -> copy(railHidden = true, openPanel = null)
    SheetSelectorEvent.RailShown -> copy(railHidden = false)
}
