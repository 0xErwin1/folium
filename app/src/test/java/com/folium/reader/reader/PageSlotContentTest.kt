package com.folium.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [pageSlotContent] in isolation from Compose: the decision of what a page slot draws must
 * never let a carried preview for one page stand in for another, which is exactly the bug a raw
 * `carriedPreview != null` check in [PageContent] used to let through.
 */
class PageSlotContentTest {

    @Test fun `a raster of its own always wins regardless of carried preview or failure`() {
        assertEquals(
            PageSlotContent.RASTER,
            pageSlotContent(hasDetail = true, hasBase = false, carriedPageIndex = 9, slotPageIndex = 3, failed = true)
        )
        assertEquals(
            PageSlotContent.RASTER,
            pageSlotContent(hasDetail = false, hasBase = true, carriedPageIndex = null, slotPageIndex = 3, failed = false)
        )
    }

    @Test fun `a carried preview for a different page is never drawn in this slot`() {
        assertEquals(
            PageSlotContent.PLACEHOLDER,
            pageSlotContent(hasDetail = false, hasBase = false, carriedPageIndex = 9, slotPageIndex = 3, failed = false)
        )
    }

    @Test fun `a carried preview for this exact page is drawn when nothing of its own has arrived`() {
        assertEquals(
            PageSlotContent.CARRIED,
            pageSlotContent(hasDetail = false, hasBase = false, carriedPageIndex = 3, slotPageIndex = 3, failed = false)
        )
    }

    @Test fun `a page with no raster and nothing carried for it shows the placeholder`() {
        assertEquals(
            PageSlotContent.PLACEHOLDER,
            pageSlotContent(hasDetail = false, hasBase = false, carriedPageIndex = null, slotPageIndex = 3, failed = false)
        )
    }

    @Test fun `a failed page with nothing of its own draws nothing, carried or not`() {
        assertEquals(
            PageSlotContent.NONE,
            pageSlotContent(hasDetail = false, hasBase = false, carriedPageIndex = 3, slotPageIndex = 3, failed = true)
        )
        assertEquals(
            PageSlotContent.NONE,
            pageSlotContent(hasDetail = false, hasBase = false, carriedPageIndex = null, slotPageIndex = 3, failed = true)
        )
    }
}
