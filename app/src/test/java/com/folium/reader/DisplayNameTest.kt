package com.folium.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayNameTest {

    @Test fun `a blank queried name falls through to the last path segment`() {
        assertEquals("report.pdf", displayName(queried = "   ", lastPathSegment = "report.pdf", fallback = "Untitled"))
    }

    @Test fun `a null queried name falls through to the last path segment`() {
        assertEquals("report.pdf", displayName(queried = null, lastPathSegment = "report.pdf", fallback = "Untitled"))
    }

    @Test fun `a usable queried name wins over the last path segment`() {
        assertEquals("Report.pdf", displayName(queried = "Report.pdf", lastPathSegment = "raw-id", fallback = "Untitled"))
    }

    @Test fun `both unusable fall back to the caller-supplied default`() {
        assertEquals("Untitled", displayName(queried = null, lastPathSegment = null, fallback = "Untitled"))
        assertEquals("Untitled", displayName(queried = "  ", lastPathSegment = "  ", fallback = "Untitled"))
    }
}
