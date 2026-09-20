package com.folium.reader.ink

import com.folium.reader.core.ink.SheetPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PenStraightenTrackerTest {

    @Test fun `the hold is judged in view pixels while the points are kept in sheet space`() {
        val tracker = PenStraightenTracker(slopPx = 16f)

        tracker.onDown(SheetPoint(0.10f, 0.10f), viewXPx = 80f, viewYPx = 80f, atMillis = 0L)
        tracker.onMove(SheetPoint(0.50f, 0.10f), viewXPx = 400f, viewYPx = 80f, atMillis = 200L)
        tracker.onMove(SheetPoint(0.501f, 0.10f), viewXPx = 401f, viewYPx = 80f, atMillis = 300L)

        assertTrue(tracker.isHeld(nowMillis = 1_000L))
        assertEquals(listOf(SheetPoint(0.10f, 0.10f), SheetPoint(0.50f, 0.10f), SheetPoint(0.501f, 0.10f)), tracker.points)
    }
}
