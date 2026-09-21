package com.folium.reader.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EPSILON = 1e-4f

class SheetItemSelectionTest {

    private fun strokeAlongPoints(id: String, points: List<Pair<Float, Float>>, sequence: Long = 0): InkStroke = InkStroke(
        StrokeId(id), InkTool.PEN, InkTip.BALLPOINT, colorArgb = 0,
        widthSheetUnits = 0.01f, inputKind = InkInputKind.STYLUS,
        samples = points.mapIndexed { index, (x, y) -> InkSample(x, y, index * 10) },
        sequence = sequence
    )

    private fun textBoxAt(bounds: SheetRect, id: String, sequence: Long = 0): SheetTextBox = SheetTextBox(
        StrokeId(id), topLeft = SheetPoint(bounds.left, bounds.top),
        widthSheetUnits = bounds.width, heightSheetUnits = bounds.height,
        text = "note", font = SheetTextFont.SERIF, sizePt = 16f, style = SheetTextStyle.NORMAL, colorArgb = 0, sequence = sequence
    )

    private fun strokeId(seed: String) = StrokeId("11111111-1111-1111-1111-1111111111$seed")

    // --- Tap ---

    @Test fun `a tap inside a text box's bounds selects it`() {
        val box = textBoxAt(SheetRect(0f, 0f, 1f, 1f), "11111111-1111-1111-1111-111111111101")
        val hit = itemGroupAtTap(listOf(SheetItem.Text(box)), SheetPoint(0.5f, 0.5f), toleranceSheetUnits = 0.01f)

        assertEquals(setOf(box.id), hit)
    }

    @Test fun `a tap outside a text box's own tolerance selects nothing`() {
        val box = textBoxAt(SheetRect(0f, 0f, 1f, 1f), "11111111-1111-1111-1111-111111111102")
        val hit = itemGroupAtTap(listOf(SheetItem.Text(box)), SheetPoint(2f, 2f), toleranceSheetUnits = 0.01f)

        assertTrue(hit.isEmpty())
    }

    @Test fun `a tap just past a text box's edge is still hit within tolerance`() {
        val box = textBoxAt(SheetRect(0f, 0f, 1f, 1f), "11111111-1111-1111-1111-111111111103")
        val hit = itemGroupAtTap(listOf(SheetItem.Text(box)), SheetPoint(1.005f, 0.5f), toleranceSheetUnits = 0.01f)

        assertEquals(setOf(box.id), hit)
    }

    @Test fun `the topmost text box wins over a stroke beneath it`() {
        val stroke = strokeAlongPoints("11111111-1111-1111-1111-111111111104", listOf(0f to 0f, 1f to 1f), sequence = 0)
        val box = textBoxAt(SheetRect(0f, 0f, 1f, 1f), "11111111-1111-1111-1111-111111111105", sequence = 1)

        val hit = itemGroupAtTap(
            listOf(SheetItem.Stroke(stroke), SheetItem.Text(box)),
            SheetPoint(0.5f, 0.5f), toleranceSheetUnits = 0.01f
        )

        assertEquals(setOf(box.id), hit)
    }

    @Test fun `a stroke drawn over a text box wins the tap`() {
        val box = textBoxAt(SheetRect(0f, 0f, 1f, 1f), "11111111-1111-1111-1111-111111111106", sequence = 0)
        val stroke = strokeAlongPoints("11111111-1111-1111-1111-111111111107", listOf(0f to 0.5f, 1f to 0.5f), sequence = 1)

        val hit = itemGroupAtTap(
            listOf(SheetItem.Text(box), SheetItem.Stroke(stroke)),
            SheetPoint(0.5f, 0.5f), toleranceSheetUnits = 0.01f
        )

        assertEquals(setOf(stroke.id), hit)
    }

    // --- Lasso / rectangle area rule ---

    /**
     * The box spans x in 0..1, y in 0..1. [selectItemsByPolygon]'s 8x8 grid samples column centres at
     * (col + 0.5) / 8, i.e. 0.0625, 0.1875, ..., 0.9375. A polygon covering x < 0.6 keeps 5 of those 8
     * columns (up to 0.5625) fully inside, 62.5% of the box's area, clear of the 50% threshold; one
     * covering x < 0.4 keeps only 3 columns, 37.5%, equally clear on the other side.
     */
    @Test fun `a lasso covering sixty percent of a text box's width selects it`() {
        val box = textBoxAt(SheetRect(0f, 0f, 1f, 1f), "11111111-1111-1111-1111-111111111110")
        val polygon = listOf(SheetPoint(0f, -1f), SheetPoint(0.6f, -1f), SheetPoint(0.6f, 2f), SheetPoint(0f, 2f))

        assertEquals(setOf(box.id), selectItemsByLasso(listOf(SheetItem.Text(box)), polygon))
    }

    @Test fun `a lasso covering forty percent of a text box's width does not select it`() {
        val box = textBoxAt(SheetRect(0f, 0f, 1f, 1f), "11111111-1111-1111-1111-111111111111")
        val polygon = listOf(SheetPoint(0f, -1f), SheetPoint(0.4f, -1f), SheetPoint(0.4f, 2f), SheetPoint(0f, 2f))

        assertTrue(selectItemsByLasso(listOf(SheetItem.Text(box)), polygon).isEmpty())
    }

    @Test fun `a rectangle selection covering most of a text box selects it alongside a stroke`() {
        val stroke = strokeAlongPoints("11111111-1111-1111-1111-111111111112", listOf(0.4f to 0.4f, 0.6f to 0.6f))
        val box = textBoxAt(SheetRect(0f, 0f, 1f, 1f), "11111111-1111-1111-1111-111111111113")

        val items = listOf(SheetItem.Stroke(stroke), SheetItem.Text(box))
        val selected = selectItemsByRectangle(items, SheetPoint(-0.1f, -0.1f), SheetPoint(1.1f, 1.1f))

        assertEquals(setOf(stroke.id, box.id), selected)
    }

    @Test fun `a lasso with fewer than three points selects nothing`() {
        val box = textBoxAt(SheetRect(0f, 0f, 1f, 1f), "11111111-1111-1111-1111-111111111114")
        assertTrue(selectItemsByLasso(listOf(SheetItem.Text(box)), listOf(SheetPoint(0f, 0f), SheetPoint(1f, 1f))).isEmpty())
    }

    // --- Bounds ---

    @Test fun `item selection bounds is null for an empty selection`() {
        assertNull(itemSelectionBounds(emptyList()))
    }

    @Test fun `item selection bounds unions a stroke and a text box`() {
        val stroke = strokeAlongPoints("11111111-1111-1111-1111-111111111120", listOf(-5f to -5f, -4f to -4f))
        val box = textBoxAt(SheetRect(1f, 1f, 2f, 2f), "11111111-1111-1111-1111-111111111121")

        val bounds = itemSelectionBounds(listOf(SheetItem.Stroke(stroke), SheetItem.Text(box)))!!

        assertEquals(stroke.bounds.union(box.bounds).left, bounds.left, EPSILON)
        assertEquals(stroke.bounds.union(box.bounds).top, bounds.top, EPSILON)
        assertEquals(stroke.bounds.union(box.bounds).right, bounds.right, EPSILON)
        assertEquals(stroke.bounds.union(box.bounds).bottom, bounds.bottom, EPSILON)
    }

    // --- Translate ---

    @Test fun `translating a text box moves its top-left and takes a fresh id and sequence`() {
        val original = textBoxAt(SheetRect(0.2f, 0.3f, 0.7f, 0.4f), "11111111-1111-1111-1111-111111111130", sequence = 2)

        val moved = translateTextBox(original, dx = 0.1f, dy = -0.05f, newId = { strokeId("31") }, newSequence = { 9L })

        assertEquals(strokeId("31"), moved.id)
        assertEquals(9L, moved.sequence)
        assertEquals(0.3f, moved.topLeft.x, EPSILON)
        assertEquals(0.25f, moved.topLeft.y, EPSILON)
        assertEquals(original.widthSheetUnits, moved.widthSheetUnits, EPSILON)
        assertEquals(original.heightSheetUnits, moved.heightSheetUnits, EPSILON)
        assertEquals(original.text, moved.text)
        assertEquals(original.style, moved.style)
        assertEquals(original.colorArgb, moved.colorArgb)
    }

    // --- Scale position ---

    @Test fun `scaling a text box's position maps its top-left through the anchor and factors but leaves its size untouched`() {
        val original = textBoxAt(SheetRect(0.4f, 1f, 0.9f, 1.5f), "11111111-1111-1111-1111-111111111150", sequence = 3)

        val scaled = scaleTextBoxPosition(
            original,
            anchor = SheetPoint(0.2f, 1f),
            scaleX = 2f,
            scaleY = 1f,
            newId = { strokeId("51") },
            newSequence = { 12L }
        )

        assertEquals(strokeId("51"), scaled.id)
        assertEquals(12L, scaled.sequence)
        assertEquals(0.6f, scaled.topLeft.x, EPSILON)
        assertEquals(1f, scaled.topLeft.y, EPSILON)
        assertEquals(original.widthSheetUnits, scaled.widthSheetUnits, EPSILON)
        assertEquals(original.heightSheetUnits, scaled.heightSheetUnits, EPSILON)
        assertEquals(original.text, scaled.text)
        assertEquals(original.font, scaled.font)
        assertEquals(original.sizePt, scaled.sizePt, EPSILON)
        assertEquals(original.style, scaled.style)
        assertEquals(original.colorArgb, scaled.colorArgb)
    }

    // --- Width resize ---

    @Test fun `dragging the right edge grows the box without moving its left edge or top`() {
        val box = textBoxAt(SheetRect(0f, 0f, 1f, 0.5f), "11111111-1111-1111-1111-111111111140")

        val resize = textBoxWidthResize(box, SelectionCorner.BOTTOM_RIGHT, SheetPoint(1.5f, 5f))

        assertEquals(0f, resize.left, EPSILON)
        assertEquals(1.5f, resize.right, EPSILON)
    }

    @Test fun `dragging the left edge grows the box without moving its right edge`() {
        val box = textBoxAt(SheetRect(0f, 0f, 1f, 0.5f), "11111111-1111-1111-1111-111111111141")

        val resize = textBoxWidthResize(box, SelectionCorner.TOP_LEFT, SheetPoint(-0.5f, -5f))

        assertEquals(-0.5f, resize.left, EPSILON)
        assertEquals(1f, resize.right, EPSILON)
    }

    @Test fun `a width resize never collapses the box under the minimum width`() {
        val box = textBoxAt(SheetRect(0f, 0f, 1f, 0.5f), "11111111-1111-1111-1111-111111111142")

        val resize = textBoxWidthResize(box, SelectionCorner.TOP_RIGHT, SheetPoint(0.001f, 0f))

        assertTrue(resize.right - resize.left >= MIN_DIAGONAL_SHEET_UNITS)
    }

    @Test fun `dragging a top or bottom corner never changes which edge is fixed`() {
        val box = textBoxAt(SheetRect(0f, 0f, 1f, 0.5f), "11111111-1111-1111-1111-111111111143")

        val fromTopRight = textBoxWidthResize(box, SelectionCorner.TOP_RIGHT, SheetPoint(2f, -9f))
        val fromBottomRight = textBoxWidthResize(box, SelectionCorner.BOTTOM_RIGHT, SheetPoint(2f, 9f))

        assertEquals(fromTopRight.left, fromBottomRight.left, EPSILON)
        assertEquals(fromTopRight.right, fromBottomRight.right, EPSILON)
    }
}
