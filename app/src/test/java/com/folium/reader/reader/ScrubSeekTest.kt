package com.folium.reader.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MEASURED_PREVIEW_MILLIS = 48L

class ScrubSeekTest {

    @Test fun `a drag that has not reached another page asks for nothing`() {
        assertFalse(seekWanted(page = 40, seekedPage = 40, millisSinceSeek = 1_000))
    }

    /**
     * The reason the interval exists: a fast drag crosses a page every sixteen milliseconds, and a
     * seek per page leaves no page on screen long enough for its own raster to arrive before the
     * window has moved past it, so the reader watches a blank sheet for the whole gesture.
     */
    @Test fun `a page reached sooner than the interval waits its turn`() {
        assertFalse(seekWanted(page = 41, seekedPage = 40, millisSinceSeek = SEEK_INTERVAL_MILLIS - 1))
    }

    @Test fun `a page reached after the interval is asked for`() {
        assertTrue(seekWanted(page = 41, seekedPage = 40, millisSinceSeek = SEEK_INTERVAL_MILLIS))
        assertTrue(seekWanted(page = 300, seekedPage = 40, millisSinceSeek = SEEK_INTERVAL_MILLIS * 4))
    }

    /**
     * The interval only ever delays a page, it never drops one: whatever the drag ended on is asked
     * for however recently the last one was, or releasing the finger would land somewhere the reader
     * did not choose.
     */
    @Test fun `the page a drag ended on is always asked for`() {
        assertTrue(seekWantedOnRelease(page = 41, seekedPage = 40))
        assertFalse(seekWantedOnRelease(page = 40, seekedPage = 40))
    }

    /**
     * The interval is only worth anything if a preview can be produced inside it. Measured on a
     * Pixel 8 against a 615 page book, a whole-page preview costs about 48ms once the reader is
     * running: 39ms of rasterizing and 9ms of measuring the page. Below that the window moves on
     * before the preview it asked for arrives, which is the state this whole thing replaced.
     */
    @Test fun `the interval leaves room for a preview to arrive`() {
        assertTrue(SEEK_INTERVAL_MILLIS >= MEASURED_PREVIEW_MILLIS * 2)
    }

    /** And short enough that a long drag still crosses the book rather than stepping through it. */
    @Test fun `the interval still crosses a book within a drag`() {
        val dragMillis = 2_500L
        assertTrue(dragMillis / SEEK_INTERVAL_MILLIS >= 12)
    }
}
