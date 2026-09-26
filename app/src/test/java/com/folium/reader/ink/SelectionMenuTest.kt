package com.folium.reader.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionMenuTest {

    @Test fun `without a convert-to-text handler the menu offers copy and delete, none primary`() {
        val items = selectionMenuItems(hasConvertToTextHandler = false)

        assertEquals(
            listOf(SelectionMenuAction.COPY, SelectionMenuAction.DELETE),
            items.map { it.action }
        )
        assertTrue(items.none { it.isPrimary })
    }

    @Test fun `with a convert-to-text handler convert-to-text leads and is the only primary item`() {
        val items = selectionMenuItems(hasConvertToTextHandler = true)

        assertEquals(
            listOf(SelectionMenuAction.CONVERT_TO_TEXT, SelectionMenuAction.COPY, SelectionMenuAction.DELETE),
            items.map { it.action }
        )
        assertEquals(listOf(SelectionMenuAction.CONVERT_TO_TEXT), items.filter { it.isPrimary }.map { it.action })
    }

    @Test fun `with a text box in the selection but no convert handler text leads and is never primary`() {
        val items = selectionMenuItems(hasConvertToTextHandler = false, hasTextBoxInSelection = true)

        assertEquals(
            listOf(SelectionMenuAction.TEXT, SelectionMenuAction.COPY, SelectionMenuAction.DELETE),
            items.map { it.action }
        )
        assertTrue(items.none { it.isPrimary })
    }

    @Test fun `with both a convert handler and a text box in the selection, convert leads, then text`() {
        val items = selectionMenuItems(hasConvertToTextHandler = true, hasTextBoxInSelection = true)

        assertEquals(
            listOf(SelectionMenuAction.CONVERT_TO_TEXT, SelectionMenuAction.TEXT, SelectionMenuAction.COPY, SelectionMenuAction.DELETE),
            items.map { it.action }
        )
        assertEquals(listOf(SelectionMenuAction.CONVERT_TO_TEXT), items.filter { it.isPrimary }.map { it.action })
    }

    @Test fun `without a text box in the selection the menu never offers text`() {
        val items = selectionMenuItems(hasConvertToTextHandler = true, hasTextBoxInSelection = false)

        assertTrue(items.none { it.action == SelectionMenuAction.TEXT })
    }

    @Test fun `the menu anchors below the selection when there is room`() {
        val placement = selectionMenuPlacement(
            selectionLeftPx = 50,
            selectionTopPx = 100,
            selectionBottomPx = 200,
            paneWidthPx = 1000,
            paneHeightPx = 1000,
            marginStartPx = 24,
            contentWidthPx = 300,
            contentHeightPx = 60
        )

        assertFalse(placement.above)
        assertEquals(200, placement.topPx)
        assertEquals(74, placement.leftPx)
    }

    @Test fun `the menu flips above the selection when there is no room below`() {
        val placement = selectionMenuPlacement(
            selectionLeftPx = 50,
            selectionTopPx = 900,
            selectionBottomPx = 980,
            paneWidthPx = 1000,
            paneHeightPx = 1000,
            marginStartPx = 24,
            contentWidthPx = 300,
            contentHeightPx = 60
        )

        assertTrue(placement.above)
        assertEquals(840, placement.topPx)
    }

    @Test fun `the menu's own left edge is clamped so it never leaves the pane`() {
        val placement = selectionMenuPlacement(
            selectionLeftPx = 900,
            selectionTopPx = 100,
            selectionBottomPx = 200,
            paneWidthPx = 1000,
            paneHeightPx = 1000,
            marginStartPx = 24,
            contentWidthPx = 300,
            contentHeightPx = 60
        )

        // selectionLeftPx + marginStartPx (924) would run the 300px-wide menu past the 1000px pane.
        assertEquals(700, placement.leftPx)
    }

    @Test fun `the menu's own top edge is clamped so it never leaves the pane, even flipped above`() {
        val placement = selectionMenuPlacement(
            selectionLeftPx = 50,
            selectionTopPx = 10,
            selectionBottomPx = 990,
            paneWidthPx = 1000,
            paneHeightPx = 1000,
            marginStartPx = 24,
            contentWidthPx = 300,
            contentHeightPx = 60
        )

        assertTrue(placement.above)
        assertEquals(0, placement.topPx)
    }

    @Test fun `the menu shows for a laid-out selection while nothing else holds the surface`() {
        val bounds = ViewRect(10f, 20f, 110f, 120f)

        assertTrue(selectionMenuShown(selectionSize = 2, boundsViewPx = bounds, paneLaidOut = true, selectionEditing = false, panelOpen = false))
    }

    @Test fun `the menu hides with no selection, no bounds, no pane size, a drag in progress or a panel open`() {
        val bounds = ViewRect(10f, 20f, 110f, 120f)

        assertFalse(selectionMenuShown(selectionSize = 0, boundsViewPx = bounds, paneLaidOut = true, selectionEditing = false, panelOpen = false))
        assertFalse(selectionMenuShown(selectionSize = 2, boundsViewPx = null, paneLaidOut = true, selectionEditing = false, panelOpen = false))
        assertFalse(selectionMenuShown(selectionSize = 2, boundsViewPx = bounds, paneLaidOut = false, selectionEditing = false, panelOpen = false))
        assertFalse(selectionMenuShown(selectionSize = 2, boundsViewPx = bounds, paneLaidOut = true, selectionEditing = true, panelOpen = false))
        assertFalse(selectionMenuShown(selectionSize = 2, boundsViewPx = bounds, paneLaidOut = true, selectionEditing = false, panelOpen = true))
    }
}
