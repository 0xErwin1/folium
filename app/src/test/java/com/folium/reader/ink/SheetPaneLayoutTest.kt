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
            listOf(SheetRailTool.VIEW, SheetRailTool.PEN, SheetRailTool.HIGHLIGHT, SheetRailTool.SHAPE, SheetRailTool.ERASER),
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
        assertEquals(InkSurfaceTool.SHAPE, SheetRailTool.SHAPE.toSurfaceTool())
    }

    @Test fun `a column rail's body carries no compact-row margin, since the rail is docked or floating with none of its own`() {
        val layout = sheetPaneBodyLayout(FoliumWidthClass.EXPANDED)
        assertEquals(0.dp, layout.compactRailMargin)
    }

    @Test fun `a compact row's body carries its own side margin`() {
        val layout = sheetPaneBodyLayout(FoliumWidthClass.COMPACT)
        assertEquals(CompactPanelMargin, layout.compactRailMargin)
    }
}
