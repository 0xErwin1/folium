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
        assertEquals(
            listOf(SheetRailTool.VIEW, SheetRailTool.PEN, SheetRailTool.HIGHLIGHT, SheetRailTool.ERASER),
            SheetRailTools
        )
    }

    @Test fun `every rail tool's test tag is distinct`() {
        val tags = SheetRailTools.map { it.testTag }
        assertEquals(tags.distinct(), tags)
    }

    @Test fun `each rail tool drives its own drawing surface tool`() {
        assertEquals(InkSurfaceTool.PEN, SheetRailTool.PEN.toSurfaceTool())
        assertEquals(InkSurfaceTool.HIGHLIGHTER, SheetRailTool.HIGHLIGHT.toSurfaceTool())
        assertEquals(InkSurfaceTool.ERASER, SheetRailTool.ERASER.toSurfaceTool())
        assertEquals(InkSurfaceTool.VIEW, SheetRailTool.VIEW.toSurfaceTool())
    }

    @Test fun `a left column rail shows the foot's PUNTA cell`() {
        assertTrue(sheetRailShowsPunta(SheetPaneRailOrientation.COLUMN))
    }

    @Test fun `a bottom row rail has no foot, so no PUNTA cell`() {
        assertFalse(sheetRailShowsPunta(SheetPaneRailOrientation.ROW))
    }

    @Test fun `PUNTA's width bar tracks the pen's own width, floored and capped`() {
        assertEquals(2.dp, puntaWidthBarHeight(0.5f))
        assertEquals(8.dp, puntaWidthBarHeight(3f))
        assertEquals(1.dp, puntaWidthBarHeight(0f))
        assertEquals(8.dp, puntaWidthBarHeight(20f))
    }

    @Test fun `a column rail's body floats on the artboard's own 24dp padding and gap`() {
        val layout = sheetPaneBodyLayout(FoliumWidthClass.EXPANDED)
        assertEquals(SheetPaneBodyPadding, layout.outerPadding)
        assertEquals(SheetPaneBodyGap, layout.gap)
        assertEquals(0.dp, layout.compactRailMargin)
    }

    @Test fun `a compact row's body carries no outer padding, only its own side margin`() {
        val layout = sheetPaneBodyLayout(FoliumWidthClass.COMPACT)
        assertEquals(0.dp, layout.outerPadding)
        assertEquals(0.dp, layout.gap)
        assertEquals(CompactPanelMargin, layout.compactRailMargin)
    }
}
