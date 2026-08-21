package com.folium.reader.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BookContractsTest {

    @Test fun bookFormatResolvesFromItsExtension() {
        assertEquals(BookFormat.PDF, BookFormat.forExtension("pdf"))
        assertEquals(BookFormat.EPUB, BookFormat.forExtension("epub"))
        assertNull(BookFormat.forExtension("txt"))
    }

    @Test fun bookFormatResolvesFromAPathsLastSegment() {
        assertEquals(BookFormat.PDF, BookFormat.forPath("library/book-1/document.pdf"))
        assertEquals(BookFormat.EPUB, BookFormat.forPath("document.epub"))
        assertNull(BookFormat.forPath("library/book-1/document.txt"))
    }

    /**
     * The gate this replaces matched `.pdf` case-insensitively, so a name a provider hands over
     * shouting is a name the app has always accepted.
     */
    @Test fun aFormatIsRecognizedWhateverCaseItsExtensionIsWrittenIn() {
        assertEquals(BookFormat.PDF, BookFormat.forExtension("PDF"))
        assertEquals(BookFormat.EPUB, BookFormat.forExtension("ePub"))
        assertEquals(BookFormat.PDF, BookFormat.forPath("/storage/emulated/0/Download/BOOK.PDF"))
    }

    /** A name that is only a bare word claims no format, even when the word is one. */
    @Test fun aSegmentWithNoExtensionResolvesToNoFormat() {
        assertNull(BookFormat.forPath("epub"))
        assertNull(BookFormat.forPath("library/book-1/pdf"))
        assertNull(BookFormat.forPath(""))
    }

    @Test fun libraryBookDefaultsToPdfFormat() {
        val book = LibraryBook(BookId("a"), "Title", pageCount = 1, addedAtMillis = 0L)
        assertEquals(BookFormat.PDF, book.format)
    }

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

    @Test fun shelfEntryUsesAnExplicitPageCountOverTheBooksOwn() {
        val book = LibraryBook(BookId("a"), "Title", pageCount = 10, addedAtMillis = 0L)
        val entry = ShelfEntry(book, pageIndex = 40, pageCount = 200)
        assertEquals(200, entry.pageCount)
        assertEquals(40, entry.pageIndex)
    }

    @Test fun shelfEntryClampsAgainstTheEffectivePageCountNotTheBooksOwn() {
        val book = LibraryBook(BookId("a"), "Title", pageCount = 10, addedAtMillis = 0L)
        val entry = ShelfEntry(book, pageIndex = 500, pageCount = 50)
        assertEquals(49, entry.pageIndex)
    }

    @Test fun shelfEntryWithNoExplicitPageCountFallsBackToTheBooksOwn() {
        val book = LibraryBook(BookId("a"), "Title", pageCount = 10, addedAtMillis = 0L)
        val entry = ShelfEntry(book, pageIndex = 3)
        assertEquals(10, entry.pageCount)
    }

    @Test fun neverOpenedBookStartsAtPageOne() {
        val book = LibraryBook(BookId("a"), "Title", pageCount = 42, addedAtMillis = 0L)
        val entry = ShelfEntry(book, pageIndex = 0)
        assertEquals(1, entry.displayPage)
    }
}
