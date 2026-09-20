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

    @Test fun `for a small selection the body always wins over a corner even when a touch lands inside near it`() {
        val small = ViewRect(left = 100f, top = 200f, right = 130f, bottom = 230f)
        val insideNearTopLeft = ViewPoint(small.left + 5f, small.top + 5f)

        assertEquals(SelectionTouchTarget.Body, selectionTouchTarget(insideNearTopLeft, small, HIT_RADIUS_PX))
    }

    @Test fun `for a small selection a corner is still grabbed from just outside the frame`() {
        val small = ViewRect(left = 100f, top = 200f, right = 130f, bottom = 230f)
        val justOutsideTopLeft = ViewPoint(small.left - 5f, small.top + 5f)

        assertEquals(SelectionTouchTarget.Handle(SelectionCorner.TOP_LEFT), selectionTouchTarget(justOutsideTopLeft, small, HIT_RADIUS_PX))
    }

    @Test fun `for a small selection a touch far from the frame still resolves to no target`() {
        val small = ViewRect(left = 100f, top = 200f, right = 130f, bottom = 230f)
        val farAway = ViewPoint(small.right + 100f, small.bottom + 100f)

        assertEquals(SelectionTouchTarget.None, selectionTouchTarget(farAway, small, HIT_RADIUS_PX))
    }

    @Test fun `a frame at the exact small-selection threshold still forces the body inside near a corner`() {
        // Diagonal squared is exactly 4 * HIT_RADIUS_PX^2 (44^2 = 4 * 22^2), the boundary [selectionTouchTarget] treats as small.
        val atThreshold = ViewRect(left = 100f, top = 200f, right = 144f, bottom = 200f)
        val insideNearTopLeft = ViewPoint(atThreshold.left + 5f, atThreshold.top)

        assertEquals(SelectionTouchTarget.Body, selectionTouchTarget(insideNearTopLeft, atThreshold, HIT_RADIUS_PX))
    }

    @Test fun `a large selection just past the small-selection threshold keeps the handle-wins rule`() {
        val large = ViewRect(left = 100f, top = 200f, right = 300f, bottom = 400f)
        val insideNearTopLeft = ViewPoint(large.left + 5f, large.top + 5f)

        assertEquals(SelectionTouchTarget.Handle(SelectionCorner.TOP_LEFT), selectionTouchTarget(insideNearTopLeft, large, HIT_RADIUS_PX))
    }
}
