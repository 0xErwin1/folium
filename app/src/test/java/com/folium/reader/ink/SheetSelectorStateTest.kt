package com.folium.reader.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SheetSelectorStateTest {

    private val initial = SheetSelectorState(activeTool = SheetRailTool.PEN, openPanel = null)

    @Test fun `every rail tool has a selector panel`() {
        assertEquals(SheetSelectorPanel.VIEW, SheetRailTool.VIEW.selectorPanel())
        assertEquals(SheetSelectorPanel.PEN, SheetRailTool.PEN.selectorPanel())
        assertEquals(SheetSelectorPanel.HIGHLIGHT, SheetRailTool.HIGHLIGHT.selectorPanel())
        assertEquals(SheetSelectorPanel.TEXT, SheetRailTool.TEXT.selectorPanel())
        assertEquals(SheetSelectorPanel.SHAPE, SheetRailTool.SHAPE.selectorPanel())
        assertEquals(SheetSelectorPanel.SELECT, SheetRailTool.SELECT.selectorPanel())
        assertEquals(SheetSelectorPanel.ERASER, SheetRailTool.ERASER.selectorPanel())
    }

    @Test fun `tapping the already-active select cell opens its panel`() {
        val selectActive = SheetSelectorState(activeTool = SheetRailTool.SELECT, openPanel = null)
        val state = selectActive.reduce(SheetSelectorEvent.ToolTapped(SheetRailTool.SELECT))
        assertEquals(SheetSelectorPanel.SELECT, state.openPanel)
        assertEquals(SheetRailTool.SELECT, state.activeTool)
    }

    @Test fun `tapping the already-active shape cell opens its panel`() {
        val shapeActive = SheetSelectorState(activeTool = SheetRailTool.SHAPE, openPanel = null)
        val state = shapeActive.reduce(SheetSelectorEvent.ToolTapped(SheetRailTool.SHAPE))
        assertEquals(SheetSelectorPanel.SHAPE, state.openPanel)
        assertEquals(SheetRailTool.SHAPE, state.activeTool)
    }

    @Test fun `tapping the already-active highlight cell opens its panel`() {
        val highlightActive = SheetSelectorState(activeTool = SheetRailTool.HIGHLIGHT, openPanel = null)
        val state = highlightActive.reduce(SheetSelectorEvent.ToolTapped(SheetRailTool.HIGHLIGHT))
        assertEquals(SheetSelectorPanel.HIGHLIGHT, state.openPanel)
        assertEquals(SheetRailTool.HIGHLIGHT, state.activeTool)
    }

    @Test fun `tapping the already-active text cell opens its panel`() {
        val textActive = SheetSelectorState(activeTool = SheetRailTool.TEXT, openPanel = null)
        val state = textActive.reduce(SheetSelectorEvent.ToolTapped(SheetRailTool.TEXT))
        assertEquals(SheetSelectorPanel.TEXT, state.openPanel)
        assertEquals(SheetRailTool.TEXT, state.activeTool)
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

    @Test fun `a selection text request opens the text panel while the select tool stays active`() {
        val selectActive = SheetSelectorState(activeTool = SheetRailTool.SELECT, openPanel = null)

        val state = selectActive.reduce(SheetSelectorEvent.SelectionTextRequested)

        assertEquals(SheetSelectorPanel.TEXT, state.openPanel)
        assertEquals(SheetRailTool.SELECT, state.activeTool)
    }

    @Test fun `an outside tap, a back press or a started stroke each close a selection text panel and keep the select tool active`() {
        val selectActive = SheetSelectorState(activeTool = SheetRailTool.SELECT, openPanel = null)
        val opened = selectActive.reduce(SheetSelectorEvent.SelectionTextRequested)

        listOf(SheetSelectorEvent.OutsideTapped, SheetSelectorEvent.BackPressed, SheetSelectorEvent.StrokeStarted).forEach { event ->
            val state = opened.reduce(event)
            assertNull(state.openPanel)
            assertEquals(SheetRailTool.SELECT, state.activeTool)
        }
    }

    @Test fun `an outside tap, a back press or a started stroke each close an open panel without switching tools`() {
        val opened = initial.reduce(SheetSelectorEvent.ToolTapped(SheetRailTool.PEN))

        listOf(SheetSelectorEvent.OutsideTapped, SheetSelectorEvent.BackPressed, SheetSelectorEvent.StrokeStarted).forEach { event ->
            val state = opened.reduce(event)
            assertNull(state.openPanel)
            assertEquals(SheetRailTool.PEN, state.activeTool)
        }
    }

    @Test fun `hiding the rail closes an open panel and keeps the active tool`() {
        val opened = initial.reduce(SheetSelectorEvent.ToolTapped(SheetRailTool.PEN))

        val hidden = opened.reduce(SheetSelectorEvent.RailHidden)

        assertEquals(true, hidden.railHidden)
        assertNull(hidden.openPanel)
        assertEquals(SheetRailTool.PEN, hidden.activeTool)
    }

    @Test fun `hiding an already-closed rail is a no-op beyond flipping the flag`() {
        val hidden = initial.reduce(SheetSelectorEvent.RailHidden)

        assertEquals(true, hidden.railHidden)
        assertNull(hidden.openPanel)
        assertEquals(SheetRailTool.PEN, hidden.activeTool)
    }

    @Test fun `showing the rail keeps the active tool and any already-closed panel`() {
        val hidden = initial.reduce(SheetSelectorEvent.RailHidden)

        val shown = hidden.reduce(SheetSelectorEvent.RailShown)

        assertEquals(false, shown.railHidden)
        assertNull(shown.openPanel)
        assertEquals(SheetRailTool.PEN, shown.activeTool)
    }
}
