package com.folium.reader.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetEditHistoryTest {

    private fun strokeWithSequence(sequence: Long): InkStroke = InkStroke(
        StrokeId("11111111-1111-1111-1111-111111111111"),
        InkTool.PEN, InkTip.BALLPOINT, colorArgb = 0,
        widthSheetUnits = 0.01f, inputKind = InkInputKind.STYLUS,
        samples = listOf(InkSample(0.1f, 0.1f, 0)), sequence = sequence
    )

    @Test
    fun undoOfAnAddReturnsARemoveOfTheSameStrokes() {
        val history = SheetEditHistory()
        val stroke = strokeWithSequence(0)
        history.apply(SheetEdit.AddStrokes(listOf(stroke)))

        val undo = history.undo()
        assertEquals(SheetEdit.RemoveStrokes(listOf(stroke)), undo)
    }

    @Test
    fun redoAfterUndoReappliesTheOriginalEdit() {
        val history = SheetEditHistory()
        val stroke = strokeWithSequence(1)
        val add = SheetEdit.AddStrokes(listOf(stroke))
        history.apply(add)
        history.undo()

        assertEquals(add, history.redo())
    }

    @Test
    fun undoAndRedoRunInStrictOrder() {
        val history = SheetEditHistory()
        val first = SheetEdit.AddStrokes(listOf(strokeWithSequence(0)))
        val second = SheetEdit.AddStrokes(listOf(strokeWithSequence(1)))
        history.apply(first)
        history.apply(second)

        assertEquals(second.inverse(), history.undo())
        assertEquals(first.inverse(), history.undo())
        assertNull(history.undo())

        assertEquals(first, history.redo())
        assertEquals(second, history.redo())
        assertNull(history.redo())
    }

    @Test
    fun aNewEditClearsTheRedoStack() {
        val history = SheetEditHistory()
        history.apply(SheetEdit.AddStrokes(listOf(strokeWithSequence(0))))
        history.undo()
        assertTrue(history.canRedo)

        history.apply(SheetEdit.AddStrokes(listOf(strokeWithSequence(1))))
        assertFalse(history.canRedo)
    }

    @Test
    fun theOldestEditIsDroppedOnceCapacityIsExceeded() {
        val history = SheetEditHistory(capacity = 2)
        val first = SheetEdit.AddStrokes(listOf(strokeWithSequence(0)))
        val second = SheetEdit.AddStrokes(listOf(strokeWithSequence(1)))
        val third = SheetEdit.AddStrokes(listOf(strokeWithSequence(2)))
        history.apply(first)
        history.apply(second)
        history.apply(third)

        assertEquals(third.inverse(), history.undo())
        assertEquals(second.inverse(), history.undo())
        assertNull(history.undo())
    }

    @Test
    fun undoingARemovalRestoresTheStrokesWithTheirOriginalSequence() {
        val history = SheetEditHistory()
        val stroke = strokeWithSequence(sequence = 42)
        history.apply(SheetEdit.RemoveStrokes(listOf(stroke)))

        val undo = history.undo()
        assertTrue(undo is SheetEdit.AddStrokes)
        assertEquals(42L, (undo as SheetEdit.AddStrokes).strokes.single().sequence)
    }

    @Test
    fun canUndoAndCanRedoReflectHistoryState() {
        val history = SheetEditHistory()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)

        history.apply(SheetEdit.AddStrokes(listOf(strokeWithSequence(0))))
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)

        history.undo()
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
    }
}
