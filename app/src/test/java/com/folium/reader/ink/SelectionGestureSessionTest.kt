package com.folium.reader.ink

import com.folium.reader.core.ink.SheetPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SLOP_PX = 8f

class SelectionGestureSessionTest {

    @Test fun `a gesture that never leaves the slop never starts dragging`() {
        val session = SelectionGestureSession(SLOP_PX)

        session.onDown(SheetPoint(0.1f, 0.1f), viewX = 10f, viewY = 10f)
        session.onMove(SheetPoint(0.11f, 0.1f), viewX = 12f, viewY = 10f)

        assertFalse(session.isDragging)
    }

    @Test fun `a gesture that clears the slop starts dragging`() {
        val session = SelectionGestureSession(SLOP_PX)

        session.onDown(SheetPoint(0.1f, 0.1f), viewX = 10f, viewY = 10f)
        session.onMove(SheetPoint(0.3f, 0.1f), viewX = 30f, viewY = 10f)

        assertTrue(session.isDragging)
    }

    @Test fun `dragging latches even if a later move falls back inside the slop`() {
        val session = SelectionGestureSession(SLOP_PX)

        session.onDown(SheetPoint(0f, 0f), viewX = 0f, viewY = 0f)
        session.onMove(SheetPoint(0.3f, 0f), viewX = 30f, viewY = 0f)
        session.onMove(SheetPoint(0.001f, 0f), viewX = 1f, viewY = 0f)

        assertTrue(session.isDragging)
    }

    @Test fun `down and current points track the pointer's own down and most recent position`() {
        val session = SelectionGestureSession(SLOP_PX)

        session.onDown(SheetPoint(0.1f, 0.2f), viewX = 10f, viewY = 20f)
        session.onMove(SheetPoint(0.4f, 0.5f), viewX = 40f, viewY = 50f)

        assertEquals(SheetPoint(0.1f, 0.2f), session.downPoint)
        assertEquals(SheetPoint(0.4f, 0.5f), session.currentPoint)
    }

    @Test fun `the lasso path holds only the down point until the gesture starts dragging`() {
        val session = SelectionGestureSession(SLOP_PX)

        session.onDown(SheetPoint(0f, 0f), viewX = 0f, viewY = 0f)
        session.onMove(SheetPoint(0.001f, 0f), viewX = 1f, viewY = 0f)

        assertEquals(listOf(SheetPoint(0f, 0f)), session.lassoPoints)
    }

    @Test fun `the lasso path decimates points closer than its own minimum spacing`() {
        val session = SelectionGestureSession(SLOP_PX)

        session.onDown(SheetPoint(0f, 0f), viewX = 0f, viewY = 0f)
        session.onMove(SheetPoint(1f, 0f), viewX = 20f, viewY = 0f)
        session.onMove(SheetPoint(1.01f, 0f), viewX = 21f, viewY = 0f)
        session.onMove(SheetPoint(2f, 0f), viewX = 40f, viewY = 0f)

        assertEquals(listOf(SheetPoint(0f, 0f), SheetPoint(1f, 0f), SheetPoint(2f, 0f)), session.lassoPoints)
    }

    @Test fun `a fresh onDown discards a previous gesture's own state`() {
        val session = SelectionGestureSession(SLOP_PX)
        session.onDown(SheetPoint(0f, 0f), viewX = 0f, viewY = 0f)
        session.onMove(SheetPoint(1f, 0f), viewX = 30f, viewY = 0f)

        session.onDown(SheetPoint(5f, 5f), viewX = 5f, viewY = 5f)

        assertFalse(session.isDragging)
        assertEquals(listOf(SheetPoint(5f, 5f)), session.lassoPoints)
        assertEquals(SheetPoint(5f, 5f), session.downPoint)
        assertEquals(SheetPoint(5f, 5f), session.currentPoint)
    }
}
