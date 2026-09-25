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
        val composition = topBarComposition(FoliumWidthClass.COMPACT, sheetCurrent = false)

        assertEquals(emptyList<TopBarSecondaryAction>(), composition.directActions)
        assertEquals(overflowBookActions, composition.overflowActions)
        assertTrue(composition.overflowShown)
    }

    @Test fun `a medium window draws contents, search then book settings, with no overflow`() {
        val composition = topBarComposition(FoliumWidthClass.MEDIUM, sheetCurrent = false)

        assertEquals(bookActions, composition.directActions)
        assertEquals(emptyList<TopBarSecondaryAction>(), composition.overflowActions)
        assertFalse(composition.overflowShown)
    }

    @Test fun `an expanded window draws contents, search then book settings, with no overflow`() {
        val composition = topBarComposition(FoliumWidthClass.EXPANDED, sheetCurrent = false)

        assertEquals(bookActions, composition.directActions)
        assertFalse(composition.overflowShown)
    }

    @Test fun `a phone on a sheet offers undo and redo first in its overflow`() {
        val composition = topBarComposition(FoliumWidthClass.COMPACT, sheetCurrent = true)

        assertEquals(emptyList<TopBarSecondaryAction>(), composition.directActions)
        assertEquals(historyActions + overflowBookActions, composition.overflowActions)
        assertTrue(composition.overflowShown)
    }

    @Test fun `a medium window on a sheet draws undo and redo before the book's own actions`() {
        val composition = topBarComposition(FoliumWidthClass.MEDIUM, sheetCurrent = true)

        assertEquals(historyActions + bookActions, composition.directActions)
        assertFalse(composition.overflowShown)
    }

    @Test fun `an expanded window on a sheet draws undo and redo before the book's own actions`() {
        val composition = topBarComposition(FoliumWidthClass.EXPANDED, sheetCurrent = true)

        assertEquals(historyActions + bookActions, composition.directActions)
        assertFalse(composition.overflowShown)
    }
}
