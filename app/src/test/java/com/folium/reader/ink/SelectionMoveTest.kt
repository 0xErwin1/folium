package com.folium.reader.ink

import com.folium.reader.core.ink.InkInputKind
import com.folium.reader.core.ink.InkSample
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.InkTip
import com.folium.reader.core.ink.InkTool
import com.folium.reader.core.ink.SheetItem
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetTextBox
import com.folium.reader.core.ink.SheetTextFont
import com.folium.reader.core.ink.SheetTextStyle
import com.folium.reader.core.ink.StrokeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EPSILON = 1e-5f

class SelectionMoveTest {

    private fun stroke(id: String, sequence: Long): InkStroke = InkStroke(
        id = StrokeId(id), tool = InkTool.PEN, tip = InkTip.BALLPOINT, colorArgb = 0xFF000000.toInt(),
        widthSheetUnits = 0.01f, inputKind = InkInputKind.STYLUS,
        samples = listOf(InkSample(0.1f, 0.1f, 0), InkSample(0.2f, 0.2f, 10)), sequence = sequence
    )

    private fun textBox(id: String, sequence: Long): SheetTextBox = SheetTextBox(
        id = StrokeId(id), topLeft = SheetPoint(0.1f, 0.1f), widthSheetUnits = 0.5f, heightSheetUnits = 0.05f,
        text = "hello", font = SheetTextFont.SERIF, sizePt = 16f, style = SheetTextStyle.NORMAL,
        colorArgb = 0xFF000000.toInt(), sequence = sequence
    )

    @Test fun `an item list with only strokes contains no text box`() {
        assertFalse(containsTextBox(listOf(SheetItem.Stroke(stroke("a", 0)))))
    }

    @Test fun `an item list holding one text box contains a text box`() {
        assertTrue(containsTextBox(listOf(SheetItem.Stroke(stroke("a", 0)), SheetItem.Text(textBox("b", 1)))))
    }

    @Test fun `moving a mixed selection gives every item a fresh id and moves it by the same delta`() {
        val items = listOf(SheetItem.Stroke(stroke("a", 0)), SheetItem.Text(textBox("b", 1)))
        var nextId = 0
        var nextSequence = 10L

        val moved = translateSelectionItems(
            items, dx = 0.05f, dy = -0.02f,
            newId = { StrokeId("moved-${nextId++}") },
            newSequence = { nextSequence++ }
        )

        val movedStroke = (moved[0] as SheetItem.Stroke).stroke
        val movedText = (moved[1] as SheetItem.Text).textBox

        assertEquals(StrokeId("moved-0"), movedStroke.id)
        assertEquals(10L, movedStroke.sequence)
        assertEquals(0.15f, movedStroke.samples.first().x, EPSILON)
        assertEquals(0.08f, movedStroke.samples.first().y, EPSILON)

        assertEquals(StrokeId("moved-1"), movedText.id)
        assertEquals(11L, movedText.sequence)
        assertEquals(0.15f, movedText.topLeft.x, EPSILON)
        assertEquals(0.08f, movedText.topLeft.y, EPSILON)
    }

    @Test fun `moving a mixed selection preserves its own relative z-order`() {
        val items = listOf(SheetItem.Text(textBox("first", 0)), SheetItem.Stroke(stroke("second", 1)), SheetItem.Text(textBox("third", 2)))

        val moved = translateSelectionItems(items, dx = 0.01f, dy = 0.01f, newId = { StrokeId("x-${System.nanoTime()}") }, newSequence = sequenceGenerator())

        assertEquals(listOf(0L, 1L, 2L), moved.map { it.sequence })
    }

    private fun sequenceGenerator(): () -> Long {
        var next = 0L
        return { next++ }
    }
}
