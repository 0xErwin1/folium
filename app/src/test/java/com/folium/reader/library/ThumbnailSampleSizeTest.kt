package com.folium.reader.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sample size decides how much memory every visible row's thumbnail costs, and the boundary it
 * turns on is one pixel wide, so it is pinned here rather than left to be read off the decoder.
 */
class ThumbnailSampleSizeTest {

    private val target = THUMBNAIL_ROW_TARGET_PX

    @Test
    fun `source smaller than the target is decoded whole`() {
        assertEquals(1, thumbnailSampleSize(1))
        assertEquals(1, thumbnailSampleSize(target / 2))
        assertEquals(1, thumbnailSampleSize(target - 1))
    }

    @Test
    fun `source at the target is decoded whole`() {
        assertEquals(1, thumbnailSampleSize(target))
    }

    @Test
    fun `an exact multiple of the target samples down to exactly the target`() {
        assertEquals(2, thumbnailSampleSize(target * 2))
        assertEquals(4, thumbnailSampleSize(target * 4))
        assertEquals(8, thumbnailSampleSize(target * 8))
    }

    @Test
    fun `just over a multiple keeps that multiple's sample size`() {
        assertEquals(2, thumbnailSampleSize(target * 2 + 1))
        assertEquals(4, thumbnailSampleSize(target * 4 + 1))
    }

    @Test
    fun `just under a multiple falls back to the smaller sample size`() {
        assertEquals(1, thumbnailSampleSize(target * 2 - 1))
        assertEquals(2, thumbnailSampleSize(target * 4 - 1))
    }

    @Test
    fun `the stored thumbnail's own longest edge is never sampled below the target`() {
        assertEquals(1, thumbnailSampleSize(STORED_THUMBNAIL_LONGEST_EDGE_PX))
    }

    /**
     * The target is only a target if it is the size the bitmap is actually drawn at: the slot is
     * taller than it is wide, so it is the row's thumbnail height that meets the source's longest
     * edge.
     */
    @Test
    fun `the target is the thumbnail's drawn longest edge at the densest screen shipped to`() {
        assertEquals((ThumbnailHeight.value * DENSEST_SCREEN_SCALE).toInt(), THUMBNAIL_ROW_TARGET_PX)
    }

    /**
     * Both halves of the contract over the whole range, since either one alone is survivable:
     * never sampling below the target is satisfied by never sampling at all, and sampling as far
     * down as the target allows is satisfied by sampling past it.
     */
    @Test
    fun `every source is sampled as far as it can go without decoding below the target`() {
        for (longestEdge in 1..(target * 16)) {
            val sample = thumbnailSampleSize(longestEdge)

            assertTrue("$sample is not a power of two", sample > 0 && sample and (sample - 1) == 0)

            val decoded = longestEdge / sample
            assertTrue(
                "longestEdge=$longestEdge sampled to $decoded, below the target",
                decoded >= target || sample == 1
            )
            assertTrue(
                "longestEdge=$longestEdge stopped at $sample, but ${sample * 2} still stays at the target",
                longestEdge / (sample * 2) < target
            )
        }
    }

    private companion object {
        const val STORED_THUMBNAIL_LONGEST_EDGE_PX = 320
        const val DENSEST_SCREEN_SCALE = 3f
    }
}
