package com.folium.reader.reader

import androidx.compose.ui.unit.dp
import com.folium.reader.ink.SheetDockInsets
import com.folium.reader.ink.SheetPaneRailOrientation
import com.folium.reader.ui.FoliumWidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the reader draws its one tool rail: only while the unit on screen shows a sheet, as a column
 * beside the content on a tablet and as a row above the bottom chrome on a phone, with the sheet cells
 * inset to line up with it.
 */
class ReaderSheetRailTest {

    @Test fun `a unit of book pages alone shows no rail`() {
        FoliumWidthClass.entries.forEach { widthClass ->
            assertNull(readerSheetRail(widthClass, sheetCurrent = false, toolsAvailable = true))
        }
    }

    @Test fun `a reader with no sheet tools shows no rail even on a sheet`() {
        assertNull(readerSheetRail(FoliumWidthClass.EXPANDED, sheetCurrent = true, toolsAvailable = false))
    }

    @Test fun `a sheet on a tablet-width window shows a column rail`() {
        assertEquals(SheetPaneRailOrientation.COLUMN, readerSheetRail(FoliumWidthClass.MEDIUM, sheetCurrent = true, toolsAvailable = true))
        assertEquals(SheetPaneRailOrientation.COLUMN, readerSheetRail(FoliumWidthClass.EXPANDED, sheetCurrent = true, toolsAvailable = true))
    }

    @Test fun `a sheet on a phone-width window shows a bottom row`() {
        assertEquals(SheetPaneRailOrientation.ROW, readerSheetRail(FoliumWidthClass.COMPACT, sheetCurrent = true, toolsAvailable = true))
    }

    @Test fun `a column rail keeps 24dp clear of both chrome bars and leaves the pager its full height`() {
        assertEquals(
            SheetDockInsets(railTop = 80.dp, railBottom = 96.dp, contentTop = 0.dp, contentBottom = 0.dp, rowBottom = 0.dp),
            readerSheetDockInsets(SheetPaneRailOrientation.COLUMN, chromeTop = 56.dp, chromeBottom = 72.dp)
        )
    }

    @Test fun `a compact row sits right above the bottom chrome`() {
        assertEquals(
            SheetDockInsets(railTop = 0.dp, railBottom = 0.dp, contentTop = 0.dp, contentBottom = 0.dp, rowBottom = 72.dp),
            readerSheetDockInsets(SheetPaneRailOrientation.ROW, chromeTop = 56.dp, chromeBottom = 72.dp)
        )
    }

    @Test fun `no rail insets nothing`() {
        assertEquals(SheetDockInsets(0.dp, 0.dp, 0.dp, 0.dp, 0.dp), readerSheetDockInsets(null, chromeTop = 56.dp, chromeBottom = 72.dp))
    }

    @Test fun `beside a column rail a sheet cell lines up with the rail, 24dp inside both chrome bars`() {
        assertEquals(
            SheetCellInsets(topPx = 148f, bottomPx = 188f),
            sheetCellInsets(100f, 140f, cellShowsSheet = true, rail = SheetPaneRailOrientation.COLUMN, bodyPaddingPx = 48f)
        )
    }

    @Test fun `above a compact row a sheet cell keeps clear of the top chrome only`() {
        assertEquals(
            SheetCellInsets(topPx = 100f, bottomPx = 0f),
            sheetCellInsets(100f, 140f, cellShowsSheet = true, rail = SheetPaneRailOrientation.ROW, bodyPaddingPx = 48f)
        )
    }

    @Test fun `a book page beside a column rail still keeps the whole page area`() {
        assertEquals(
            SheetCellInsets(0f, 0f),
            sheetCellInsets(100f, 140f, cellShowsSheet = false, rail = SheetPaneRailOrientation.COLUMN, bodyPaddingPx = 48f)
        )
    }
}
