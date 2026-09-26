package com.folium.reader.ink

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetToolDockLayoutTest {

    @Test fun `a shown rail sits 24dp in from the start and leaves the content the width minus padding, rail and gap`() {
        val geometry = sheetDockColumnGeometry(availableWidth = 1000.dp, railHidden = false)

        assertEquals(24.dp, geometry.railStart)
        assertEquals(80.dp, geometry.railWidth)
        assertEquals(128.dp, geometry.contentStart)
        assertEquals(1000.dp - 24.dp - 80.dp - 24.dp - 24.dp, geometry.contentWidth)
    }

    @Test fun `a hidden rail reserves only its tab's width`() {
        val geometry = sheetDockColumnGeometry(availableWidth = 1000.dp, railHidden = true)

        assertEquals(24.dp, geometry.railStart)
        assertEquals(44.dp, geometry.railWidth)
        assertEquals(92.dp, geometry.contentStart)
        assertEquals(1000.dp - 24.dp - 44.dp - 24.dp - 24.dp, geometry.contentWidth)
    }

    @Test fun `a width narrower than the rail and its margins leaves the content no width rather than a negative one`() {
        assertEquals(0.dp, sheetDockColumnGeometry(availableWidth = 100.dp, railHidden = false).contentWidth)
    }

    @Test fun `a column rail lists every tool, then + sheet, then hide when a sheet can be created`() {
        val cells = sheetRailCells(SheetPaneRailOrientation.COLUMN, canCreateSheet = true)

        assertEquals(SheetRailTools.map { SheetRailCell.Tool(it) } + listOf(SheetRailCell.NewSheet, SheetRailCell.Hide), cells)
    }

    @Test fun `a column rail with nowhere to create a sheet leaves + sheet out`() {
        val cells = sheetRailCells(SheetPaneRailOrientation.COLUMN, canCreateSheet = false)

        assertEquals(SheetRailTools.map { SheetRailCell.Tool(it) } + SheetRailCell.Hide, cells)
    }

    @Test fun `a compact row carries only the tools`() {
        listOf(true, false).forEach { canCreateSheet ->
            assertEquals(SheetRailTools.map { SheetRailCell.Tool(it) }, sheetRailCells(SheetPaneRailOrientation.ROW, canCreateSheet))
        }
    }

    @Test fun `a compact row is its cell height plus its bottom padding and its top rule`() {
        assertEquals(48.dp + 12.dp + 1.dp, RailRowHeight)
    }

    @Test fun `the sheet screen pads its rail and its surface 24dp on every side in a column`() {
        assertEquals(SheetDockInsets(24.dp, 24.dp, 24.dp, 24.dp, 0.dp), standaloneSheetDockInsets(SheetPaneRailOrientation.COLUMN))
    }

    @Test fun `the sheet screen runs its surface down to a compact row with no inset`() {
        assertEquals(SheetDockInsets(0.dp, 0.dp, 0.dp, 0.dp, 0.dp), standaloneSheetDockInsets(SheetPaneRailOrientation.ROW))
    }
}
