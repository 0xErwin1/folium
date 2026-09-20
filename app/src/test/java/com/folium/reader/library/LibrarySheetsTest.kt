package com.folium.reader.library

import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.ink.SheetSummary
import com.folium.reader.core.ink.SheetTemplate
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySheetsTest {
    private val recent = sheet("recent", "Meeting notes", updatedAt = 3_000L)
    private val older = sheet("older", "Grocery list", updatedAt = 2_000L)
    private val tieA = sheet("tie-a", "Alpha", updatedAt = 1_000L)
    private val tieB = sheet("tie-b", "Beta", updatedAt = 1_000L)
    private val all = listOf(older, tieB, recent, tieA)

    @Test fun `ALL orders sheets by most recently updated, ties by title`() {
        val visible = visibleSheets(all, ShelfFilter.ALL, query = null)

        assertEquals(listOf(recent, older, tieA, tieB), visible)
    }

    @Test fun `a reading-progress filter hides every sheet, having no progress to answer with`() {
        assertEquals(emptyList<SheetSummary>(), visibleSheets(all, ShelfFilter.STARTED, query = null))
        assertEquals(emptyList<SheetSummary>(), visibleSheets(all, ShelfFilter.UNOPENED, query = null))
    }

    @Test fun `a search matches titles regardless of case`() {
        val visible = visibleSheets(all, ShelfFilter.ALL, query = "GROCERY")

        assertEquals(listOf(older), visible)
    }

    @Test fun `a blank search hides nothing`() {
        val visible = visibleSheets(all, ShelfFilter.ALL, query = "")

        assertEquals(listOf(recent, older, tieA, tieB), visible)
    }

    @Test fun `no sheet matches a query naming none of them`() {
        val visible = visibleSheets(all, ShelfFilter.ALL, query = "zzz")

        assertEquals(emptyList<SheetSummary>(), visible)
    }

    private fun sheet(id: String, title: String, updatedAt: Long) = SheetSummary(
        id = SheetId(id),
        title = title,
        createdAtEpochMillis = updatedAt,
        updatedAtEpochMillis = updatedAt,
        template = SheetTemplate.BLANK,
        anchor = null
    )
}
