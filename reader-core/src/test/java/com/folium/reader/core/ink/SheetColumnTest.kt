package com.folium.reader.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetColumnTest {

    @Test
    fun clampsXIntoZeroToOneAndYToNonNegative() {
        val clamped = SheetColumn.clamp(SheetPoint(-0.5f, -3f))
        assertEquals(SheetPoint(0f, 0f), clamped)

        val clampedAboveOne = SheetColumn.clamp(SheetPoint(1.5f, 4f))
        assertEquals(SheetPoint(1f, 4f), clampedAboveOne)
    }

    @Test
    fun leavesAnAlreadyValidPointUnchanged() {
        val point = SheetPoint(0.3f, 2f)
        assertEquals(point, SheetColumn.clamp(point))
    }

    @Test
    fun allowsOnlyRectsWithinItsBounds() {
        assertTrue(SheetColumn.allows(SheetRect(0f, 0f, 1f, 5f)))
        assertFalse(SheetColumn.allows(SheetRect(-0.1f, 0f, 1f, 5f)))
        assertFalse(SheetColumn.allows(SheetRect(0f, 0f, 1.1f, 5f)))
        assertFalse(SheetColumn.allows(SheetRect(0f, -1f, 1f, 5f)))
    }
}
