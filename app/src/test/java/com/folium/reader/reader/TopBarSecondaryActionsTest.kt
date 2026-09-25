package com.folium.reader.reader

import com.folium.reader.ui.FoliumWidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarSecondaryActionsTest {

    private val bookActions = listOf(TopBarSecondaryAction.CONTENTS, TopBarSecondaryAction.SEARCH, TopBarSecondaryAction.BOOK_SETTINGS)

    private val overflowBookActions = listOf(TopBarSecondaryAction.SEARCH, TopBarSecondaryAction.CONTENTS, TopBarSecondaryAction.BOOK_SETTINGS)

    private val historyActions = listOf(TopBarSecondaryAction.UNDO, TopBarSecondaryAction.REDO)

    @Test fun `a phone draws no direct action and keeps the overflow`() {
        val composition = topBarComposition(FoliumWidthClass.COMPACT, sheetCurrent = false, canCreateSheet = false)

        assertEquals(emptyList<TopBarSecondaryAction>(), composition.directActions)
        assertEquals(overflowBookActions, composition.overflowActions)
        assertTrue(composition.overflowShown)
    }

    @Test fun `a medium window draws contents, search then book settings, with no overflow`() {
        val composition = topBarComposition(FoliumWidthClass.MEDIUM, sheetCurrent = false, canCreateSheet = false)

        assertEquals(bookActions, composition.directActions)
        assertEquals(emptyList<TopBarSecondaryAction>(), composition.overflowActions)
        assertFalse(composition.overflowShown)
    }

    @Test fun `an expanded window draws contents, search then book settings, with no overflow`() {
        val composition = topBarComposition(FoliumWidthClass.EXPANDED, sheetCurrent = false, canCreateSheet = false)

        assertEquals(bookActions, composition.directActions)
        assertFalse(composition.overflowShown)
    }

    @Test fun `a phone on a sheet draws undo and redo directly and keeps the overflow`() {
        val composition = topBarComposition(FoliumWidthClass.COMPACT, sheetCurrent = true, canCreateSheet = false)

        assertEquals(historyActions, composition.directActions)
        assertEquals(overflowBookActions, composition.overflowActions)
        assertTrue(composition.overflowShown)
    }

    @Test fun `a medium window on a sheet draws undo and redo before the book's own actions`() {
        val composition = topBarComposition(FoliumWidthClass.MEDIUM, sheetCurrent = true, canCreateSheet = false)

        assertEquals(historyActions + bookActions, composition.directActions)
        assertFalse(composition.overflowShown)
    }

    @Test fun `an expanded window on a sheet draws undo and redo before the book's own actions`() {
        val composition = topBarComposition(FoliumWidthClass.EXPANDED, sheetCurrent = true, canCreateSheet = false)

        assertEquals(historyActions + bookActions, composition.directActions)
        assertFalse(composition.overflowShown)
    }

    @Test fun `a phone offers a new sheet first in its overflow`() {
        val composition = topBarComposition(FoliumWidthClass.COMPACT, sheetCurrent = false, canCreateSheet = true)

        assertEquals(emptyList<TopBarSecondaryAction>(), composition.directActions)
        assertEquals(listOf(TopBarSecondaryAction.NEW_SHEET) + overflowBookActions, composition.overflowActions)
    }

    @Test fun `a phone on a sheet draws undo and redo and offers a new sheet in its overflow`() {
        val composition = topBarComposition(FoliumWidthClass.COMPACT, sheetCurrent = true, canCreateSheet = true)

        assertEquals(historyActions, composition.directActions)
        assertEquals(listOf(TopBarSecondaryAction.NEW_SHEET) + overflowBookActions, composition.overflowActions)
    }

    @Test fun `a medium window draws the new sheet mark before the book's own actions`() {
        val composition = topBarComposition(FoliumWidthClass.MEDIUM, sheetCurrent = false, canCreateSheet = true)

        assertEquals(listOf(TopBarSecondaryAction.NEW_SHEET) + bookActions, composition.directActions)
        assertFalse(composition.overflowShown)
    }

    @Test fun `an expanded window on a sheet draws undo, redo, then the new sheet mark`() {
        val composition = topBarComposition(FoliumWidthClass.EXPANDED, sheetCurrent = true, canCreateSheet = true)

        assertEquals(historyActions + TopBarSecondaryAction.NEW_SHEET + bookActions, composition.directActions)
        assertFalse(composition.overflowShown)
    }
}
