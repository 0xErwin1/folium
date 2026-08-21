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

    /**
     * The shelf labels a name it derived from a file differently from one the author wrote, so the
     * catalog has to remember which of the two it stored.
     */
    @Test fun aBookRemembersWhereItsTitleCameFrom() {
        val declared = LibraryBook(
            BookId("book-1"), "Building Microservices", pageCount = 615, addedAtMillis = 1_000L,
            titleDeclared = true
        )
        val derived = LibraryBook(
            BookId("book-2"), "cocina.pdf", pageCount = 97, addedAtMillis = 1_000L,
            titleDeclared = false
        )

        assertEquals(declared, LibraryRecords.decodeBook(LibraryRecords.encodeBook(declared)))
        assertEquals(derived, LibraryRecords.decodeBook(LibraryRecords.encodeBook(derived)))
    }

    /**
     * A five-field line is a catalog written before the origin was recorded. It keeps its author
     * and leaves the origin unknown rather than being dropped or guessed at.
     */
    @Test fun aCatalogWrittenBeforeTitleOriginDecodesWithoutIt() {
        val book = LibraryRecords.decodeBook(bookLine("book-1", "Report.pdf", "42", "1000", "Sam Newman"))

        assertEquals("Sam Newman", book?.author)
        assertNull(book?.titleDeclared)
    }

    @Test fun malformedBookLinesDecodeToNullWithoutThrowing() {
        assertNull(LibraryRecords.decodeBook(bookLine("only", "three", "fields")))
        assertNull(LibraryRecords.decodeBook(bookLine("id", "Title", "10", "0", "", "1", "docx")))
        assertNull(LibraryRecords.decodeBook(bookLine("id", "Title", "not-a-number", "0")))
        assertNull(LibraryRecords.decodeBook(bookLine("id", "Title", "10", "not-a-number")))
        assertNull(LibraryRecords.decodeBook(bookLine("   ", "Title", "10", "0")))
        assertNull(LibraryRecords.decodeBook(bookLine("id", "   ", "10", "0")))
        assertNull(LibraryRecords.decodeBook(bookLine("id", "Title", "0", "0")))
        assertNull(LibraryRecords.decodeBook(bookLine("id", "Title", "10", "0", "", "", "", "extra")))
        assertNull(LibraryRecords.decodeBook(CATALOG_VERSION_MARKER))
    }

    /**
     * A six-field line is a catalog written before formats other than PDF existed, and decodes as
     * PDF because that is what it was.
     */
    @Test fun aCatalogWrittenBeforeFormatsExistedDecodesAsPdf() {
        val book = LibraryRecords.decodeBook(bookLine("book-1", "Report.pdf", "42", "1000", "", ""))
        assertEquals(BookFormat.PDF, book?.format)
    }

    /**
     * An unrecognized format token is dropped rather than guessed at: falling back to PDF would
     * point the app at a document that is not really there.
     */
    @Test fun aCatalogLineWithAnUnrecognizedFormatIsDropped() {
        assertNull(LibraryRecords.decodeBook(bookLine("book-1", "Report.mobi", "42", "1000", "", "", "mobi")))
    }

    @Test fun anEpubBookRoundTripsThroughEncodeAndDecode() {
        val book = LibraryBook(
            BookId("book-1"), "A Reflowable Book", pageCount = 12, addedAtMillis = 1_000L,
            format = BookFormat.EPUB
        )
        val encoded = LibraryRecords.encodeBook(book)
        assertEquals(book, LibraryRecords.decodeBook(encoded))
    }

    /**
     * A PDF book is encoded with exactly the six fields today's catalog already writes, so a
     * catalog rewrite made after this change touches no PDF row's byte shape.
     */
    @Test fun aPdfBookIsEncodedWithoutASeventhField() {
        val book = LibraryBook(BookId("book-1"), "Report.pdf", pageCount = 42, addedAtMillis = 1_000L)
        assertEquals(5, LibraryRecords.encodeBook(book).count { it == SEPARATOR })
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
