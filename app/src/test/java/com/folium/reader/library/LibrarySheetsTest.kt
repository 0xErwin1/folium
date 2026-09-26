package com.folium.reader.library

import com.folium.reader.core.ink.SheetAnchor
import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.ink.SheetSummary
import com.folium.reader.core.ink.SheetTemplate
import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.ReadingPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySheetsTest {
    private val recent = sheet("recent", "Meeting notes", updatedAt = 3_000L)
    private val older = sheet("older", "Grocery list", updatedAt = 2_000L)
    private val tieA = sheet("tie-a", "Alpha", updatedAt = 1_000L)
    private val tieB = sheet("tie-b", "Beta", updatedAt = 1_000L)
    private val all = listOf(older, tieB, recent, tieA)

    @Test fun `ALL orders sheets by most recently updated, ties by title`() {
        val visible = visibleSheets(all, ShelfFilter.ALL, query = null, shelfBooks = emptySet())

        assertEquals(listOf(recent, older, tieA, tieB), visible)
    }

    @Test fun `a reading-progress filter hides every sheet, having no progress to answer with`() {
        assertEquals(emptyList<SheetSummary>(), visibleSheets(all, ShelfFilter.STARTED, query = null, shelfBooks = emptySet()))
        assertEquals(emptyList<SheetSummary>(), visibleSheets(all, ShelfFilter.UNOPENED, query = null, shelfBooks = emptySet()))
    }

    @Test fun `a search matches titles regardless of case`() {
        val visible = visibleSheets(all, ShelfFilter.ALL, query = "GROCERY", shelfBooks = emptySet())

        assertEquals(listOf(older), visible)
    }

    @Test fun `a blank search hides nothing`() {
        val visible = visibleSheets(all, ShelfFilter.ALL, query = "", shelfBooks = emptySet())

        assertEquals(listOf(recent, older, tieA, tieB), visible)
    }

    @Test fun `no sheet matches a query naming none of them`() {
        val visible = visibleSheets(all, ShelfFilter.ALL, query = "zzz", shelfBooks = emptySet())

        assertEquals(emptyList<SheetSummary>(), visible)
    }

    @Test fun `a sheet anchored to a book on the shelf is read inside that book, not on the shelf`() {
        val anchored = sheet("anchored", "Notes", updatedAt = 4_000L, anchor = pageAnchor("on-shelf"))

        val visible = visibleSheets(all + anchored, ShelfFilter.ALL, query = null, shelfBooks = setOf(BookId("on-shelf")))

        assertEquals(listOf(recent, older, tieA, tieB), visible)
    }

    @Test fun `a sheet anchored to a book no longer on the shelf stays visible`() {
        val orphan = sheet("orphan", "Orphaned notes", updatedAt = 4_000L, anchor = pageAnchor("removed"))

        val visible = visibleSheets(all + orphan, ShelfFilter.ALL, query = null, shelfBooks = setOf(BookId("on-shelf")))

        assertEquals(listOf(orphan, recent, older, tieA, tieB), visible)
    }

    @Test fun `a text-anchored sheet is hidden by its book the same way`() {
        val anchored = sheet(
            "text",
            "Margin",
            updatedAt = 4_000L,
            anchor = SheetAnchor.Text(BookId("on-shelf"), ReadingPosition(0, 10), rank = 0L)
        )

        val visible = visibleSheets(listOf(anchored, recent), ShelfFilter.ALL, query = null, shelfBooks = setOf(BookId("on-shelf")))

        assertEquals(listOf(recent), visible)
    }

    @Test fun `the removal prompt counts only the sheets anchored to that book`() {
        val sheets = listOf(
            sheet("a", "A", updatedAt = 1L, anchor = pageAnchor("book")),
            sheet("b", "B", updatedAt = 1L, anchor = pageAnchor("book")),
            sheet("c", "C", updatedAt = 1L, anchor = pageAnchor("other")),
            sheet("d", "D", updatedAt = 1L)
        )

        assertEquals(RemoveBookPrompt(sheetCount = 2), removeBookPrompt(BookId("book"), sheets))
        assertEquals(true, removeBookPrompt(BookId("book"), sheets).offersSheetDeletion)
    }

    @Test fun `a book with no sheets is removed without offering to delete any`() {
        val prompt = removeBookPrompt(BookId("book"), all + sheet("c", "C", updatedAt = 1L, anchor = pageAnchor("other")))

        assertEquals(RemoveBookPrompt(sheetCount = 0), prompt)
        assertEquals(false, prompt.offersSheetDeletion)
    }

    @Test fun `the removal prompt reports the pages written on so it can say their handwriting goes too`() {
        val prompt = removeBookPrompt(BookId("book"), sheets = emptyList(), pageInk = PageInkCount.Known(3))

        assertEquals(RemoveBookPrompt(sheetCount = 0, pageInk = PageInkCount.Known(3)), prompt)
        assertEquals(true, prompt.mentionsPageInk)
        assertEquals(false, prompt.offersSheetDeletion)
    }

    @Test fun `a book with no handwriting on its pages says nothing about it`() {
        val prompt = removeBookPrompt(BookId("book"), sheets = emptyList())

        assertEquals(false, prompt.mentionsPageInk)
        assertEquals(false, prompt.mentionsUncountedPageInk)
    }

    @Test fun `the removal cannot be confirmed until the pages written on are counted`() {
        val counting = removeBookPrompt(BookId("book"), sheets = emptyList(), pageInk = null)

        assertEquals(false, counting.canConfirm)
        assertEquals(true, counting.countingPageInk)
        assertEquals(false, counting.mentionsPageInk)
        assertEquals(false, counting.mentionsUncountedPageInk)

        val counted = removeBookPrompt(BookId("book"), sheets = emptyList(), pageInk = PageInkCount.Known(0))

        assertEquals(true, counted.canConfirm)
        assertEquals(false, counted.countingPageInk)
    }

    @Test fun `a count that failed still lets the book be removed and warns that its handwriting goes too`() {
        val prompt = removeBookPrompt(BookId("book"), sheets = emptyList(), pageInk = PageInkCount.Unknown)

        assertEquals(true, prompt.canConfirm)
        assertEquals(false, prompt.countingPageInk)
        assertEquals(false, prompt.mentionsPageInk)
        assertEquals(true, prompt.mentionsUncountedPageInk)
    }

    @Test fun `a detached sheet is named after its book unless its title already names it`() {
        assertEquals("Dune · Notes", detachedSheetTitle(sheetTitle = "Notes", bookTitle = "Dune"))
        assertEquals("Dune · 19", detachedSheetTitle(sheetTitle = "Dune · 19", bookTitle = "Dune"))
    }

    private fun pageAnchor(bookId: String) = SheetAnchor.Page(BookId(bookId), pageIndex = 3, rank = 0L)

    private fun sheet(id: String, title: String, updatedAt: Long, anchor: SheetAnchor? = null) = SheetSummary(
        id = SheetId(id),
        title = title,
        createdAtEpochMillis = updatedAt,
        updatedAtEpochMillis = updatedAt,
        template = SheetTemplate.BLANK,
        anchor = anchor
    )
}
