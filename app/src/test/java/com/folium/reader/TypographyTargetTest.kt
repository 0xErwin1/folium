package com.folium.reader

import com.folium.reader.core.library.BookFormat
import com.folium.reader.core.library.BookId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypographyTargetTest {

    @Test fun `a stored id and format round-trip back to the same target`() {
        val target = TypographyTarget(BookId("book-1"), BookFormat.EPUB)
        assertEquals(target, restoreTypographyTarget(target.id.value, target.format.name))
    }

    @Test fun `a missing id restores to no sheet`() {
        assertNull(restoreTypographyTarget(null, BookFormat.EPUB.name))
    }

    @Test fun `an unrecognised format token restores to no sheet`() {
        assertNull(restoreTypographyTarget("book-1", "TXT"))
    }

    @Test fun `a missing format restores to no sheet`() {
        assertNull(restoreTypographyTarget("book-1", null))
    }
}
