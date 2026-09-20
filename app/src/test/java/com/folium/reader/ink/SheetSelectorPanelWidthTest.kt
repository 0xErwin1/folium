package com.folium.reader.ink

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetSelectorPanelWidthTest {

    @Test fun `a wide pane caps the panel at 400dp with the full rail shown`() {
        assertEquals(400.dp, sheetSelectorPanelWidth(paneWidth = 1200.dp, railBreadth = 80.dp))
    }

    @Test fun `a narrow pane clamps the panel to the room left of the rail`() {
        assertEquals(88.dp, sheetSelectorPanelWidth(paneWidth = 180.dp, railBreadth = 80.dp))
    }

    @Test fun `a pane no wider than the rail plus its connector leaves no room for a panel`() {
        assertEquals(0.dp, sheetSelectorPanelWidth(paneWidth = 90.dp, railBreadth = 80.dp))
    }

    @Test fun `the hidden tab's own narrower breadth leaves more room for the panel than the full rail`() {
        val withTab = sheetSelectorPanelWidth(paneWidth = 200.dp, railBreadth = railAnchorBreadth(railHidden = true))
        val withRail = sheetSelectorPanelWidth(paneWidth = 200.dp, railBreadth = railAnchorBreadth(railHidden = false))

        assertEquals(142.dp, withTab)
        assertEquals(108.dp, withRail)
    }

    @Test fun `the compact panel spans the pane minus a margin on each side`() {
        assertEquals(368.dp, sheetSelectorCompactPanelWidth(paneWidth = 400.dp))
    }

    @Test fun `a compact pane narrower than its own margins leaves no room for a panel`() {
        assertEquals(0.dp, sheetSelectorCompactPanelWidth(paneWidth = 20.dp))
    }
}
