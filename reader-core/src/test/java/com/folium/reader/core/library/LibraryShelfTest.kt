package com.folium.reader.core.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryShelfTest {

    private fun book(id: String, addedAtMillis: Long, pageCount: Int = 100): LibraryBook =
        LibraryBook(BookId(id), "Book $id", pageCount, addedAtMillis)

    @Test fun bookWithNoMatchingProgressStartsAtPageZero() {
        val books = listOf(book("a", addedAtMillis = 1L))
        val entries = LibraryShelf.entries(books, emptyList())
        assertEquals(0, entries.single().pageIndex)
    }

    @Test fun bookWithMatchingProgressUsesItsStoredPage() {
        val books = listOf(book("a", addedAtMillis = 1L))
        val progress = listOf(ProgressRecord(BookId("a"), pageIndex = 41))
        val entries = LibraryShelf.entries(books, progress)
        assertEquals(41, entries.single().pageIndex)
    }

    @Test fun orphanProgressRowsAreDropped() {
        val books = listOf(book("a", addedAtMillis = 1L))
        val progress = listOf(
            ProgressRecord(BookId("a"), pageIndex = 5),
            ProgressRecord(BookId("gone"), pageIndex = 5)
        )
        val entries = LibraryShelf.entries(books, progress)
        assertEquals(listOf(BookId("a")), entries.map { it.book.id })
    }

    @Test fun orderingIsMostRecentlyAddedFirstWithIdTieBreak() {
        val books = listOf(
            book("older", addedAtMillis = 1L),
            book("newer", addedAtMillis = 2L),
            book("tie-b", addedAtMillis = 2L),
            book("tie-a", addedAtMillis = 2L)
        )
        val entries = LibraryShelf.entries(books, emptyList())
        assertEquals(listOf("newer", "tie-a", "tie-b", "older"), entries.map { it.book.id.value })
    }

    @Test fun progressPastPageCountIsClampedRatherThanPropagated() {
        val books = listOf(book("a", addedAtMillis = 1L, pageCount = 10))
        val progress = listOf(ProgressRecord(BookId("a"), pageIndex = 999))
        val entries = LibraryShelf.entries(books, progress)
        assertEquals(9, entries.single().pageIndex)
    }

    @Test fun duplicatesAreIndependentRows() {
        val books = listOf(book("a", addedAtMillis = 1L), book("b", addedAtMillis = 2L))
        val progress = listOf(ProgressRecord(BookId("a"), pageIndex = 3))
        val entries = LibraryShelf.entries(books, progress)
        assertEquals(2, entries.size)
        assertEquals(3, entries.first { it.book.id.value == "a" }.pageIndex)
        assertEquals(0, entries.first { it.book.id.value == "b" }.pageIndex)
    }
}
