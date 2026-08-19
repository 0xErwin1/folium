package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.library.ShelfEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfPartitionTest {
    private val started = entry("started", "Building Microservices", pages = 615, at = 318)
    private val alsoStarted = entry("also", "Refactoring", pages = 400, at = 12)
    private val fresh = entry("fresh", "Don Quijote de la Mancha", pages = 471, at = 0)
    private val untouched = entry("untouched", "The Pragmatic Programmer", pages = 300, at = 0)
    private val all = listOf(started, alsoStarted, fresh, untouched)

    @Test fun `the book furthest in is lifted out of an unnarrowed shelf`() {
        val (current, shelf) = partitionShelf(all, ShelfFilter.ALL, query = null, liftCurrent = true)

        assertEquals(started, current)
        assertEquals(listOf(alsoStarted, fresh, untouched), shelf)
    }

    /**
     * The bug this exists for: asking for the books in progress and being shown none, while the one
     * in progress sits above the empty list.
     */
    @Test fun `filtering shows every match, including the one that would have been the hero`() {
        val (current, shelf) = partitionShelf(all, ShelfFilter.STARTED, query = null, liftCurrent = true)

        assertNull(current)
        assertEquals(listOf(started, alsoStarted), shelf)
    }

    @Test fun `the unopened filter is the same rule from the other side`() {
        val (current, shelf) = partitionShelf(all, ShelfFilter.UNOPENED, query = null, liftCurrent = true)

        assertNull(current)
        assertEquals(listOf(fresh, untouched), shelf)
    }

    @Test fun `a search hides the hero even before anything is typed`() {
        val (current, shelf) = partitionShelf(all, ShelfFilter.ALL, query = "", liftCurrent = true)

        assertNull(current)
        assertEquals(all, shelf)
    }

    @Test fun `a search matches titles regardless of case`() {
        val (_, shelf) = partitionShelf(all, ShelfFilter.ALL, query = "QUIJOTE", liftCurrent = true)

        assertEquals(listOf(fresh), shelf)
    }

    /** Two panes give a chosen book its own room, so nothing is lifted out of the shelf. */
    @Test fun `a layout that shows a book beside the shelf lifts nothing out of it`() {
        val (current, shelf) = partitionShelf(all, ShelfFilter.ALL, query = null, liftCurrent = false)

        assertNull(current)
        assertEquals(all, shelf)
    }

    @Test fun `a shelf nobody has opened has no hero to lift`() {
        val unread = listOf(fresh, untouched)

        val (current, shelf) = partitionShelf(unread, ShelfFilter.ALL, query = null, liftCurrent = true)

        assertNull(current)
        assertEquals(unread, shelf)
    }

    /**
     * The property behind all of the above: whatever the narrowing, a book that matches it is on
     * screen somewhere — in the shelf, or as the one lifted above it.
     */
    @Test fun `every book that matches is reachable, whichever way the shelf is narrowed`() {
        val queries = listOf(null, "", "the", "QUIJOTE", "zzz")

        ShelfFilter.entries.forEach { filter ->
            queries.forEach { query ->
                listOf(true, false).forEach { lift ->
                    val (current, shelf) = partitionShelf(all, filter, query, lift)
                    val shown = (shelf + listOfNotNull(current)).toSet()
                    val matching = all
                        .filter(filter::accepts)
                        .filter { query.isNullOrBlank() || it.book.title.contains(query, ignoreCase = true) }
                        .toSet()

                    assertEquals("filter=$filter query=$query lift=$lift", matching, shown)
                }
            }
        }
    }

    private fun entry(id: String, title: String, pages: Int, at: Int) =
        ShelfEntry(LibraryBook(BookId(id), title, pages, addedAtMillis = 1_000L), at)
}
