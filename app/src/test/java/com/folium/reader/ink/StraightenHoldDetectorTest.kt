package com.folium.reader.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SLOP_PX = 8f
private const val HOLD_MILLIS = 600L
private const val MIN_TRAVEL_PX = SLOP_PX * 4f

class StraightenHoldDetectorTest {

    @Test fun `fires once the pointer has travelled enough and then stayed still long enough`() {
        val detector = StraightenHoldDetector(SLOP_PX, HOLD_MILLIS)

        detector.onMove(0f, 0f, 0L)
        detector.onMove(0f, MIN_TRAVEL_PX + 1f, 100L)

        assertFalse(detector.isHeld(100L + HOLD_MILLIS - 1L))
        assertTrue(detector.isHeld(100L + HOLD_MILLIS))
    }

    @Test fun `jitter inside the slop does not reset the anchor's own clock`() {
        val detector = StraightenHoldDetector(SLOP_PX, HOLD_MILLIS)

        detector.onMove(0f, 0f, 0L)
        detector.onMove(0f, MIN_TRAVEL_PX + 1f, 100L)
        detector.onMove(0f, MIN_TRAVEL_PX + 1f + SLOP_PX / 2f, 300L)

        assertTrue(detector.isHeld(100L + HOLD_MILLIS))
    }

    @Test fun `moving beyond the slop resets the anchor's own clock`() {
        val detector = StraightenHoldDetector(SLOP_PX, HOLD_MILLIS)

        detector.onMove(0f, 0f, 0L)
        detector.onMove(0f, MIN_TRAVEL_PX + 1f, 100L)
        detector.onMove(0f, MIN_TRAVEL_PX + 1f + SLOP_PX * 2f, 300L)

        assertFalse(detector.isHeld(100L + HOLD_MILLIS))
        assertTrue(detector.isHeld(300L + HOLD_MILLIS))
    }

    @Test fun `never fires without enough prior travel, however long the pointer stays still`() {
        val detector = StraightenHoldDetector(SLOP_PX, HOLD_MILLIS)

        detector.onMove(0f, 0f, 0L)
        detector.onMove(0f, MIN_TRAVEL_PX - 1f, 100L)

        assertFalse(detector.isHeld(100L + HOLD_MILLIS))
        assertFalse(detector.isHeld(100L + HOLD_MILLIS * 10L))
    }

    @Test fun `does not fire again for the same stroke once it has already fired`() {
        val detector = StraightenHoldDetector(SLOP_PX, HOLD_MILLIS)

        detector.onMove(0f, 0f, 0L)
        detector.onMove(0f, MIN_TRAVEL_PX + 1f, 100L)
        assertTrue(detector.isHeld(100L + HOLD_MILLIS))

        detector.onMove(0f, MIN_TRAVEL_PX + 1f, 100L + HOLD_MILLIS)
        assertFalse(detector.isHeld(100L + HOLD_MILLIS * 2L))
    }

    @Test fun `reset clears every recorded point and the latch`() {
        val detector = StraightenHoldDetector(SLOP_PX, HOLD_MILLIS)

        detector.onMove(0f, 0f, 0L)
        detector.onMove(0f, MIN_TRAVEL_PX + 1f, 100L)
        assertTrue(detector.isHeld(100L + HOLD_MILLIS))

        detector.reset()

        assertFalse(detector.isHeld(100L + HOLD_MILLIS))
        assertNull(detector.nextCheckAtMillis())

        detector.onMove(0f, 0f, 1_000L)
        detector.onMove(0f, MIN_TRAVEL_PX + 1f, 1_100L)
        assertTrue(detector.isHeld(1_100L + HOLD_MILLIS))
    }

    @Test fun `the next check time tracks the current anchor until it fires`() {
        val detector = StraightenHoldDetector(SLOP_PX, HOLD_MILLIS)
        assertNull(detector.nextCheckAtMillis())

        detector.onMove(0f, 0f, 0L)
        assertEquals(HOLD_MILLIS, detector.nextCheckAtMillis())

        detector.onMove(0f, MIN_TRAVEL_PX + 1f, 100L)
        assertEquals(100L + HOLD_MILLIS, detector.nextCheckAtMillis())

        detector.isHeld(100L + HOLD_MILLIS)
        assertNull(detector.nextCheckAtMillis())
    }

    @Test fun `a closed shape that ends where it began still counts as travelled`() {
        val detector = StraightenHoldDetector(slopPx = 8f)

        detector.onMove(100f, 100f, 0L)
        detector.onMove(300f, 100f, 100L)
        detector.onMove(300f, 300f, 200L)
        detector.onMove(100f, 300f, 300L)
        detector.onMove(101f, 101f, 400L)

        assertTrue(detector.isHeld(nowMillis = 1_100L))
    }
}
