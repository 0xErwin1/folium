package com.folium.reader.ink

/**
 * The selector panel a [SheetRailTool] opens when its own rail cell is tapped while already active,
 * or `null` for a tool with no panel yet (ERASER has no panel yet, `rail-spec.md` task instructions).
 * A closed enum rather than reusing [SheetRailTool] itself, so a future panel that is not a 1:1 match
 * with a tool — none exists today — is not foreclosed by this type.
 */
internal enum class SheetSelectorPanel { VIEW, PEN }

internal fun SheetRailTool.selectorPanel(): SheetSelectorPanel? = when (this) {
    SheetRailTool.VIEW -> SheetSelectorPanel.VIEW
    SheetRailTool.PEN -> SheetSelectorPanel.PEN
    SheetRailTool.ERASER -> null
}

/** Which tool the rail currently highlights, and which selector panel, if any, is open over it. */
internal data class SheetSelectorState(val activeTool: SheetRailTool, val openPanel: SheetSelectorPanel?)

/**
 * Every input the rail's selector panel reacts to (`rail-spec.md` 2.1): a rail cell tapped, the
 * PUNTA foot cell tapped, a tap outside the panel, the system back gesture, or a stroke or erase
 * starting on the drawing surface.
 */
internal sealed interface SheetSelectorEvent {
    data class ToolTapped(val tool: SheetRailTool) : SheetSelectorEvent
    data object PuntaTapped : SheetSelectorEvent
    data object OutsideTapped : SheetSelectorEvent
    data object BackPressed : SheetSelectorEvent
    data object StrokeStarted : SheetSelectorEvent
}

/**
 * Advances [this] state by one [SheetSelectorEvent], with no side effect of its own: a panel opens
 * only by tapping the tool that is already active, or PUNTA, and it closes the same way regardless of
 * which of the four closing events fired (`rail-spec.md` task instructions, panel anatomy).
 */
internal fun SheetSelectorState.reduce(event: SheetSelectorEvent): SheetSelectorState = when (event) {
    is SheetSelectorEvent.ToolTapped -> when {
        event.tool != activeTool -> copy(activeTool = event.tool, openPanel = null)
        event.tool.selectorPanel() == null -> this
        openPanel == event.tool.selectorPanel() -> copy(openPanel = null)
        else -> copy(openPanel = event.tool.selectorPanel())
    }
    SheetSelectorEvent.PuntaTapped -> copy(activeTool = SheetRailTool.PEN, openPanel = SheetSelectorPanel.PEN)
    SheetSelectorEvent.OutsideTapped -> copy(openPanel = null)
    SheetSelectorEvent.BackPressed -> copy(openPanel = null)
    SheetSelectorEvent.StrokeStarted -> copy(openPanel = null)
}
