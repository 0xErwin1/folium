package com.folium.reader.ink

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What a sheet's tool rail and its drawing surface share: the active tool, the open selector panel
 * and whether the rail is hidden, plus the live surface and what it last reported that the selector
 * panels read back. Hoisted out of [SheetPane] so a host can draw the rail beside the pane rather than
 * inside it — the reader keeps one rail for the whole screen and hands the same instance to the
 * sheet's pane — while the sheet screen passes its own to both.
 *
 * [railHidden] seeds the rail's hidden state once; hiding and showing then run through this holder.
 * One instance belongs to one open sheet: a host makes a new one for every sheet it opens, so every
 * sheet starts on the pen with no panel open, as it always has.
 */
@Stable
class SheetTools(railHidden: Boolean = false) {
    internal var selectorState by mutableStateOf(
        SheetSelectorState(activeTool = SheetRailTool.PEN, openPanel = null, railHidden = railHidden)
    )
        private set

    internal var surfaceTool by mutableStateOf(InkSurfaceTool.PEN)
        private set

    internal var surface by mutableStateOf<InkDrawingSurface?>(null)
        private set

    internal var viewport by mutableStateOf<SheetViewport?>(null)

    internal var strokeCount by mutableIntStateOf(0)

    internal var editingTextAttributes by mutableStateOf<SelectedTextAttributes?>(null)

    internal fun reduce(event: SheetSelectorEvent) {
        selectorState = selectorState.reduce(event)
    }

    /** A rail cell was tapped: selects [tool], or opens or closes its panel when it is already active. */
    internal fun toolTapped(tool: SheetRailTool) {
        reduce(SheetSelectorEvent.ToolTapped(tool))
        surfaceTool = tool.toSurfaceTool()
    }

    internal fun bind(bound: InkDrawingSurface?) {
        surface = bound
    }
}
