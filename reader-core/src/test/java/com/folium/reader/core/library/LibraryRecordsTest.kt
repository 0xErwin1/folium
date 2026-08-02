package com.folium.reader.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val SEPARATOR = '\u001F'

class LibraryRecordsTest {

    private fun bookLine(vararg fields: String): String = fields.joinToString(SEPARATOR.toString())

    @Test fun bookRoundTripsThroughEncodeAndDecode() {
        val book = LibraryBook(BookId("book-1"), "Report.pdf", pageCount = 42, addedAtMillis = 1_000L)
        val encoded = LibraryRecords.encodeBook(book)
        assertEquals(book, LibraryRecords.decodeBook(encoded))
    }

    @Test fun progressRoundTripsThroughEncodeAndDecode() {
        val record = ProgressRecord(BookId("book-1"), pageIndex = 137)
        val encoded = LibraryRecords.encodeProgress(record)
        assertEquals(record, LibraryRecords.decodeProgress(encoded))
    }

    @Test fun malformedBookLinesDecodeToNullWithoutThrowing() {
        assertNull(LibraryRecords.decodeBook(bookLine("only", "three", "fields")))
        assertNull(LibraryRecords.decodeBook(bookLine("id", "Title", "not-a-number", "0")))
        assertNull(LibraryRecords.decodeBook(bookLine("id", "Title", "10", "not-a-number")))
        assertNull(LibraryRecords.decodeBook(bookLine("   ", "Title", "10", "0")))
        assertNull(LibraryRecords.decodeBook(bookLine("id", "   ", "10", "0")))
        assertNull(LibraryRecords.decodeBook(bookLine("id", "Title", "0", "0")))
        assertNull(LibraryRecords.decodeBook(CATALOG_VERSION_MARKER))
    }

    @Test fun malformedProgressLinesDecodeToNullWithoutThrowing() {
        assertNull(LibraryRecords.decodeProgress(bookLine("id", "0", "extra")))
        assertNull(LibraryRecords.decodeProgress(bookLine("id")))
        assertNull(LibraryRecords.decodeProgress(bookLine("id", "not-a-number")))
        assertNull(LibraryRecords.decodeProgress(bookLine("   ", "0")))
        assertNull(LibraryRecords.decodeProgress(bookLine("id", "-1")))
        assertNull(LibraryRecords.decodeProgress(PROGRESS_VERSION_MARKER))
    }
}
