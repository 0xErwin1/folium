package com.folium.reader.ink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StraightenResizeGateTest {

    @Test fun `holding within the slop never exceeds it`() {
        val gate = StraightenResizeGate(slopPx = 16f)

        assertFalse(gate.hasExceededSlop(dxPx = 0f, dyPx = 0f))
        assertFalse(gate.hasExceededSlop(dxPx = 5f, dyPx = -3f))
        assertFalse(gate.hasExceededSlop(dxPx = -8f, dyPx = 8f))
    }

    @Test fun `moving past the slop exceeds it`() {
        val gate = StraightenResizeGate(slopPx = 16f)

        assertTrue(gate.hasExceededSlop(dxPx = 20f, dyPx = 0f))
    }

    @Test fun `once exceeded it latches even if the pointer falls back inside the slop`() {
        val gate = StraightenResizeGate(slopPx = 16f)

        assertTrue(gate.hasExceededSlop(dxPx = 20f, dyPx = 0f))
        assertTrue(gate.hasExceededSlop(dxPx = 1f, dyPx = 0f))
    }
}
