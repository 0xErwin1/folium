package com.folium.reader.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PAN_SLOP_PX = 8f

private fun points(vararg xy: Float): List<PointerPosition> =
    xy.toList().chunked(2).map { (x, y) -> PointerPosition(x, y) }

class PanZoomTrackerTest {

    @Test
    fun aTwoFingerScrollWithSpanJitterNeverYieldsAZoom() {
        val tracker = PanZoomTracker(PAN_SLOP_PX)
        tracker.rebaseline(points(100f, 500f, 300f, 500f))

        var y = 500f
        val spans = listOf(0f, 3f, -3f, 2f, -2f, 3f, -3f)
        for (jitter in spans) {
            y -= 20f
            val step = tracker.onMove(points(100f, y, 300f + jitter, y))
            assertEquals(1f, step.zoomFactor, 0f)
        }
    }

    @Test
    fun aSlowDeliberatePinchEntersZoomAfterTwelvePercentWithoutTheWithheldChange() {
        val tracker = PanZoomTracker(PAN_SLOP_PX)
        tracker.rebaseline(points(100f, 500f, 300f, 500f))

        val stillUndecided = tracker.onMove(points(95f, 500f, 305f, 500f))
        assertEquals(1f, stillUndecided.zoomFactor, 0f)

        val entersZoom = tracker.onMove(points(75f, 500f, 325f, 500f))
        assertEquals(1f, entersZoom.zoomFactor, 0f)

        val firstRealZoomStep = tracker.onMove(points(50f, 500f, 350f, 500f))
        assertTrue(firstRealZoomStep.zoomFactor > 1f)
    }

    @Test
    fun aPinchStartedAfterPanWasDecidedBreaksOutOnlyPastTwentyFivePercent() {
        val tracker = PanZoomTracker(PAN_SLOP_PX)
        tracker.rebaseline(points(100f, 500f, 300f, 500f))

        val decidesPan = tracker.onMove(points(100f, 480f, 300f, 480f))
        assertEquals(1f, decidesPan.zoomFactor, 0f)

        val underBreakout = tracker.onMove(points(80f, 480f, 320f, 480f))
        assertEquals(1f, underBreakout.zoomFactor, 0f)

        val breaksOut = tracker.onMove(points(30f, 480f, 370f, 480f))
        assertEquals(1f, breaksOut.zoomFactor, 0f)

        val firstRealZoomStep = tracker.onMove(points(10f, 480f, 390f, 480f))
        assertTrue(firstRealZoomStep.zoomFactor > 1f)
    }

    @Test
    fun panIsAppliedFromTheVeryFirstMoveWhileUndecided() {
        val tracker = PanZoomTracker(PAN_SLOP_PX)
        tracker.rebaseline(points(100f, 500f, 300f, 500f))

        val step = tracker.onMove(points(105f, 495f, 305f, 495f))

        assertEquals(5f, step.panDxPx, 0f)
        assertEquals(-5f, step.panDyPx, 0f)
    }

    @Test
    fun liftingOneOfTwoFingersProducesNoPanJump() {
        val tracker = PanZoomTracker(PAN_SLOP_PX)
        tracker.rebaseline(points(100f, 500f, 300f, 500f))
        tracker.onMove(points(110f, 500f, 310f, 500f))

        tracker.rebaseline(points(310f, 500f))
        val step = tracker.onMove(points(320f, 500f))

        assertEquals(10f, step.panDxPx, 0f)
        assertEquals(0f, step.panDyPx, 0f)
        assertEquals(1f, step.zoomFactor, 0f)
    }

    @Test
    fun droppingToOnePointerAndReturningToTwoStartsUndecidedAgain() {
        val tracker = PanZoomTracker(PAN_SLOP_PX)
        tracker.rebaseline(points(100f, 500f, 300f, 500f))
        // Enters ZOOM with a span baseline of 170, which a missed reset would leak into the new gesture.
        tracker.onMove(points(30f, 500f, 370f, 500f))

        tracker.rebaseline(points(370f, 500f))
        tracker.rebaseline(points(50f, 500f, 650f, 500f))

        val smallJitter = tracker.onMove(points(50f, 500f, 656f, 500f))
        assertEquals(1f, smallJitter.zoomFactor, 0f)
    }

    @Test
    fun coincidentPointersDoNotProduceNanOrInfinity() {
        val tracker = PanZoomTracker(PAN_SLOP_PX)
        tracker.rebaseline(points(200f, 500f, 200f, 500f))

        val step = tracker.onMove(points(210f, 500f, 210f, 500f))

        assertFalse(step.zoomFactor.isNaN())
        assertFalse(step.zoomFactor.isInfinite())
    }

    @Test
    fun onePointerMovesPanOnly() {
        val tracker = PanZoomTracker(PAN_SLOP_PX)
        tracker.rebaseline(points(100f, 500f))

        val step = tracker.onMove(points(150f, 520f))

        assertEquals(50f, step.panDxPx, 0f)
        assertEquals(20f, step.panDyPx, 0f)
        assertEquals(1f, step.zoomFactor, 0f)
    }
}
