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
    fun inverseOfAReplaceSwapsRemovedAndAdded() {
        val removed = strokeWithSequence(0)
        val added = strokeWithSequence(1)
        val replace = SheetEdit.ReplaceStrokes(removed = listOf(removed), added = listOf(added))

        assertEquals(SheetEdit.ReplaceStrokes(removed = listOf(added), added = listOf(removed)), replace.inverse())
    }

    @Test
    fun undoOfAReplaceReturnsTheSwappedReplace() {
        val history = SheetEditHistory()
        val removed = strokeWithSequence(0)
        val added = strokeWithSequence(1)
        val replace = SheetEdit.ReplaceStrokes(removed = listOf(removed), added = listOf(added))
        history.apply(replace)

        assertEquals(replace.inverse(), history.undo())
        assertEquals(replace, history.redo())
    }

    private fun textBoxWithSequence(sequence: Long): SheetTextBox = SheetTextBox(
        StrokeId("22222222-2222-2222-2222-222222222222"),
        topLeft = SheetPoint(0f, 0f), widthSheetUnits = 0.3f, heightSheetUnits = 0.1f,
        text = "note", font = SheetTextFont.SERIF, sizePt = 16f, style = SheetTextStyle.NORMAL, colorArgb = 0, sequence = sequence
    )

    @Test
    fun inverseOfAReplaceItemsSwapsRemovedAndAdded() {
        val removed = listOf(SheetItem.Stroke(strokeWithSequence(0)))
        val added = listOf(SheetItem.Text(textBoxWithSequence(1)))
        val replace = SheetEdit.ReplaceItems(removed = removed, added = added)

        assertEquals(SheetEdit.ReplaceItems(removed = added, added = removed), replace.inverse())
    }

    @Test
    fun undoOfAReplaceItemsReturnsTheSwappedReplace() {
        val history = SheetEditHistory()
        val removed = listOf(SheetItem.Stroke(strokeWithSequence(0)), SheetItem.Text(textBoxWithSequence(1)))
        val added = listOf(SheetItem.Text(textBoxWithSequence(2)))
        val replace = SheetEdit.ReplaceItems(removed = removed, added = added)
        history.apply(replace)

        assertEquals(replace.inverse(), history.undo())
        assertEquals(replace, history.redo())
    }

    @Test
    fun anEmptyRemovedSideExpressesAnAddOnlyReplaceItems() {
        val added = listOf(SheetItem.Text(textBoxWithSequence(0)))
        val replace = SheetEdit.ReplaceItems(removed = emptyList(), added = added)

        val inverse = replace.inverse() as SheetEdit.ReplaceItems
        assertEquals(added, inverse.removed)
        assertTrue(inverse.added.isEmpty())
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
