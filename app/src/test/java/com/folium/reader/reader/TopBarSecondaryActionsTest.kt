package com.folium.reader.reader

import com.folium.reader.ui.FoliumWidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarSecondaryActionsTest {

    @Test fun `a phone draws no direct action and keeps the overflow`() {
        val composition = topBarComposition(FoliumWidthClass.COMPACT)

        assertEquals(emptyList<TopBarSecondaryAction>(), composition.directActions)
        assertTrue(composition.overflowShown)
    }

    @Test fun `a medium window draws contents, search then book settings, with no overflow`() {
        val composition = topBarComposition(FoliumWidthClass.MEDIUM)

        assertEquals(
            listOf(TopBarSecondaryAction.CONTENTS, TopBarSecondaryAction.SEARCH, TopBarSecondaryAction.BOOK_SETTINGS),
            composition.directActions
        )
        assertFalse(composition.overflowShown)
    }

    @Test fun `an expanded window draws contents, search then book settings, with no overflow`() {
        val composition = topBarComposition(FoliumWidthClass.EXPANDED)

        assertEquals(
            listOf(TopBarSecondaryAction.CONTENTS, TopBarSecondaryAction.SEARCH, TopBarSecondaryAction.BOOK_SETTINGS),
            composition.directActions
        )
        assertFalse(composition.overflowShown)
    }
}
