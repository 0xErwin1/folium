package com.folium.reader.core.ink

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EPSILON = 1e-4f
private const val WIDTH = 0.01f

class InkSelectionTest {

    private fun strokeId(seed: String) = StrokeId("11111111-1111-1111-1111-1111111111$seed")

    private fun strokeAlongPoints(
        id: StrokeId,
        points: List<Pair<Float, Float>>,
        sequence: Long = 0,
        widthSheetUnits: Float = WIDTH,
        tool: InkTool = InkTool.PEN,
        colorArgb: Int = 0
    ): InkStroke = InkStroke(
        id, tool, InkTip.BALLPOINT, colorArgb = colorArgb,
        widthSheetUnits = widthSheetUnits, inputKind = InkInputKind.STYLUS,
        samples = points.mapIndexed { index, (x, y) -> InkSample(x, y, index * 10) },
        sequence = sequence
    )

    // --- Tap ---

    @Test fun `a tap selects the topmost of two overlapping strokes`() {
        val bottom = strokeAlongPoints(strokeId("01"), listOf(0f to 0f, 1f to 1f), sequence = 0)
        val top = strokeAlongPoints(strokeId("02"), listOf(0f to 1f, 1f to 0f), sequence = 1)

        val hit = strokeGroupAtTap(listOf(bottom, top), SheetPoint(0.5f, 0.5f), toleranceSheetUnits = 0.01f)

        assertEquals(setOf(top.id), hit)
    }

    @Test fun `a tap outside every stroke's tolerance selects nothing`() {
        val stroke = strokeAlongPoints(strokeId("03"), listOf(0f to 0f, 1f to 0f), widthSheetUnits = 0.01f)

        val hit = strokeGroupAtTap(listOf(stroke), SheetPoint(0.5f, 0.5f), toleranceSheetUnits = 0.01f)

        assertTrue(hit.isEmpty())
    }

    @Test fun `a tap counts half the stroke width as part of the stroke`() {
        val stroke = strokeAlongPoints(strokeId("04"), listOf(0f to 0f, 1f to 0f), widthSheetUnits = 0.2f)

        val hit = strokeGroupAtTap(listOf(stroke), SheetPoint(0.5f, 0.09f), toleranceSheetUnits = 0.001f)

        assertEquals(setOf(stroke.id), hit)
    }

    @Test fun `tapping a box side selects every side of the joined box`() {
        val samples = shapeSamples(InkShape.BOX, SheetPoint(0f, 0f), SheetPoint(1f, 1f), WIDTH)
        val sides = samples.mapIndexed { index, side ->
            strokeAlongPoints(strokeId("1$index"), side.map { it.x to it.y }, sequence = index.toLong())
        }

        val hit = strokeGroupAtTap(sides, SheetPoint(0.5f, 0f), toleranceSheetUnits = 0.01f)

        assertEquals(sides.map { it.id }.toSet(), hit)
    }

    @Test fun `a freehand stroke touching a box corner is not pulled into the joined shape`() {
        val samples = shapeSamples(InkShape.BOX, SheetPoint(0f, 0f), SheetPoint(1f, 1f), WIDTH)
        val sides = samples.mapIndexed { index, side ->
            strokeAlongPoints(strokeId("2$index"), side.map { it.x to it.y }, sequence = index.toLong())
        }
        val freehand = strokeAlongPoints(
            strokeId("29"),
            listOf(0f to 0f, 0.1f to 0.05f, 0.2f to 0f),
            sequence = 99
        )

        val hit = strokeGroupAtTap(sides + freehand, SheetPoint(0.5f, 0f), toleranceSheetUnits = 0.01f)

        assertTrue(freehand.id !in hit)
        assertEquals(sides.map { it.id }.toSet(), hit)
    }

    @Test fun `a freehand stroke never expands even when tapped directly`() {
        val freehand = strokeAlongPoints(strokeId("30"), listOf(0f to 0f, 0.1f to 0.05f, 0.2f to 0f))
        val other = strokeAlongPoints(strokeId("31"), listOf(0f to 0f, 0f to 1f), sequence = 1)

        val hit = strokeGroupAtTap(listOf(freehand, other), SheetPoint(0.1f, 0.049f), toleranceSheetUnits = 0.001f)

        assertEquals(setOf(freehand.id), hit)
    }

    @Test fun `a joined shape does not expand across a different colour, width or tool`() {
        val base = strokeAlongPoints(strokeId("40"), listOf(0f to 0f, 1f to 0f))
        val differentColor = strokeAlongPoints(strokeId("41"), listOf(1f to 0f, 1f to 1f), colorArgb = 1)
        val differentWidth = strokeAlongPoints(strokeId("42"), listOf(1f to 1f, 0f to 1f), widthSheetUnits = 0.02f)
        val differentTool = strokeAlongPoints(strokeId("43"), listOf(0f to 1f, 0f to 0f), tool = InkTool.HIGHLIGHTER)

        val hit = strokeGroupAtTap(
            listOf(base, differentColor, differentWidth, differentTool),
            SheetPoint(0.5f, 0f),
            toleranceSheetUnits = 0.01f
        )

        assertEquals(setOf(base.id), hit)
    }

    // --- Lasso ---

    @Test fun `a lasso fully surrounding a stroke selects it`() {
        val stroke = strokeAlongPoints(strokeId("50"), listOf(0.4f to 0.4f, 0.6f to 0.6f))
        val polygon = listOf(SheetPoint(0f, 0f), SheetPoint(1f, 0f), SheetPoint(1f, 1f), SheetPoint(0f, 1f))

        assertEquals(setOf(stroke.id), selectByLasso(listOf(stroke), polygon))
    }

    @Test fun `a lasso fully outside a stroke selects nothing`() {
        val stroke = strokeAlongPoints(strokeId("51"), listOf(5f to 5f, 6f to 6f))
        val polygon = listOf(SheetPoint(0f, 0f), SheetPoint(1f, 0f), SheetPoint(1f, 1f), SheetPoint(0f, 1f))

        assertTrue(selectByLasso(listOf(stroke), polygon).isEmpty())
    }

    @Test fun `a stroke sixty percent inside the lasso is selected`() {
        val stroke = strokeAlongPoints(strokeId("52"), listOf(0f to 0f, 1f to 0f))
        val polygon = listOf(SheetPoint(0f, -0.1f), SheetPoint(0.6f, -0.1f), SheetPoint(0.6f, 0.1f), SheetPoint(0f, 0.1f))

        assertEquals(setOf(stroke.id), selectByLasso(listOf(stroke), polygon))
    }

    @Test fun `a stroke forty percent inside the lasso is not selected`() {
        val stroke = strokeAlongPoints(strokeId("53"), listOf(0f to 0f, 1f to 0f))
        val polygon = listOf(SheetPoint(0f, -0.1f), SheetPoint(0.4f, -0.1f), SheetPoint(0.4f, 0.1f), SheetPoint(0f, 0.1f))

        assertTrue(selectByLasso(listOf(stroke), polygon).isEmpty())
    }

    @Test fun `a two-sample side crossing the lasso boundary is judged by length`() {
        val side = strokeAlongPoints(strokeId("54"), listOf(0f to 0f, 1f to 0f))
        val polygon = listOf(SheetPoint(0.4f, -1f), SheetPoint(2f, -1f), SheetPoint(2f, 1f), SheetPoint(0.4f, 1f))

        assertEquals(setOf(side.id), selectByLasso(listOf(side), polygon))
    }

    @Test fun `a concave lasso only counts the length actually inside it`() {
        val stroke = strokeAlongPoints(strokeId("55"), listOf(0f to 0.5f, 1f to 0.5f))
        // A unit square with a narrow slot cut down from its top edge, spanning x in 0.6..0.7 down to
        // y = 0.6: at y = 0.5 that slot is a gap in the polygon, so only 10% of the stroke's own length
        // (x in 0.6..0.7) falls outside it, and the other 90% (x in 0..0.6 and 0.7..1) is inside.
        val polygon = listOf(
            SheetPoint(0f, 0f), SheetPoint(0.6f, 0f), SheetPoint(0.6f, 0.6f), SheetPoint(0.7f, 0.6f),
            SheetPoint(0.7f, 0f), SheetPoint(1f, 0f), SheetPoint(1f, 1f), SheetPoint(0f, 1f)
        )

        assertEquals(setOf(stroke.id), selectByLasso(listOf(stroke), polygon))
    }

    @Test fun `a degenerate lasso with fewer than three points selects nothing`() {
        val stroke = strokeAlongPoints(strokeId("56"), listOf(0f to 0f, 1f to 1f))

        assertTrue(selectByLasso(listOf(stroke), listOf(SheetPoint(0f, 0f), SheetPoint(1f, 1f))).isEmpty())
    }

    @Test fun `a dot stroke is selected by whether its point lies inside the lasso`() {
        val inside = strokeAlongPoints(strokeId("57"), listOf(0.5f to 0.5f))
        val outside = strokeAlongPoints(strokeId("58"), listOf(5f to 5f))
        val polygon = listOf(SheetPoint(0f, 0f), SheetPoint(1f, 0f), SheetPoint(1f, 1f), SheetPoint(0f, 1f))

        assertEquals(setOf(inside.id), selectByLasso(listOf(inside, outside), polygon))
    }

    // --- Rectangle ---

    @Test fun `a rectangle selection works with corners given in either order`() {
        val stroke = strokeAlongPoints(strokeId("60"), listOf(0.4f to 0.4f, 0.6f to 0.6f))

        val selected = selectByRectangle(listOf(stroke), SheetPoint(1f, 1f), SheetPoint(0f, 0f))

        assertEquals(setOf(stroke.id), selected)
    }

    @Test fun `a rectangle selection excludes a stroke fully outside it`() {
        val stroke = strokeAlongPoints(strokeId("61"), listOf(5f to 5f, 6f to 6f))

        assertTrue(selectByRectangle(listOf(stroke), SheetPoint(0f, 0f), SheetPoint(1f, 1f)).isEmpty())
    }

    // --- Bounds ---

    @Test fun `selection bounds is null for an empty selection`() {
        assertNull(selectionBounds(emptyList()))
    }

    @Test fun `selection bounds unions the strokes' own bounds`() {
        val a = strokeAlongPoints(strokeId("70"), listOf(0f to 0f, 0.2f to 0f), widthSheetUnits = 0.1f)
        val b = strokeAlongPoints(strokeId("71"), listOf(0.5f to 0.5f, 0.5f to 0.7f), widthSheetUnits = 0.02f)

        val bounds = selectionBounds(listOf(a, b))!!
        val expected = a.bounds.union(b.bounds)

        assertEquals(expected.left, bounds.left, EPSILON)
        assertEquals(expected.top, bounds.top, EPSILON)
        assertEquals(expected.right, bounds.right, EPSILON)
        assertEquals(expected.bottom, bounds.bottom, EPSILON)
    }

    // --- Translate ---

    @Test fun `translate moves every sample and keeps channels, but takes fresh ids and sequences`() {
        val original = InkStroke(
            strokeId("80"), InkTool.PEN, InkTip.FOUNTAIN, colorArgb = 0xFF00FF, widthSheetUnits = 0.03f,
            inputKind = InkInputKind.STYLUS,
            samples = listOf(InkSample(0f, 0f, 0, pressure = 0.5f, tiltRadians = 0.1f, orientationRadians = 0.2f)),
            sequence = 3
        )

        val ids = listOf(strokeId("81")).iterator()
        val sequences = listOf(9L).iterator()
        val moved = translateStrokes(listOf(original), dx = 0.1f, dy = -0.2f, newId = { ids.next() }, newSequence = { sequences.next() })

        assertEquals(1, moved.size)
        val stroke = moved[0]
        assertEquals(strokeId("81"), stroke.id)
        assertEquals(9L, stroke.sequence)
        assertEquals(0.1f, stroke.samples[0].x, EPSILON)
        assertEquals(-0.2f, stroke.samples[0].y, EPSILON)
        assertEquals(original.tool, stroke.tool)
        assertEquals(original.tip, stroke.tip)
        assertEquals(original.colorArgb, stroke.colorArgb)
        assertEquals(original.widthSheetUnits, stroke.widthSheetUnits, EPSILON)
        assertEquals(original.inputKind, stroke.inputKind)
        assertEquals(0.5f, stroke.samples[0].pressure!!, EPSILON)
        assertEquals(0.1f, stroke.samples[0].tiltRadians!!, EPSILON)
        assertEquals(0.2f, stroke.samples[0].orientationRadians!!, EPSILON)
    }

    @Test fun `translate keeps the relative z-order of the copies`() {
        val first = strokeAlongPoints(strokeId("82"), listOf(0f to 0f, 1f to 1f), sequence = 0)
        val second = strokeAlongPoints(strokeId("83"), listOf(0f to 1f, 1f to 0f), sequence = 1)

        var nextSequence = 100L
        val moved = translateStrokes(
            listOf(first, second), dx = 0f, dy = 0f,
            newId = { strokeId("99") }, newSequence = { nextSequence++ }
        )

        assertTrue(moved[0].sequence < moved[1].sequence)
    }

    // --- Scale ---

    @Test fun `scale maps every sample about the anchor and leaves width unscaled`() {
        val original = strokeAlongPoints(strokeId("90"), listOf(1f to 1f, 3f to 3f), widthSheetUnits = 0.05f)
        val anchor = SheetPoint(1f, 1f)

        val scaled = scaleStrokes(
            listOf(original), anchor, scaleX = 2f, scaleY = 2f,
            newId = { strokeId("91") }, newSequence = { 5L }
        )

        assertEquals(1f, scaled[0].samples[0].x, EPSILON)
        assertEquals(1f, scaled[0].samples[0].y, EPSILON)
        assertEquals(5f, scaled[0].samples[1].x, EPSILON)
        assertEquals(5f, scaled[0].samples[1].y, EPSILON)
        assertEquals(original.widthSheetUnits, scaled[0].widthSheetUnits, EPSILON)
    }

    @Test fun `a negative scale factor flips the stroke about the anchor`() {
        val original = strokeAlongPoints(strokeId("92"), listOf(2f to 0f, 4f to 0f))
        val anchor = SheetPoint(1f, 0f)

        val scaled = scaleStrokes(listOf(original), anchor, scaleX = -1f, scaleY = 1f, newId = { strokeId("93") }, newSequence = { 1L })

        assertEquals(0f, scaled[0].samples[0].x, EPSILON)
        assertEquals(-2f, scaled[0].samples[1].x, EPSILON)
    }

    // --- Resize drag helper ---

    @Test fun `dragging the bottom-right corner keeps the top-left as anchor`() {
        val bounds = SheetRect(0f, 0f, 1f, 1f)

        val result = selectionResizeScale(bounds, SelectionCorner.BOTTOM_RIGHT, SheetPoint(2f, 3f))

        assertEquals(SheetPoint(0f, 0f), result.anchor)
        assertEquals(2f, result.scaleX, EPSILON)
        assertEquals(3f, result.scaleY, EPSILON)
    }

    @Test fun `dragging the top-left corner keeps the bottom-right as anchor`() {
        val bounds = SheetRect(0f, 0f, 1f, 1f)

        val result = selectionResizeScale(bounds, SelectionCorner.TOP_LEFT, SheetPoint(-1f, 0.5f))

        assertEquals(SheetPoint(1f, 1f), result.anchor)
        assertEquals(2f, result.scaleX, EPSILON)
        assertEquals(0.5f, result.scaleY, EPSILON)
    }

    @Test fun `dragging the top-right corner keeps the bottom-left as anchor`() {
        val bounds = SheetRect(0f, 0f, 1f, 1f)

        val result = selectionResizeScale(bounds, SelectionCorner.TOP_RIGHT, SheetPoint(2f, 0.5f))

        assertEquals(SheetPoint(0f, 1f), result.anchor)
        assertEquals(2f, result.scaleX, EPSILON)
        assertEquals(0.5f, result.scaleY, EPSILON)
    }

    @Test fun `dragging the bottom-left corner keeps the top-right as anchor`() {
        val bounds = SheetRect(0f, 0f, 1f, 1f)

        val result = selectionResizeScale(bounds, SelectionCorner.BOTTOM_LEFT, SheetPoint(-1f, 2f))

        assertEquals(SheetPoint(1f, 0f), result.anchor)
        assertEquals(2f, result.scaleX, EPSILON)
        assertEquals(2f, result.scaleY, EPSILON)
    }

    @Test fun `the resize drag helper never lets the selection collapse to zero`() {
        val bounds = SheetRect(0f, 0f, 1f, 1f)

        val result = selectionResizeScale(bounds, SelectionCorner.BOTTOM_RIGHT, SheetPoint(0f, 0f))

        val newSpanX = abs(result.scaleX)
        val newSpanY = abs(result.scaleY)
        assertTrue(newSpanX >= MIN_DIAGONAL_SHEET_UNITS)
        assertTrue(newSpanY >= MIN_DIAGONAL_SHEET_UNITS)
    }
}
