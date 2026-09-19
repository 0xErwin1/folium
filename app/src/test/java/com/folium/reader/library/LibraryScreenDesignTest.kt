package com.folium.reader.library

import androidx.compose.ui.unit.dp
import com.folium.reader.core.library.AppearanceMode
import com.folium.reader.ui.FoliumGrid
import com.folium.reader.ui.resolveColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The system draws a cover and a shelf row without a border: a book's shape comes from its own
 * field tone and, on the list, from a hairline rule between rows — never from an outline drawn
 * around it. These decisions are pulled out of their composables so a regression back to a boxed
 * look shows up here first.
 */
class LibraryScreenDesignTest {

    /**
     * The bug this guards: a cover with no thumbnail, or one whose page happens to be blank white,
     * used to sit on the same paper tone as the page behind it and vanish once its border was
     * removed. The field tone is what keeps the slot visible without a border.
     */
    @Test fun `a cover's placeholder tone is the field, not the paper behind it`() {
        val scheme = resolveColorScheme(AppearanceMode.LIGHT, systemDark = false)

        assertEquals(scheme.surfaceVariant, coverBackgroundColor(scheme))
        assertNotEquals(scheme.surface, coverBackgroundColor(scheme))
    }

    /** The row separates from the one below it with the line token, never with the header's own ink rule. */
    @Test fun `a row's own rule reads as a line, not as the header's ink`() {
        val scheme = resolveColorScheme(AppearanceMode.LIGHT, systemDark = false)

        assertEquals(scheme.outlineVariant, rowDividerColor(scheme))
        assertNotEquals(scheme.onSurface, rowDividerColor(scheme))
    }

    /** Reposo is a 1px line; focus alone — not the signal colour — thickens it to a 2px ink border. */
    @Test fun `the search field's border thickens and turns to ink only once it has focus`() {
        val scheme = resolveColorScheme(AppearanceMode.LIGHT, systemDark = false)

        val resting = searchFieldBorder(focused = false, scheme = scheme)
        val focused = searchFieldBorder(focused = true, scheme = scheme)

        assertEquals(1.dp, resting.width)
        assertEquals(scheme.outline, resting.color)

        assertEquals(2.dp, focused.width)
        assertEquals(scheme.onSurface, focused.color)
        assertNotEquals(scheme.tertiary, focused.color)
    }

    /**
     * The system draws one cover ratio, 1:1.4 ([FoliumGrid.COVER_ASPECT]), everywhere a cover-shaped
     * surface reads as the cover atom: the grid cell, the row thumbnail, the detail hero and the
     * two-pane hero all share [FoliumGrid.COVER_ASPECT] as [BookCover]'s own default rather than each
     * carrying a ratio of their own.
     */
    @Test fun `a cover reads at the system's own 1 to 1,4 ratio by default`() {
        assertEquals(0.712f, FoliumGrid.COVER_ASPECT, 0.0001f)
    }

    /**
     * The continue-reading hero is the one surface the design draws at 3:4 instead of the cover
     * atom's own ratio (S-Library.dc.html: a 180×240 hero against an 84×118 cover), so it is the one
     * caller that passes its own aspect ratio into [BookCover] rather than taking the default.
     */
    @Test fun `the continue-reading hero keeps its own 3 to 4 exception`() {
        assertEquals(3f / 4f, ContinueReadingCoverAspectRatio, 0.0001f)
        assertNotEquals(FoliumGrid.COVER_ASPECT.toDouble(), ContinueReadingCoverAspectRatio.toDouble())
    }

    /** The row's thumbnail is the cover atom at a smaller width, not a shape of its own. */
    @Test fun `the row's thumbnail keeps the cover atom's own ratio at its own width`() {
        val expectedHeight = ThumbnailWidth / FoliumGrid.COVER_ASPECT

        assertEquals(expectedHeight.value, ThumbnailHeight.value, 0.01f)
    }

    /** Every palette carries the same rule: cover slot is field, row rule is line, focus is 2px ink. */
    @Test fun `every palette keeps the same field-versus-paper and line-versus-ink distinctions`() {
        AppearanceMode.entries.forEach { mode ->
            val scheme = resolveColorScheme(mode, systemDark = false)

            assertNotEquals("$mode", scheme.surface, coverBackgroundColor(scheme))
            assertNotEquals("$mode", scheme.onSurface, rowDividerColor(scheme))
        }
    }
}
