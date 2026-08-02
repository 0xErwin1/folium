package com.folium.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decisions behind the jump dialog and the contents list that do not need a screen: which typed
 * entries name a page, what the field is allowed to hold, and what an entry the document left
 * untitled shows instead.
 */
class ReaderNavigationTest {

    @Test fun `a page inside the document is answered as a zero-based index`() {
        assertEquals(0, jumpTargetPage("1", pageCount = 500))
        assertEquals(299, jumpTargetPage("300", pageCount = 500))
        assertEquals(499, jumpTargetPage("500", pageCount = 500))
    }

    @Test fun `surrounding whitespace does not stop an entry naming a page`() {
        assertEquals(41, jumpTargetPage("  42  ", pageCount = 500))
    }

    @Test fun `a page outside the document names nothing rather than being clamped into it`() {
        assertNull(jumpTargetPage("0", pageCount = 500))
        assertNull(jumpTargetPage("501", pageCount = 500))
        assertNull(jumpTargetPage("9999", pageCount = 500))
    }

    @Test fun `an entry that is not a page number names nothing`() {
        assertNull(jumpTargetPage("", pageCount = 500))
        assertNull(jumpTargetPage("   ", pageCount = 500))
        assertNull(jumpTargetPage("twelve", pageCount = 500))
        assertNull(jumpTargetPage("1.5", pageCount = 500))
        assertNull(jumpTargetPage("-3", pageCount = 500))
        assertNull(jumpTargetPage("99999999999999999999", pageCount = 500))
    }

    @Test fun `the entry keeps only digits and never more of them than the last page needs`() {
        assertEquals("42", sanitizeJumpEntry("4a2", pageCount = 500))
        assertEquals("", sanitizeJumpEntry("twelve", pageCount = 500))
        assertEquals("123", sanitizeJumpEntry("123456", pageCount = 500))
        assertEquals("1234", sanitizeJumpEntry("123456", pageCount = 1000))
    }

    @Test fun `an outline entry the document left untitled keeps its place under a placeholder`() {
        assertEquals("Chapter 1", contentsRowTitle("Chapter 1", placeholder = "—"))
        assertEquals("Chapter 1", contentsRowTitle("  Chapter 1  ", placeholder = "—"))
        assertEquals("—", contentsRowTitle("", placeholder = "—"))
        assertEquals("—", contentsRowTitle("   ", placeholder = "—"))
    }
}
