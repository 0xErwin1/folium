package com.folium.reader.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoliumWidthClassTest {
    @Test fun `the bands are decided by width, and the boundaries belong to the wider one`() {
        assertEquals(FoliumWidthClass.COMPACT, FoliumWidthClass.of(360.dp))
        assertEquals(FoliumWidthClass.COMPACT, FoliumWidthClass.of(599.dp))
        assertEquals(FoliumWidthClass.MEDIUM, FoliumWidthClass.of(600.dp))
        assertEquals(FoliumWidthClass.MEDIUM, FoliumWidthClass.of(839.dp))
        assertEquals(FoliumWidthClass.EXPANDED, FoliumWidthClass.of(840.dp))
        assertEquals(FoliumWidthClass.EXPANDED, FoliumWidthClass.of(1365.dp))
    }

    /** A phone turned sideways is a medium window, not a phone. That is the point of deciding by width. */
    @Test fun `a device is never asked, only the room it gave the layout`() {
        assertEquals(FoliumWidthClass.MEDIUM, FoliumWidthClass.of(915.dp / 1.1f))
        assertEquals(FoliumWidthClass.COMPACT, FoliumWidthClass.of(412.dp))
    }

    @Test fun `only the widest band puts two things beside each other`() {
        assertFalse(FoliumWidthClass.COMPACT.showsTwoPanes)
        assertFalse(FoliumWidthClass.MEDIUM.showsTwoPanes)
        assertTrue(FoliumWidthClass.EXPANDED.showsTwoPanes)
    }

    /**
     * The whole reason the span exists: a cover that spanned one module everywhere would shrink as
     * the count grew. Held against the same widths the design was drawn at, it grows instead.
     */
    @Test fun `a cover grows from band to band and stays inside its readable range`() {
        val widths = mapOf(
            FoliumWidthClass.COMPACT to 412.dp,
            FoliumWidthClass.MEDIUM to 720.dp,
            FoliumWidthClass.EXPANDED to 1365.dp
        )

        val covers = FoliumWidthClass.entries.map { band -> band to cover(band, widths.getValue(band)) }

        covers.zipWithNext { (_, narrower), (band, wider) ->
            assertTrue("$band did not grow: $narrower then $wider", wider > narrower)
        }
        covers.forEach { (band, width) ->
            assertTrue(
                "$band cover $width left ${FoliumGrid.minCover}..${FoliumGrid.maxCover}",
                width >= FoliumGrid.minCover.value && width <= FoliumGrid.maxCover.value
            )
        }
    }

    @Test fun `every band names a whole grid`() {
        FoliumWidthClass.entries.forEach { band ->
            assertTrue(band.columns > 0)
            assertTrue(band.coverSpan in 1..band.columns)
            assertTrue(band.margin.value > 0f)
            assertTrue(band.gutter.value > 0f)
        }
    }

    private fun cover(band: FoliumWidthClass, width: androidx.compose.ui.unit.Dp): Float {
        val module = (width.value - band.margin.value * 2 - band.gutter.value * (band.columns - 1)) / band.columns
        return module * band.coverSpan + band.gutter.value * (band.coverSpan - 1)
    }
}
