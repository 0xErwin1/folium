package com.folium.reader.ink

import com.folium.reader.core.ink.SelectionCorner
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetRect
import com.folium.reader.core.ink.selectionResizeScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val BOUNDS = SheetRect(left = 0.2f, top = 1f, right = 0.6f, bottom = 2f)

/** Float subtraction/addition in sheet units is not always exact; every comparison below tolerates this much drift. */
private const val EPSILON = 1e-5f

class SelectionEditSessionTest {

    @Test fun `a move session reports the sheet-space delta between the down and the current point`() {
        val session = SelectionEditSession(SelectionEditKind.Move, BOUNDS, SheetPoint(0.3f, 1.5f))

        session.onMove(SheetPoint(0.35f, 1.6f))

        assertEquals(0.05f, session.translation.x, EPSILON)
        assertEquals(0.1f, session.translation.y, EPSILON)
    }

    @Test fun `a move session's own preview bounds shift by its own translation`() {
        val session = SelectionEditSession(SelectionEditKind.Move, BOUNDS, SheetPoint(0.3f, 1.5f))

        session.onMove(SheetPoint(0.4f, 1.5f))

        val bounds = session.previewBounds()
        assertEquals(0.3f, bounds.left, EPSILON)
        assertEquals(1f, bounds.top, EPSILON)
        assertEquals(0.7f, bounds.right, EPSILON)
        assertEquals(2f, bounds.bottom, EPSILON)
    }

    @Test fun `a move session that ends where it started has not changed`() {
        val session = SelectionEditSession(SelectionEditKind.Move, BOUNDS, SheetPoint(0.3f, 1.5f))

        session.onMove(SheetPoint(0.3f, 1.5f))

        assertFalse(session.hasChanged())
    }

    @Test fun `a move session that ends anywhere else has changed`() {
        val session = SelectionEditSession(SelectionEditKind.Move, BOUNDS, SheetPoint(0.3f, 1.5f))

        session.onMove(SheetPoint(0.3f, 1.51f))

        assertTrue(session.hasChanged())
    }

    @Test fun `a move session with a vertical snap rounds its own vertical translation to the nearest grid multiple`() {
        val session = SelectionEditSession(SelectionEditKind.Move, BOUNDS, SheetPoint(0.3f, 1.5f), verticalSnapUnits = 0.032f)

        session.onMove(SheetPoint(0.35f, 1.54f))

        assertEquals(0.05f, session.translation.x, EPSILON)
        assertEquals(0.032f, session.translation.y, EPSILON)
    }

    @Test fun `a move session with a vertical snap rounds down to zero for a displacement under half the grid`() {
        val session = SelectionEditSession(SelectionEditKind.Move, BOUNDS, SheetPoint(0.3f, 1.5f), verticalSnapUnits = 0.032f)

        session.onMove(SheetPoint(0.3f, 1.51f))

        assertEquals(0f, session.translation.y, EPSILON)
        assertFalse(session.hasChanged())
    }

    @Test fun `a move session with no vertical snap moves freely on both axes`() {
        val session = SelectionEditSession(SelectionEditKind.Move, BOUNDS, SheetPoint(0.3f, 1.5f))

        session.onMove(SheetPoint(0.35f, 1.54f))

        assertEquals(0.04f, session.translation.y, EPSILON)
    }

    @Test fun `a resize session ignores its own vertical snap and tracks the pointer exactly`() {
        val corner = SelectionCorner.BOTTOM_RIGHT
        val session = SelectionEditSession(SelectionEditKind.Resize(corner), BOUNDS, SheetPoint(0.6f, 2f), verticalSnapUnits = 0.032f)

        session.onMove(SheetPoint(0.8f, 2.01f))

        assertEquals(0.01f, session.translation.y, EPSILON)
    }

    @Test fun `a resize session's own dragged corner point tracks the pointer's displacement from the corner's own start`() {
        val corner = SelectionCorner.BOTTOM_RIGHT
        val session = SelectionEditSession(SelectionEditKind.Resize(corner), BOUNDS, SheetPoint(0.63f, 2.04f))

        session.onMove(SheetPoint(0.73f, 2.14f))

        val dragged = session.draggedCornerPointSheet()
        assertEquals(0.7f, dragged.x, EPSILON)
        assertEquals(2.1f, dragged.y, EPSILON)
    }

    @Test fun `a resize session delegates to selectionResizeScale from its own start bounds and corner`() {
        val corner = SelectionCorner.BOTTOM_RIGHT
        val dragPoint = SheetPoint(0.8f, 3f)
        val session = SelectionEditSession(SelectionEditKind.Resize(corner), BOUNDS, SheetPoint(0.6f, 2f))

        session.onMove(dragPoint)

        assertEquals(selectionResizeScale(BOUNDS, corner, dragPoint), session.resizeScale())
    }

    @Test fun `a resize session that ends where it started has not changed`() {
        val corner = SelectionCorner.BOTTOM_RIGHT
        val downPoint = SheetPoint(0.6f, 2f)
        val session = SelectionEditSession(SelectionEditKind.Resize(corner), BOUNDS, downPoint)

        session.onMove(downPoint)

        assertFalse(session.hasChanged())
    }

    @Test fun `a resize session grabbed off its corner leaves the selection untouched until the pointer moves`() {
        val corner = SelectionCorner.BOTTOM_RIGHT
        val session = SelectionEditSession(SelectionEditKind.Resize(corner), BOUNDS, SheetPoint(0.63f, 2.04f))

        assertFalse(session.hasChanged())
        assertEquals(1f, session.resizeScale().scaleX, EPSILON)
        assertEquals(1f, session.resizeScale().scaleY, EPSILON)
    }

    @Test fun `a resize session grabbed off its corner moves the corner by the pointer's displacement`() {
        val corner = SelectionCorner.BOTTOM_RIGHT
        val session = SelectionEditSession(SelectionEditKind.Resize(corner), BOUNDS, SheetPoint(0.63f, 2.04f))

        session.onMove(SheetPoint(1.03f, 3.04f))

        val bounds = session.previewBounds()
        assertEquals(1f, bounds.right, EPSILON)
        assertEquals(3f, bounds.bottom, EPSILON)
    }

    @Test fun `a resize session's own preview bounds follow the anchor and scale of the dragged corner`() {
        val corner = SelectionCorner.BOTTOM_RIGHT
        val session = SelectionEditSession(SelectionEditKind.Resize(corner), BOUNDS, SheetPoint(0.6f, 2f))

        session.onMove(SheetPoint(1f, 3f))

        // The anchor is the opposite corner, TOP_LEFT (0.2, 1); the drag doubles both spans.
        val bounds = session.previewBounds()
        assertEquals(0.2f, bounds.left, EPSILON)
        assertEquals(1f, bounds.top, EPSILON)
        assertEquals(1f, bounds.right, EPSILON)
        assertEquals(3f, bounds.bottom, EPSILON)
        assertTrue(session.hasChanged())
    }

    @Test fun `a resize session's own preview bounds stay normalized when a drag flips the selection through its anchor`() {
        val corner = SelectionCorner.BOTTOM_RIGHT
        val session = SelectionEditSession(SelectionEditKind.Resize(corner), BOUNDS, SheetPoint(0.6f, 2f))

        // Dragging the bottom-right corner past the anchor (top-left) flips the rect: left > right before normalizing.
        session.onMove(SheetPoint(0f, 0.5f))

        val bounds = session.previewBounds()
        assertTrue(bounds.left <= bounds.right)
        assertTrue(bounds.top <= bounds.bottom)
    }
}
