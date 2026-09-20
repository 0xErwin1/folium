package com.folium.reader.ink

import com.folium.reader.core.ink.InkInputKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkGestureArbiterTest {

    @Test
    fun singleFingerDownDrawsWhenPenIsSelected() {
        val arbiter = InkGestureArbiter()

        val canceled = arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.PEN)

        assertFalse(canceled)
        assertEquals(InkGesture.DRAW, arbiter.gesture)
    }

    @Test
    fun singleFingerDownErasesWhenEraserIsSelected() {
        val arbiter = InkGestureArbiter()

        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.ERASER)

        assertEquals(InkGesture.ERASE, arbiter.gesture)
    }

    @Test
    fun singleStylusDownDrawsRegardlessOfPriorStylusHistory() {
        val arbiter = InkGestureArbiter()

        arbiter.onPointerDown(InkInputKind.STYLUS, InkSurfaceTool.PEN)

        assertEquals(InkGesture.DRAW, arbiter.gesture)
    }

    @Test
    fun secondPointerDownCancelsAnInProgressDrawAndStartsPanZoom() {
        val arbiter = InkGestureArbiter()
        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.PEN)

        val canceled = arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.PEN)

        assertTrue(canceled)
        assertEquals(InkGesture.PAN_ZOOM, arbiter.gesture)
    }

    @Test
    fun secondPointerDownCancelsAnInProgressEraseAndStartsPanZoom() {
        val arbiter = InkGestureArbiter()
        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.ERASER)

        val canceled = arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.ERASER)

        assertTrue(canceled)
        assertEquals(InkGesture.PAN_ZOOM, arbiter.gesture)
    }

    @Test
    fun thirdPointerDownStaysInPanZoomWithoutReportingACancel() {
        val arbiter = InkGestureArbiter()
        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.PEN)
        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.PEN)

        val canceled = arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.PEN)

        assertFalse(canceled)
        assertEquals(InkGesture.PAN_ZOOM, arbiter.gesture)
    }

    @Test
    fun panZoomPersistsAfterOnePointerLiftsBackToOne() {
        val arbiter = InkGestureArbiter()
        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.PEN)
        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.PEN)

        arbiter.onPointerUp(remainingPointerCount = 1)

        assertEquals(InkGesture.PAN_ZOOM, arbiter.gesture)
    }

    @Test
    fun gestureBecomesIgnoreOnceEveryPointerHasLifted() {
        val arbiter = InkGestureArbiter()
        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.PEN)
        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.PEN)
        arbiter.onPointerUp(remainingPointerCount = 1)

        arbiter.onPointerUp(remainingPointerCount = 0)

        assertEquals(InkGesture.IGNORE, arbiter.gesture)
    }

    @Test
    fun singlePointerLiftEndsADrawGesture() {
        val arbiter = InkGestureArbiter()
        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.PEN)

        arbiter.onPointerUp(remainingPointerCount = 0)

        assertEquals(InkGesture.IGNORE, arbiter.gesture)
    }

    @Test
    fun cancelEndsTheGestureImmediately() {
        val arbiter = InkGestureArbiter()
        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.PEN)

        arbiter.onCancel()

        assertEquals(InkGesture.IGNORE, arbiter.gesture)
    }

    @Test
    fun aFingerOnlyPansOnceAStylusHasEverBeenSeen() {
        val arbiter = InkGestureArbiter()
        arbiter.onPointerDown(InkInputKind.STYLUS, InkSurfaceTool.PEN)
        arbiter.onPointerUp(remainingPointerCount = 0)

        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.PEN)

        assertEquals(InkGesture.PAN_ZOOM, arbiter.gesture)
    }

    @Test
    fun aFingerOnlyPansForTheEraserToolOnceAStylusHasEverBeenSeen() {
        val arbiter = InkGestureArbiter()
        arbiter.onPointerDown(InkInputKind.STYLUS, InkSurfaceTool.PEN)
        arbiter.onPointerUp(remainingPointerCount = 0)

        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.ERASER)

        assertEquals(InkGesture.PAN_ZOOM, arbiter.gesture)
    }

    @Test
    fun stylusSeenDuringASecondPointerStillAffectsLaterGestures() {
        val arbiter = InkGestureArbiter()
        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.PEN)
        arbiter.onPointerDown(InkInputKind.STYLUS, InkSurfaceTool.PEN)
        arbiter.onPointerUp(remainingPointerCount = 1)
        arbiter.onPointerUp(remainingPointerCount = 0)

        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.PEN)

        assertEquals(InkGesture.PAN_ZOOM, arbiter.gesture)
    }

    @Test
    fun mouseDrawsLikeAFingerWhenNoStylusHasBeenSeen() {
        val arbiter = InkGestureArbiter()

        arbiter.onPointerDown(InkInputKind.MOUSE, InkSurfaceTool.PEN)

        assertEquals(InkGesture.DRAW, arbiter.gesture)
    }

    @Test
    fun idleArbiterStartsAsIgnore() {
        val arbiter = InkGestureArbiter()

        assertEquals(InkGesture.IGNORE, arbiter.gesture)
    }

    @Test
    fun viewToolPansWithASingleFinger() {
        val arbiter = InkGestureArbiter()

        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.VIEW)

        assertEquals(InkGesture.PAN_ZOOM, arbiter.gesture)
    }

    @Test
    fun viewToolPansWithTheStylus() {
        val arbiter = InkGestureArbiter()

        arbiter.onPointerDown(InkInputKind.STYLUS, InkSurfaceTool.VIEW)

        assertEquals(InkGesture.PAN_ZOOM, arbiter.gesture)
    }

    @Test
    fun viewToolPansWithTheMouse() {
        val arbiter = InkGestureArbiter()

        arbiter.onPointerDown(InkInputKind.MOUSE, InkSurfaceTool.VIEW)

        assertEquals(InkGesture.PAN_ZOOM, arbiter.gesture)
    }

    @Test
    fun viewToolNeverDrawsOrErasesEvenAfterAStylusHasBeenSeen() {
        val arbiter = InkGestureArbiter()
        arbiter.onPointerDown(InkInputKind.STYLUS, InkSurfaceTool.PEN)
        arbiter.onPointerUp(remainingPointerCount = 0)

        arbiter.onPointerDown(InkInputKind.STYLUS, InkSurfaceTool.VIEW)

        assertEquals(InkGesture.PAN_ZOOM, arbiter.gesture)
    }

    @Test
    fun aSecondPointerJoiningTheViewToolStaysPanZoomWithoutReportingACancel() {
        val arbiter = InkGestureArbiter()
        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.VIEW)

        val canceled = arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.VIEW)

        assertFalse(canceled)
        assertEquals(InkGesture.PAN_ZOOM, arbiter.gesture)
    }

    @Test
    fun switchingFromViewBackToPenRestoresDrawing() {
        val arbiter = InkGestureArbiter()
        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.VIEW)
        arbiter.onPointerUp(remainingPointerCount = 0)

        arbiter.onPointerDown(InkInputKind.FINGER, InkSurfaceTool.PEN)

        assertEquals(InkGesture.DRAW, arbiter.gesture)
    }
}
