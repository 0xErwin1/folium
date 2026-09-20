package com.folium.reader.ink

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [railAnchorBreadth], [railAnchorCellTopOffset] and [railAnchorConnectorTopOffset] anchor a selector
 * panel to whichever rail cell is active, in both of the rail's own states: docked and full, or
 * collapsed to [SheetRailHiddenTab]'s own tool cell. Pure functions of (rail visible/hidden, active
 * tool) alone, so they never read a live composition and stay exhaustively JVM-testable.
 */
class SheetSelectorAnchorTest {

    @Test fun `the full rail's own breadth anchors the panel when the rail is shown`() {
        assertEquals(RailBreadth, railAnchorBreadth(railHidden = false))
    }

    @Test fun `the hidden tab's own breadth anchors the panel when the rail is hidden`() {
        assertEquals(RailHiddenTabWidth, railAnchorBreadth(railHidden = true))
    }

    @Test fun `a shown rail's cell top offset follows the tool's own index in the column`() {
        assertEquals(RailColumnTopPadding, railAnchorCellTopOffset(railHidden = false, tool = SheetRailTool.VIEW))
        assertEquals(
            RailColumnTopPadding + RailColumnCellHeight + RailColumnCellGap,
            railAnchorCellTopOffset(railHidden = false, tool = SheetRailTool.PEN)
        )
        assertEquals(
            RailColumnTopPadding + (RailColumnCellHeight + RailColumnCellGap) * 4,
            railAnchorCellTopOffset(railHidden = false, tool = SheetRailTool.ERASER)
        )
    }

    @Test fun `a hidden rail's cell top offset is always the tab's own top edge, regardless of tool`() {
        SheetRailTool.entries.forEach { tool ->
            assertEquals(0.dp, railAnchorCellTopOffset(railHidden = true, tool = tool))
        }
    }

    @Test fun `a shown rail's connector anchors to the active cell's own vertical middle`() {
        val expected = RailColumnTopPadding + RailColumnCellHeight / 2

        assertEquals(expected, railAnchorConnectorTopOffset(railHidden = false, tool = SheetRailTool.VIEW))
    }

    @Test fun `a hidden rail's connector anchors to the tab's own top cell vertical middle`() {
        val expected = RailHiddenTabCellSize / 2

        SheetRailTool.entries.forEach { tool ->
            assertEquals(expected, railAnchorConnectorTopOffset(railHidden = true, tool = tool))
        }
    }
}
