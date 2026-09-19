package com.folium.reader.reader

import com.folium.reader.ui.FoliumWidthClass
import org.junit.Assert.assertEquals
import org.junit.Test

class TopBarSecondaryActionsTest {

    @Test fun `a phone shows search before contents`() {
        val order = topBarSecondaryActions(FoliumWidthClass.COMPACT)

        assertEquals(listOf(TopBarSecondaryAction.SEARCH, TopBarSecondaryAction.CONTENTS), order)
    }

    @Test fun `a medium window shows contents before search`() {
        val order = topBarSecondaryActions(FoliumWidthClass.MEDIUM)

        assertEquals(listOf(TopBarSecondaryAction.CONTENTS, TopBarSecondaryAction.SEARCH), order)
    }

    @Test fun `an expanded window shows contents before search`() {
        val order = topBarSecondaryActions(FoliumWidthClass.EXPANDED)

        assertEquals(listOf(TopBarSecondaryAction.CONTENTS, TopBarSecondaryAction.SEARCH), order)
    }
}
