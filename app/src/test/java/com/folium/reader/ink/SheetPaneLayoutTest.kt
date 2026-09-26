package com.folium.reader.ink

import com.folium.reader.ui.FoliumWidthClass
import org.junit.Assert.assertEquals
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
            listOf(
                SheetRailTool.VIEW, SheetRailTool.PEN, SheetRailTool.HIGHLIGHT, SheetRailTool.TEXT, SheetRailTool.SHAPE,
                SheetRailTool.SELECT, SheetRailTool.ERASER
            ),
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
        assertEquals(InkSurfaceTool.SELECT, SheetRailTool.SELECT.toSurfaceTool())
        assertEquals(InkSurfaceTool.TEXT, SheetRailTool.TEXT.toSurfaceTool())
    }
}
