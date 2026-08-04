package com.folium.reader.ui

import com.folium.reader.core.library.AppearanceMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoliumThemeTest {

    @Test fun system_appearance_tracks_the_system_mode() {
        assertFalse(resolveDarkTheme(AppearanceMode.SYSTEM, systemDark = false))
        assertTrue(resolveDarkTheme(AppearanceMode.SYSTEM, systemDark = true))
    }

    @Test fun light_appearance_ignores_the_system_mode() {
        assertFalse(resolveDarkTheme(AppearanceMode.LIGHT, systemDark = false))
        assertFalse(resolveDarkTheme(AppearanceMode.LIGHT, systemDark = true))
    }

    @Test fun dark_appearance_ignores_the_system_mode() {
        assertTrue(resolveDarkTheme(AppearanceMode.DARK, systemDark = false))
        assertTrue(resolveDarkTheme(AppearanceMode.DARK, systemDark = true))
    }
}
