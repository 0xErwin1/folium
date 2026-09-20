package com.folium.reader.core.ink

import org.junit.Assert.assertEquals
import org.junit.Test

private const val EPSILON = 1e-4f

private fun assertPointEquals(expected: SheetPoint, actual: SheetPoint) {
    assertEquals("x", expected.x, actual.x, EPSILON)
    assertEquals("y", expected.y, actual.y, EPSILON)
}

class InkShapeResizeTest {

    @Test fun `zero delta returns an equal shape`() {
        val recognized = RecognizedShape(InkShape.BOX, SheetPoint(0.1f, 0.1f), SheetPoint(0.3f, 0.4f))
        val fingerAtSnap = SheetPoint(0.3f, 0.4f)

        val resized = resizeRecognizedShape(recognized, fingerAtSnap, fingerAtSnap)

        assertEquals(recognized, resized)
    }

    @Test fun `a line keeps its start and moves its end by the finger delta`() {
        val recognized = RecognizedShape(InkShape.LINE, SheetPoint(0.1f, 0.1f), SheetPoint(0.4f, 0.1f))
        val fingerAtSnap = SheetPoint(0.4f, 0.1f)
        val fingerNow = SheetPoint(0.5f, 0.2f)

        val resized = resizeRecognizedShape(recognized, fingerAtSnap, fingerNow)

        assertPointEquals(SheetPoint(0.1f, 0.1f), resized.start)
        assertPointEquals(SheetPoint(0.5f, 0.2f), resized.end)
    }

    @Test fun `an arrow keeps its start and moves its end by the finger delta`() {
        val recognized = RecognizedShape(InkShape.ARROW, SheetPoint(0.1f, 0.1f), SheetPoint(0.4f, 0.1f))
        val fingerAtSnap = SheetPoint(0.4f, 0.1f)
        val fingerNow = SheetPoint(0.35f, 0.05f)

        val resized = resizeRecognizedShape(recognized, fingerAtSnap, fingerNow)

        assertPointEquals(SheetPoint(0.1f, 0.1f), resized.start)
        assertPointEquals(SheetPoint(0.35f, 0.05f), resized.end)
    }

    @Test fun `a line resized under the minimum length is clamped to it along its own direction`() {
        val recognized = RecognizedShape(InkShape.LINE, SheetPoint(0.1f, 0.1f), SheetPoint(0.4f, 0.1f))
        val fingerAtSnap = SheetPoint(0.4f, 0.1f)
        val fingerNow = SheetPoint(0.105f, 0.1f)

        val resized = resizeRecognizedShape(recognized, fingerAtSnap, fingerNow)

        assertPointEquals(SheetPoint(0.1f, 0.1f), resized.start)
        assertEquals(MIN_DIAGONAL_SHEET_UNITS, distance(resized.start, resized.end), EPSILON)
        assertEquals(0.1f + MIN_DIAGONAL_SHEET_UNITS, resized.end.x, EPSILON)
        assertEquals(0.1f, resized.end.y, EPSILON)
    }

    @Test fun `a box anchors the corner farthest from the finger and moves the opposite one`() {
        val recognized = RecognizedShape(InkShape.BOX, SheetPoint(0.1f, 0.1f), SheetPoint(0.3f, 0.3f))
        val fingerAtSnap = SheetPoint(0.3f, 0.3f)
        val fingerNow = SheetPoint(0.5f, 0.6f)

        val resized = resizeRecognizedShape(recognized, fingerAtSnap, fingerNow)

        assertPointEquals(SheetPoint(0.1f, 0.1f), resized.start)
        assertPointEquals(SheetPoint(0.5f, 0.6f), resized.end)
    }

    @Test fun `a box dragged past its anchor flips into a valid, normalised box`() {
        val recognized = RecognizedShape(InkShape.BOX, SheetPoint(0.1f, 0.1f), SheetPoint(0.3f, 0.3f))
        val fingerAtSnap = SheetPoint(0.3f, 0.3f)
        val fingerNow = SheetPoint(-0.1f, 0.3f)

        val resized = resizeRecognizedShape(recognized, fingerAtSnap, fingerNow)

        assertPointEquals(SheetPoint(-0.1f, 0.1f), resized.start)
        assertPointEquals(SheetPoint(0.1f, 0.3f), resized.end)
    }

    @Test fun `an ellipse anchors the same way a box does`() {
        val recognized = RecognizedShape(InkShape.ELLIPSE, SheetPoint(0.2f, 0.2f), SheetPoint(0.6f, 0.4f))
        val fingerAtSnap = SheetPoint(0.2f, 0.4f)
        val fingerNow = SheetPoint(0.1f, 0.5f)

        val resized = resizeRecognizedShape(recognized, fingerAtSnap, fingerNow)

        assertPointEquals(SheetPoint(0.1f, 0.2f), resized.start)
        assertPointEquals(SheetPoint(0.6f, 0.5f), resized.end)
    }

    @Test fun `a box shrunk under the minimum span is clamped on the dragged axis`() {
        val recognized = RecognizedShape(InkShape.BOX, SheetPoint(0.1f, 0.1f), SheetPoint(0.3f, 0.5f))
        val fingerAtSnap = SheetPoint(0.3f, 0.5f)
        val fingerNow = SheetPoint(0.105f, 0.5f)

        val resized = resizeRecognizedShape(recognized, fingerAtSnap, fingerNow)

        assertPointEquals(SheetPoint(0.1f, 0.1f), resized.start)
        assertEquals(0.1f + MIN_DIAGONAL_SHEET_UNITS, resized.end.x, EPSILON)
        assertEquals(0.5f, resized.end.y, EPSILON)
    }

    @Test fun `a triangle's vertices map affinely from the old bounding box to the new one`() {
        val vertices = listOf(SheetPoint(0.2f, 0.1f), SheetPoint(0.3f, 0.3f), SheetPoint(0.1f, 0.3f))
        val recognized = RecognizedShape(InkShape.TRIANGLE, SheetPoint(0.1f, 0.1f), SheetPoint(0.3f, 0.3f), vertices)
        val fingerAtSnap = SheetPoint(0.3f, 0.3f)
        val fingerNow = SheetPoint(0.5f, 0.3f)

        val resized = resizeRecognizedShape(recognized, fingerAtSnap, fingerNow)

        // anchor is (0.1, 0.1); the dragged corner (0.3, 0.3) becomes (0.5, 0.3): scaleX = 2, scaleY = 1
        assertPointEquals(SheetPoint(0.1f + (0.2f - 0.1f) * 2f, 0.1f), resized.vertices[0])
        assertPointEquals(SheetPoint(0.1f + (0.3f - 0.1f) * 2f, 0.3f), resized.vertices[1])
        assertPointEquals(SheetPoint(0.1f + (0.1f - 0.1f) * 2f, 0.3f), resized.vertices[2])
        assertPointEquals(SheetPoint(0.1f, 0.1f), resized.start)
        assertPointEquals(SheetPoint(0.5f, 0.3f), resized.end)
    }

    @Test fun `a triangle flipped across its anchor mirrors its vertices instead of collapsing`() {
        val vertices = listOf(SheetPoint(0.2f, 0.1f), SheetPoint(0.3f, 0.3f), SheetPoint(0.1f, 0.3f))
        val recognized = RecognizedShape(InkShape.TRIANGLE, SheetPoint(0.1f, 0.1f), SheetPoint(0.3f, 0.3f), vertices)
        val fingerAtSnap = SheetPoint(0.3f, 0.3f)
        val fingerNow = SheetPoint(-0.1f, 0.3f)

        val resized = resizeRecognizedShape(recognized, fingerAtSnap, fingerNow)

        // anchor is (0.1, 0.1); the dragged corner (0.3, 0.3) becomes (-0.1, 0.3): scaleX = -1, scaleY = 1
        assertPointEquals(SheetPoint(0.1f - (0.2f - 0.1f), 0.1f), resized.vertices[0])
        assertPointEquals(SheetPoint(0.1f - (0.3f - 0.1f), 0.3f), resized.vertices[1])
        assertPointEquals(SheetPoint(0.1f - (0.1f - 0.1f), 0.3f), resized.vertices[2])
        assertPointEquals(SheetPoint(-0.1f, 0.1f), resized.start)
        assertPointEquals(SheetPoint(0.1f, 0.3f), resized.end)
    }

    private fun distance(a: SheetPoint, b: SheetPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }
}
