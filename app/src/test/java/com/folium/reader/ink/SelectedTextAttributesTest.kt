package com.folium.reader.ink

import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetTextBox
import com.folium.reader.core.ink.SheetTextFont
import com.folium.reader.core.ink.SheetTextStyle
import com.folium.reader.core.ink.StrokeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val EPSILON = 1e-5f

class SelectedTextAttributesTest {

    private fun box(
        id: String,
        font: SheetTextFont = SheetTextFont.SERIF,
        sizePt: Float = 16f,
        style: SheetTextStyle = SheetTextStyle.NORMAL,
        colorArgb: Int = 0xFF000000.toInt()
    ): SheetTextBox = SheetTextBox(
        id = StrokeId(id),
        topLeft = SheetPoint(0.1f, 0.1f),
        widthSheetUnits = 0.5f,
        heightSheetUnits = 0.05f,
        text = "hello",
        font = font,
        sizePt = sizePt,
        style = style,
        colorArgb = colorArgb,
        sequence = 0
    )

    @Test fun `an empty selection has no attributes`() {
        assertNull(selectedTextAttributesOf(emptyList()))
    }

    @Test fun `a single box's own attributes are all selected`() {
        val attributes = selectedTextAttributesOf(listOf(box("a", font = SheetTextFont.MONO, sizePt = 20f, style = SheetTextStyle.BOLD)))!!

        assertEquals(SheetTextFont.MONO, attributes.font)
        assertEquals(20f, attributes.sizePt, EPSILON)
        assertEquals(SheetTextStyle.BOLD, attributes.style)
        assertEquals(0xFF000000.toInt(), attributes.colorArgb)
    }

    @Test fun `boxes that agree on every attribute all show it selected`() {
        val attributes = selectedTextAttributesOf(
            listOf(box("a", font = SheetTextFont.SANS), box("b", font = SheetTextFont.SANS))
        )!!

        assertEquals(SheetTextFont.SANS, attributes.font)
    }

    @Test fun `boxes that differ on font, style or colour show nothing selected for that attribute`() {
        val attributes = selectedTextAttributesOf(
            listOf(
                box("a", font = SheetTextFont.SERIF, style = SheetTextStyle.NORMAL, colorArgb = 1),
                box("b", font = SheetTextFont.MONO, style = SheetTextStyle.ITALIC, colorArgb = 2)
            )
        )!!

        assertNull(attributes.font)
        assertNull(attributes.style)
        assertNull(attributes.colorArgb)
    }

    @Test fun `size always shows the first box's own value even when the boxes differ`() {
        val attributes = selectedTextAttributesOf(listOf(box("a", sizePt = 24f), box("b", sizePt = 30f)))!!

        assertEquals(24f, attributes.sizePt, EPSILON)
    }
}
