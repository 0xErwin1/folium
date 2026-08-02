package com.folium.reader.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BookContractsTest {

    @Test fun bookIdRejectsBlankAndControlCharacters() {
        assertThrows(IllegalArgumentException::class.java) { BookId("") }
        assertThrows(IllegalArgumentException::class.java) { BookId("   ") }
        assertThrows(IllegalArgumentException::class.java) { BookId("id\n") }
        assertEquals("abc-123", BookId("abc-123").value)
    }

    @Test fun libraryBookRejectsNonPositivePageCountAndNegativeTimestamp() {
        assertThrows(IllegalArgumentException::class.java) {
            LibraryBook(BookId("a"), "Title", pageCount = 0, addedAtMillis = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LibraryBook(BookId("a"), "Title", pageCount = 10, addedAtMillis = -1L)
        }
        LibraryBook(BookId("a"), "Title", pageCount = 1, addedAtMillis = 0L)
    }

    @Test fun libraryBookRejectsBlankOrControlTitles() {
        assertThrows(IllegalArgumentException::class.java) {
            LibraryBook(BookId("a"), "   ", pageCount = 10, addedAtMillis = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LibraryBook(BookId("a"), "Title\n", pageCount = 10, addedAtMillis = 0L)
        }
    }

    @Test fun shelfEntryComputesDisplayPageAndFraction() {
        val book = LibraryBook(BookId("a"), "Title", pageCount = 200, addedAtMillis = 0L)
        val entry = ShelfEntry(book, pageIndex = 49)
        assertEquals(50, entry.displayPage)
        assertEquals(0.25f, entry.fraction, 0.0001f)
    }

    @Test fun shelfEntryClampsAnOutOfRangePageIndexInsteadOfRejecting() {
        val book = LibraryBook(BookId("a"), "Title", pageCount = 10, addedAtMillis = 0L)
        assertEquals(9, ShelfEntry(book, pageIndex = 500).pageIndex)
        assertEquals(0, ShelfEntry(book, pageIndex = -5).pageIndex)
    }

    @Test fun neverOpenedBookStartsAtPageOne() {
        val book = LibraryBook(BookId("a"), "Title", pageCount = 42, addedAtMillis = 0L)
        val entry = ShelfEntry(book, pageIndex = 0)
        assertEquals(1, entry.displayPage)
    }
}
