package com.folium.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageInkWindowTest {

    private val window = PageInkWindow()

    @Test fun `the window keeps the visible pages and one page either side, visible pages first`() {
        val change = window.show(visible = setOf(5), margin = 1, pageCount = 20)

        assertEquals(setOf(4, 5, 6), window.pages)
        assertEquals(listOf(5, 4, 6), change.entered.map { it.page })
        assertEquals(emptySet<Int>(), change.evicted)
    }

    @Test fun `a spread keeps a whole spread either side when its margin is two`() {
        window.show(visible = setOf(4, 5), margin = 2, pageCount = 20)

        assertEquals(setOf(2, 3, 4, 5, 6, 7), window.pages)
    }

    @Test fun `the window stays within the document`() {
        window.show(visible = setOf(0), margin = 1, pageCount = 1)

        assertEquals(setOf(0), window.pages)
    }

    @Test fun `moving on evicts pages that left the window and loads only the new ones`() {
        window.show(visible = setOf(5), margin = 1, pageCount = 20)

        val change = window.show(visible = setOf(6), margin = 1, pageCount = 20)

        assertEquals(setOf(4), change.evicted)
        assertEquals(listOf(7), change.entered.map { it.page })
        assertEquals(setOf(5, 6, 7), window.pages)
    }

    @Test fun `a changed page gets a new version and its earlier load is no longer current`() {
        val loaded = window.show(visible = setOf(5), margin = 1, pageCount = 20).entered.first { it.page == 5 }

        val bumped = window.invalidate(5)

        assertNotEquals(loaded.version, bumped)
        assertFalse(window.isCurrent(5, loaded.version))
        assertTrue(window.isCurrent(5, requireNotNull(bumped)))
    }

    @Test fun `a change to a page outside the window is ignored`() {
        window.show(visible = setOf(5), margin = 1, pageCount = 20)

        assertNull(window.invalidate(9))
    }

    @Test fun `an evicted page is never current, and coming back gives it a fresh version`() {
        val first = window.show(visible = setOf(5), margin = 1, pageCount = 20).entered.first { it.page == 4 }
        window.show(visible = setOf(9), margin = 1, pageCount = 20)

        assertFalse(window.isCurrent(4, first.version))

        val again = window.show(visible = setOf(5), margin = 1, pageCount = 20).entered.first { it.page == 4 }

        assertNotEquals(first.version, again.version)
        assertTrue(window.isCurrent(4, again.version))
    }
}
