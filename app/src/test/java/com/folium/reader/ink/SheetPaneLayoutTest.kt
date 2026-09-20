package com.folium.reader.ink

import androidx.compose.ui.unit.dp
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
