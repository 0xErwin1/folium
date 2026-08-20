package com.folium.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MB = 1024L * 1024

class ReaderCacheBudgetTest {

    /**
     * The bug this replaces: the budget was a quarter of the Java heap, and a raster's pixels have
     * not been on the Java heap since Android 8. Measured with a full window of A3 plans on a Pixel
     * 8, the heap sat at 27MB while the cache was entitled to 64MB of rasters — the number it was
     * derived from was not the number that constrains it.
     */
    @Test fun `a device with memory to spare is given more than a small one`() {
        val roomy = readerShareOf(availableBytes = 2_000 * MB, lowMemoryThresholdBytes = 200 * MB)
        val tight = readerShareOf(availableBytes = 300 * MB, lowMemoryThresholdBytes = 200 * MB)

        assertTrue("a roomy device was given no more than a tight one: $roomy vs $tight", roomy > tight)
    }

    @Test fun `what the system is about to reclaim is not counted as spare`() {
        val above = readerShareOf(availableBytes = 600 * MB, lowMemoryThresholdBytes = 200 * MB)
        val same = readerShareOf(availableBytes = 400 * MB, lowMemoryThresholdBytes = 0)

        assertEquals(above, same)
    }

    @Test fun `a device already under its own threshold still gets a floor to read with`() {
        assertEquals(MIN_CACHE_BYTES, readerShareOf(availableBytes = 50 * MB, lowMemoryThresholdBytes = 200 * MB))
        assertEquals(MIN_CACHE_BYTES, readerShareOf(availableBytes = 0, lowMemoryThresholdBytes = 0))
    }

    @Test fun `no device is given more than the ceiling however much it has`() {
        assertEquals(MAX_CACHE_BYTES, readerShareOf(availableBytes = 64_000 * MB, lowMemoryThresholdBytes = 0))
    }

    @Test fun `every answer is inside the bounds and never shrinks as memory grows`() {
        val answers = (0..64).map { readerShareOf(availableBytes = it * 100L * MB, lowMemoryThresholdBytes = 200 * MB) }

        answers.forEach { assertTrue("$it left the bounds", it in MIN_CACHE_BYTES..MAX_CACHE_BYTES) }
        answers.zipWithNext { poorer, richer -> assertTrue("more memory bought less", richer >= poorer) }
    }

    /** The reader takes a share, not the lot: the rest of the device is still running. */
    @Test fun `the reader never takes more than a fraction of what is spare`() {
        val spare = 800 * MB

        assertTrue(readerShareOf(spare, lowMemoryThresholdBytes = 0) <= spare / 2)
    }
}
