package com.folium.reader.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetPaneHistoryTest {

    private class FakeTarget : InkHistoryTarget {
        var undos = 0
        var redos = 0

        override fun undo() {
            undos += 1
        }

        override fun redo() {
            redos += 1
        }
    }

    @Test fun onlyTheBoundOfTwoSharingSurfacesReportsItsHistory() {
        val history = SheetPaneHistory()
        val sheet = FakeTarget()
        val page = FakeTarget()
        history.bindHome(sheet)
        history.report(sheet, canUndo = true, canRedo = false)

        history.bind(page)
        history.update(newCanUndo = false, newCanRedo = true)
        history.report(sheet, canUndo = true, canRedo = true)

        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        assertTrue(history.isBoundTo(page))
        assertFalse(history.isBoundTo(sheet))

        history.undo()
        assertEquals(1, page.undos)
        assertEquals(0, sheet.undos)
    }

    @Test fun releasingTheOnlyBoundSurfaceClearsTheHistory() {
        val history = SheetPaneHistory()
        val page = FakeTarget()
        history.bind(page)
        history.update(newCanUndo = true, newCanRedo = true)

        history.release(page)

        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertFalse(history.isBoundTo(page))
        history.undo()
        assertEquals(0, page.undos)
    }

    @Test fun releasingAPageSurfaceHandsTheHistoryBackToItsSheet() {
        val history = SheetPaneHistory()
        val sheet = FakeTarget()
        val page = FakeTarget()
        history.bindHome(sheet)
        history.report(sheet, canUndo = true, canRedo = false)
        history.bind(page)
        history.update(newCanUndo = false, newCanRedo = false)
        history.report(sheet, canUndo = true, canRedo = true)

        history.release(page)

        assertTrue(history.isBoundTo(sheet))
        assertTrue(history.canUndo)
        assertTrue(history.canRedo)
        history.redo()
        assertEquals(1, sheet.redos)
    }

    @Test fun releasingAnUnboundSurfaceLeavesTheBindingAlone() {
        val history = SheetPaneHistory()
        val sheet = FakeTarget()
        val page = FakeTarget()
        history.bindHome(sheet)
        history.report(sheet, canUndo = true, canRedo = false)

        history.release(page)

        assertTrue(history.isBoundTo(sheet))
        assertTrue(history.canUndo)
    }

    @Test fun releasingTheSheetItselfClearsTheHistoryAndForgetsIt() {
        val history = SheetPaneHistory()
        val sheet = FakeTarget()
        val page = FakeTarget()
        history.bindHome(sheet)
        history.report(sheet, canUndo = true, canRedo = true)

        history.release(sheet)
        history.bind(page)
        history.release(page)

        assertFalse(history.isBoundTo(sheet))
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }
}
