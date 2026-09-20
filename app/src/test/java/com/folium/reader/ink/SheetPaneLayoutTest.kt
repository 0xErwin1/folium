package com.folium.reader.ink

import androidx.compose.ui.unit.dp
import com.folium.reader.ui.FoliumWidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetPaneLayoutTest {

    @Test fun `a phone lays the tool rail out as a bottom row`() {
        assertEquals(SheetPaneRailOrientation.ROW, sheetPaneRailOrientation(FoliumWidthClass.COMPACT))
    }

    @Test fun `a medium window lays the tool rail out as a left column`() {
        assertEquals(SheetPaneRailOrientation.COLUMN, sheetPaneRailOrientation(FoliumWidthClass.MEDIUM))
    }

    @Test fun `an expanded window lays the tool rail out as a left column`() {
        assertEquals(SheetPaneRailOrientation.COLUMN, sheetPaneRailOrientation(FoliumWidthClass.EXPANDED))
    }

    @Test fun `the rail lists only the tools with a working engine, in the design's own order`() {
        assertEquals(listOf(SheetRailTool.VIEW, SheetRailTool.PEN, SheetRailTool.ERASER), SheetRailTools)
    }

    @Test fun `every rail tool's test tag is distinct`() {
        val tags = SheetRailTools.map { it.testTag }
        assertEquals(tags.distinct(), tags)
    }

    @Test fun `pen and eraser drive the drawing surface, view has no surface behavior yet`() {
        assertEquals(InkSurfaceTool.PEN, SheetRailTool.PEN.toSurfaceTool())
        assertEquals(InkSurfaceTool.ERASER, SheetRailTool.ERASER.toSurfaceTool())
        assertEquals(null, SheetRailTool.VIEW.toSurfaceTool())
    }

    @Test fun `a left column rail shows the foot's PUNTA cell`() {
        assertTrue(sheetRailShowsPunta(SheetPaneRailOrientation.COLUMN))
    }

    @Test fun `a bottom row rail has no foot, so no PUNTA cell`() {
        assertFalse(sheetRailShowsPunta(SheetPaneRailOrientation.ROW))
    }

    @Test fun `PUNTA's width bar tracks the pen's own width, floored and capped`() {
        assertEquals(2.dp, puntaWidthBarHeight(2.dp))
        assertEquals(7.dp, puntaWidthBarHeight(7.dp))
        assertEquals(1.dp, puntaWidthBarHeight(0.dp))
        assertEquals(8.dp, puntaWidthBarHeight(20.dp))
    }

    @Test fun `the three pen widths carry the sheet drawing surface's own values in order`() {
        assertEquals(
            listOf(InkPenWidths.THIN_SHEET_UNITS, InkPenWidths.MEDIUM_SHEET_UNITS, InkPenWidths.THICK_SHEET_UNITS),
            SheetPaneWidthOption.entries.map { it.sheetUnits }
        )
    }

    @Test fun `each pen width's line grows thicker with the width it represents`() {
        assertEquals(2.dp, SheetPaneWidthOption.THIN.lineThickness)
        assertEquals(4.dp, SheetPaneWidthOption.MEDIUM.lineThickness)
        assertEquals(7.dp, SheetPaneWidthOption.THICK.lineThickness)
    }

    @Test fun `every width option's test tag is distinct`() {
        val tags = SheetPaneWidthOption.entries.map { it.testTag }
        assertEquals(tags.distinct(), tags)
    }
}
