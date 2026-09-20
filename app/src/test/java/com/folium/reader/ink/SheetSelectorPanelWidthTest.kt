package com.folium.reader.ink

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetSelectorPanelWidthTest {

    @Test fun `a wide pane caps the panel at 320dp`() {
        assertEquals(320.dp, sheetSelectorPanelWidth(paneWidth = 1200.dp, railInset = 24.dp, railBreadth = 80.dp))
    }

    @Test fun `a narrow pane clamps the panel to the room left of the rail`() {
        assertEquals(156.dp, sheetSelectorPanelWidth(paneWidth = 296.dp, railInset = 24.dp, railBreadth = 80.dp))
    }

    @Test fun `a pane no wider than the rail plus its insets leaves no room for a panel`() {
        assertEquals(0.dp, sheetSelectorPanelWidth(paneWidth = 90.dp, railInset = 24.dp, railBreadth = 80.dp))
    }

    @Test fun `the compact panel spans the pane minus a margin on each side`() {
        assertEquals(368.dp, sheetSelectorCompactPanelWidth(paneWidth = 400.dp))
    }

    @Test fun `a compact pane narrower than its own margins leaves no room for a panel`() {
        assertEquals(0.dp, sheetSelectorCompactPanelWidth(paneWidth = 20.dp))
    }
}
