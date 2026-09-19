package com.folium.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSettingsSectionsTest {

    @Test fun `a reflowable document shows its own typography sections`() {
        val sections = resolveBookSettingsSections(reflowable = true, spread = ReaderSpreadState())

        assertTrue(sections.showsTextSection)
        assertTrue(sections.showsPageBackgroundOption)
        assertTrue(sections.showsScopeFooter)
    }

    @Test fun `a fixed-layout document shows only the page section, with no scope footer`() {
        val sections = resolveBookSettingsSections(reflowable = false, spread = ReaderSpreadState())

        assertFalse(sections.showsTextSection)
        assertFalse(sections.showsPageBackgroundOption)
        assertFalse(sections.showsScopeFooter)
    }

    @Test fun `two pages is enabled once the window qualifies, reflowable or not`() {
        val qualifying = ReaderSpreadState(windowQualifies = true)

        assertTrue(resolveBookSettingsSections(reflowable = true, spread = qualifying).twoPagesRowEnabled)
        assertTrue(resolveBookSettingsSections(reflowable = false, spread = qualifying).twoPagesRowEnabled)
    }

    @Test fun `two pages stays disabled rather than hidden when the window does not qualify`() {
        val narrow = ReaderSpreadState(windowQualifies = false)

        assertEquals(false, resolveBookSettingsSections(reflowable = true, spread = narrow).twoPagesRowEnabled)
        assertEquals(false, resolveBookSettingsSections(reflowable = false, spread = narrow).twoPagesRowEnabled)
    }
}
