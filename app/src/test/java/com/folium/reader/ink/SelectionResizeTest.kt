package com.folium.reader.ink

import com.folium.reader.core.ink.InkInputKind
import com.folium.reader.core.ink.InkSample
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.InkTip
import com.folium.reader.core.ink.InkTool
import com.folium.reader.core.ink.SelectionResizeScale
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

class SelectionResizeTest {

    private fun stroke(id: String, sequence: Long): InkStroke = InkStroke(
        id = StrokeId(id), tool = InkTool.PEN, tip = InkTip.BALLPOINT, colorArgb = 0xFF000000.toInt(),
        widthSheetUnits = 0.01f, inputKind = InkInputKind.STYLUS,
        samples = listOf(InkSample(0.1f, 0.1f, 0), InkSample(0.2f, 0.2f, 10)), sequence = sequence
    )

    private fun textBox(id: String, sequence: Long, topLeft: SheetPoint = SheetPoint(0.1f, 0.1f)): SheetTextBox = SheetTextBox(
        id = StrokeId(id), topLeft = topLeft, widthSheetUnits = 0.5f, heightSheetUnits = 0.05f,
        text = "hello", font = SheetTextFont.SERIF, sizePt = 16f, style = SheetTextStyle.NORMAL,
        colorArgb = 0xFF000000.toInt(), sequence = sequence
    )

    @Test fun `a selection of exactly one text box is a single text box resize`() {
        assertTrue(isSingleTextBoxResize(listOf(SheetItem.Text(textBox("a", 0)))))
    }

    @Test fun `a selection of one stroke is not a single text box resize`() {
        assertFalse(isSingleTextBoxResize(listOf(SheetItem.Stroke(stroke("a", 0)))))
    }

    @Test fun `a selection mixing a stroke and a text box is not a single text box resize`() {
        assertFalse(isSingleTextBoxResize(listOf(SheetItem.Stroke(stroke("a", 0)), SheetItem.Text(textBox("b", 1)))))
    }

    @Test fun `a selection of two text boxes is not a single text box resize`() {
        assertFalse(isSingleTextBoxResize(listOf(SheetItem.Text(textBox("a", 0)), SheetItem.Text(textBox("b", 1)))))
    }

    @Test fun `scaling a mixed selection scales the stroke and only repositions the text box`() {
        val items = listOf(SheetItem.Stroke(stroke("a", 0)), SheetItem.Text(textBox("b", 1)))
        val scale = SelectionResizeScale(anchor = SheetPoint(0f, 0f), scaleX = 2f, scaleY = 2f)
        var nextId = 0
        var nextSequence = 5L

        val scaled = scaleSelectionItems(items, scale, newId = { StrokeId("scaled-${nextId++}") }, newSequence = { nextSequence++ })

        val scaledStroke = (scaled[0] as SheetItem.Stroke).stroke
        val scaledText = (scaled[1] as SheetItem.Text).textBox

        assertEquals(0.2f, scaledStroke.samples.first().x, EPSILON)
        assertEquals(0.2f, scaledStroke.samples.first().y, EPSILON)
        assertEquals(StrokeId("scaled-0"), scaledStroke.id)
        assertEquals(5L, scaledStroke.sequence)

        assertEquals(0.2f, scaledText.topLeft.x, EPSILON)
        assertEquals(0.2f, scaledText.topLeft.y, EPSILON)
        assertEquals(0.5f, scaledText.widthSheetUnits, EPSILON)
        assertEquals(StrokeId("scaled-1"), scaledText.id)
        assertEquals(6L, scaledText.sequence)
    }

    @Test fun `scaling preserves relative z-order across strokes and text boxes`() {
        val items = listOf(SheetItem.Text(textBox("first", 0)), SheetItem.Stroke(stroke("second", 1)), SheetItem.Text(textBox("third", 2)))
        val scale = SelectionResizeScale(anchor = SheetPoint(0f, 0f), scaleX = 1.5f, scaleY = 1.5f)
        var next = 0L

        val scaled = scaleSelectionItems(items, scale, newId = { StrokeId("z-${System.nanoTime()}") }, newSequence = { next++ })

        assertEquals(listOf(0L, 1L, 2L), scaled.map { it.sequence })
    }
}
