package com.folium.reader.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val EPSILON = 1e-4f

class PageImePanLedgerTest {

    private val viewWidthPx = 1000f
    private val viewHeightPx = 2000f

    private fun PageImePanLedger.step(neededPx: Float?, frameOriginYPx: Float): PanZoomStep? =
        nextStep(neededPx, frameOriginYPx, viewWidthPx, viewHeightPx)

    @Test fun `the first request pans the page up by the whole need, as a finger dragging up would`() {
        val step = PageImePanLedger().step(neededPx = 120f, frameOriginYPx = 0f)!!

        assertEquals(0f, step.panDxPx, EPSILON)
        assertEquals(-120f, step.panDyPx, EPSILON)
        assertEquals(1f, step.zoomFactor, EPSILON)
        assertEquals(500f, step.focalX, EPSILON)
        assertEquals(1000f, step.focalY, EPSILON)
    }

    @Test fun `a repeated inset callback before the host pans asks for nothing more`() {
        val ledger = PageImePanLedger()
        ledger.step(neededPx = 120f, frameOriginYPx = 0f)

        assertNull(ledger.step(neededPx = 120f, frameOriginYPx = 0f))
    }

    @Test fun `a growing keyboard asks only for the part not yet requested`() {
        val ledger = PageImePanLedger()
        ledger.step(neededPx = 120f, frameOriginYPx = 0f)

        val step = ledger.step(neededPx = 200f, frameOriginYPx = 0f)!!

        assertEquals(-80f, step.panDyPx, EPSILON)
    }

    @Test fun `a pan the host already applied is not requested again`() {
        val ledger = PageImePanLedger()
        ledger.step(neededPx = 120f, frameOriginYPx = 40f)
        ledger.step(neededPx = 200f, frameOriginYPx = 40f)

        assertNull(ledger.step(neededPx = 80f, frameOriginYPx = -80f))

        val step = ledger.step(neededPx = 30f, frameOriginYPx = -160f)!!
        assertEquals(-30f, step.panDyPx, EPSILON)
    }

    @Test fun `no need asks for nothing`() {
        assertNull(PageImePanLedger().step(neededPx = null, frameOriginYPx = 0f))
    }

    @Test fun `a reset starts a new session from the current frame`() {
        val ledger = PageImePanLedger()
        ledger.step(neededPx = 120f, frameOriginYPx = 0f)

        ledger.reset()
        val step = ledger.step(neededPx = 50f, frameOriginYPx = 0f)!!

        assertEquals(-50f, step.panDyPx, EPSILON)
    }
}
