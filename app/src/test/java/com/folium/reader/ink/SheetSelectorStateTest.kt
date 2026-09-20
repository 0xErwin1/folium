package com.folium.reader.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SheetSelectorStateTest {

    private val initial = SheetSelectorState(activeTool = SheetRailTool.PEN, openPanel = null)

    @Test fun `every rail tool has a selector panel`() {
        assertEquals(SheetSelectorPanel.VIEW, SheetRailTool.VIEW.selectorPanel())
        assertEquals(SheetSelectorPanel.PEN, SheetRailTool.PEN.selectorPanel())
        assertEquals(SheetSelectorPanel.ERASER, SheetRailTool.ERASER.selectorPanel())
    }

    @Test fun `tapping the already-active pen cell opens its panel`() {
        val state = initial.reduce(SheetSelectorEvent.ToolTapped(SheetRailTool.PEN))
        assertEquals(SheetSelectorPanel.PEN, state.openPanel)
        assertEquals(SheetRailTool.PEN, state.activeTool)
    }

    @Test fun `tapping the already-open pen cell again closes it`() {
        val opened = initial.reduce(SheetSelectorEvent.ToolTapped(SheetRailTool.PEN))
        val closed = opened.reduce(SheetSelectorEvent.ToolTapped(SheetRailTool.PEN))
        assertNull(closed.openPanel)
    }

    @Test fun `tapping a different tool switches the active tool without opening a panel`() {
        val state = initial.reduce(SheetSelectorEvent.ToolTapped(SheetRailTool.VIEW))
        assertEquals(SheetRailTool.VIEW, state.activeTool)
        assertNull(state.openPanel)
    }

    @Test fun `tapping the already-active eraser cell opens its panel`() {
        val eraserActive = SheetSelectorState(activeTool = SheetRailTool.ERASER, openPanel = null)
        val state = eraserActive.reduce(SheetSelectorEvent.ToolTapped(SheetRailTool.ERASER))
        assertEquals(SheetSelectorPanel.ERASER, state.openPanel)
        assertEquals(SheetRailTool.ERASER, state.activeTool)
    }

    @Test fun `tapping the already-active view cell opens its panel`() {
        val viewActive = SheetSelectorState(activeTool = SheetRailTool.VIEW, openPanel = null)
        val state = viewActive.reduce(SheetSelectorEvent.ToolTapped(SheetRailTool.VIEW))
        assertEquals(SheetSelectorPanel.VIEW, state.openPanel)
        assertEquals(SheetRailTool.VIEW, state.activeTool)
    }

    @Test fun `PUNTA always makes PEN active and opens its panel`() {
        val state = SheetSelectorState(activeTool = SheetRailTool.VIEW, openPanel = null)
            .reduce(SheetSelectorEvent.PuntaTapped)
        assertEquals(SheetRailTool.PEN, state.activeTool)
        assertEquals(SheetSelectorPanel.PEN, state.openPanel)
    }

    @Test fun `an outside tap, a back press or a started stroke each close an open panel without switching tools`() {
        val opened = initial.reduce(SheetSelectorEvent.ToolTapped(SheetRailTool.PEN))

        listOf(SheetSelectorEvent.OutsideTapped, SheetSelectorEvent.BackPressed, SheetSelectorEvent.StrokeStarted).forEach { event ->
            val state = opened.reduce(event)
            assertNull(state.openPanel)
            assertEquals(SheetRailTool.PEN, state.activeTool)
        }
    }
}
