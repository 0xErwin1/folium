package com.folium.reader.ink

import com.folium.reader.core.ink.SheetTextFont
import com.folium.reader.core.ink.SheetTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPanelModeTest {

    private val attributes = SelectedTextAttributes(
        font = SheetTextFont.MONO,
        sizePt = 20f,
        style = SheetTextStyle.BOLD,
        colorArgb = 0xFF000000.toInt()
    )

    @Test fun `no selection and no session under edit falls back to defaults`() {
        val mode = textPanelMode(activeTool = SheetRailTool.TEXT, selectionAttributes = null, editingAttributes = null)

        assertEquals(TextPanelMode.Defaults, mode)
    }

    @Test fun `the select tool with a selection scopes to the selected boxes`() {
        val mode = textPanelMode(activeTool = SheetRailTool.SELECT, selectionAttributes = attributes, editingAttributes = null)

        assertTrue(mode is TextPanelMode.Selection)
        assertEquals(attributes, (mode as TextPanelMode.Selection).attributes)
    }

    @Test fun `the text tool editing an existing box scopes to that box`() {
        val mode = textPanelMode(activeTool = SheetRailTool.TEXT, selectionAttributes = null, editingAttributes = attributes)

        assertTrue(mode is TextPanelMode.Editing)
        assertEquals(attributes, (mode as TextPanelMode.Editing).attributes)
    }

    @Test fun `the text tool placing a brand-new box falls back to defaults`() {
        val mode = textPanelMode(activeTool = SheetRailTool.TEXT, selectionAttributes = null, editingAttributes = null)

        assertEquals(TextPanelMode.Defaults, mode)
    }

    @Test fun `editing attributes are ignored while the select tool is active`() {
        val mode = textPanelMode(activeTool = SheetRailTool.SELECT, selectionAttributes = null, editingAttributes = attributes)

        assertEquals(TextPanelMode.Defaults, mode)
    }

    @Test fun `selection attributes are ignored while the text tool is active`() {
        val mode = textPanelMode(activeTool = SheetRailTool.TEXT, selectionAttributes = attributes, editingAttributes = null)

        assertEquals(TextPanelMode.Defaults, mode)
    }
}
