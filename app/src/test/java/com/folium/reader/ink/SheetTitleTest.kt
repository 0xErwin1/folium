package com.folium.reader.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SheetTitleTest {

    @Test
    fun trimsLeadingAndTrailingWhitespace() {
        assertEquals("Notes", normalizedSheetTitle("  Notes  "))
    }

    @Test
    fun collapsesInnerWhitespaceRunsToOneSpace() {
        assertEquals("Reading notes", normalizedSheetTitle("Reading   \t\n notes"))
    }

    @Test
    fun isNullWhenTheResultIsEmpty() {
        assertNull(normalizedSheetTitle(""))
        assertNull(normalizedSheetTitle("   \t\n "))
    }

    @Test
    fun capsAtOneHundredTwentyCharacters() {
        val input = "a".repeat(200)
        val result = normalizedSheetTitle(input)
        assertEquals(120, result?.length)
        assertEquals("a".repeat(120), result)
    }
}
