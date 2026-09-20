package com.folium.reader.ink

import com.folium.reader.core.ink.InkInputKind
import com.folium.reader.core.ink.InkSample
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.InkTip
import com.folium.reader.core.ink.InkTool
import com.folium.reader.core.ink.SheetEdit
import com.folium.reader.core.ink.SheetEditHistory
import com.folium.reader.core.ink.StrokeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkEditCommitterTest {

    private val add = SheetEdit.AddStrokes(
        listOf(
            InkStroke(
                id = StrokeId("a"),
                tool = InkTool.PEN,
                tip = InkTip.BALLPOINT,
                colorArgb = 0xFF000000.toInt(),
                widthSheetUnits = InkPenWidths.MEDIUM_SHEET_UNITS,
                inputKind = InkInputKind.FINGER,
                samples = listOf(InkSample(0f, 0f, 0)),
                sequence = 0
            )
        )
    )

    @Test
    fun aRefusedEditNeverEntersTheHistory() {
        val committer = InkEditCommitter(SheetEditHistory()) { false }

        assertFalse(committer.commit(add))
        assertFalse(committer.canUndo)
    }

    @Test
    fun anAcceptedEditIsWrittenOnceAndCanBeUndone() {
        val written = mutableListOf<SheetEdit>()
        val committer = InkEditCommitter(SheetEditHistory()) { written += it; true }

        assertTrue(committer.commit(add))

        assertEquals(listOf<SheetEdit>(add), written)
        assertTrue(committer.canUndo)
    }

    @Test
    fun aRefusedUndoLeavesTheHistoryWhereItWas() {
        var accepting = true
        val committer = InkEditCommitter(SheetEditHistory()) { accepting }
        committer.commit(add)

        accepting = false

        assertNull(committer.undo())
        assertTrue(committer.canUndo)
        assertFalse(committer.canRedo)
    }

    @Test
    fun aRefusedRedoLeavesTheHistoryWhereItWas() {
        var accepting = true
        val committer = InkEditCommitter(SheetEditHistory()) { accepting }
        committer.commit(add)
        committer.undo()

        accepting = false

        assertNull(committer.redo())
        assertFalse(committer.canUndo)
        assertTrue(committer.canRedo)
    }

    @Test
    fun undoAndRedoHandTheWriterTheEditTheyReturn() {
        val written = mutableListOf<SheetEdit>()
        val committer = InkEditCommitter(SheetEditHistory()) { written += it; true }
        committer.commit(add)

        val inverse = committer.undo()
        val forward = committer.redo()

        assertEquals(listOf(add, inverse, forward), written)
        assertEquals(add, forward)
    }

    @Test
    fun aBuiltBatchDropsStrokesThatLeftTheSheetWhileItWasBuilding() {
        val batch = listOf("kept" to 1, "erased" to 2, "also-kept" to 3)

        assertEquals(listOf("kept" to 1, "also-kept" to 3), stillLive(batch) { it != "erased" })
    }
}
