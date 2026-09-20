package com.folium.reader.ink

import com.folium.reader.core.ink.SelectionCorner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val BOUNDS = ViewRect(left = 100f, top = 200f, right = 300f, bottom = 400f)
private const val HIT_RADIUS_PX = 22f

class SelectionTouchTest {

    @Test fun `a touch within the hit radius of a corner resolves to that corner`() {
        val nearBottomRight = ViewPoint(BOUNDS.right + 5f, BOUNDS.bottom - 5f)

        assertEquals(SelectionCorner.BOTTOM_RIGHT, selectionCornerAt(nearBottomRight, BOUNDS, HIT_RADIUS_PX))
    }

    @Test fun `a touch past every corner's own hit radius resolves to no corner`() {
        val center = ViewPoint((BOUNDS.left + BOUNDS.right) / 2f, (BOUNDS.top + BOUNDS.bottom) / 2f)

        assertNull(selectionCornerAt(center, BOUNDS, HIT_RADIUS_PX))
    }

    @Test fun `a touch inside the selection's own body is inside its bounds`() {
        val center = ViewPoint((BOUNDS.left + BOUNDS.right) / 2f, (BOUNDS.top + BOUNDS.bottom) / 2f)

        assertEquals(true, isInsideSelectionBounds(center, BOUNDS))
    }

    @Test fun `a touch outside every edge is outside the bounds`() {
        val outside = ViewPoint(BOUNDS.right + 100f, BOUNDS.bottom + 100f)

        assertEquals(false, isInsideSelectionBounds(outside, BOUNDS))
    }

    @Test fun `a touch target resolves to a handle when near a corner`() {
        val nearTopLeft = ViewPoint(BOUNDS.left - 5f, BOUNDS.top + 5f)

        assertEquals(SelectionTouchTarget.Handle(SelectionCorner.TOP_LEFT), selectionTouchTarget(nearTopLeft, BOUNDS, HIT_RADIUS_PX))
    }

    @Test fun `a touch target resolves to the body when inside the bounds but away from every corner`() {
        val center = ViewPoint((BOUNDS.left + BOUNDS.right) / 2f, (BOUNDS.top + BOUNDS.bottom) / 2f)

        assertEquals(SelectionTouchTarget.Body, selectionTouchTarget(center, BOUNDS, HIT_RADIUS_PX))
    }

    @Test fun `a handle wins over the body when a touch lands inside the bounds but still within a corner's own hit radius`() {
        // Just inside the top-left corner, so it is both within the corner's hit radius and inside the rect.
        val insideNearCorner = ViewPoint(BOUNDS.left + 5f, BOUNDS.top + 5f)

        assertEquals(SelectionTouchTarget.Handle(SelectionCorner.TOP_LEFT), selectionTouchTarget(insideNearCorner, BOUNDS, HIT_RADIUS_PX))
    }

    @Test fun `a touch target resolves to none outside the bounds and every handle`() {
        val outside = ViewPoint(BOUNDS.right + 100f, BOUNDS.bottom + 100f)

        assertEquals(SelectionTouchTarget.None, selectionTouchTarget(outside, BOUNDS, HIT_RADIUS_PX))
    }
}
