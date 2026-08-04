package com.folium.reader.ui

import androidx.compose.ui.graphics.Color
import com.folium.reader.core.library.AppearanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FoliumThemeTest {

    @Test fun system_appearance_tracks_the_system_mode() {
        assertSame(
            resolveColorScheme(AppearanceMode.LIGHT, systemDark = true),
            resolveColorScheme(AppearanceMode.SYSTEM, systemDark = false)
        )
        assertSame(
            resolveColorScheme(AppearanceMode.DARK, systemDark = false),
            resolveColorScheme(AppearanceMode.SYSTEM, systemDark = true)
        )
    }

    @Test fun light_appearance_ignores_the_system_mode() {
        assertSame(
            resolveColorScheme(AppearanceMode.LIGHT, systemDark = false),
            resolveColorScheme(AppearanceMode.LIGHT, systemDark = true)
        )
    }

    @Test fun dark_appearance_ignores_the_system_mode() {
        assertSame(
            resolveColorScheme(AppearanceMode.DARK, systemDark = false),
            resolveColorScheme(AppearanceMode.DARK, systemDark = true)
        )
    }

    @Test fun e_ink_appearances_ignore_the_system_mode() {
        assertSame(
            resolveColorScheme(AppearanceMode.E_INK_LIGHT, systemDark = false),
            resolveColorScheme(AppearanceMode.E_INK_LIGHT, systemDark = true)
        )
        assertSame(
            resolveColorScheme(AppearanceMode.E_INK_DARK, systemDark = false),
            resolveColorScheme(AppearanceMode.E_INK_DARK, systemDark = true)
        )
    }

    @Test fun system_bar_icons_follow_the_resolved_palette() {
        assertTrue(usesDarkSystemBarIcons(AppearanceMode.SYSTEM, systemDark = false))
        assertFalse(usesDarkSystemBarIcons(AppearanceMode.SYSTEM, systemDark = true))
        assertTrue(usesDarkSystemBarIcons(AppearanceMode.LIGHT, systemDark = true))
        assertTrue(usesDarkSystemBarIcons(AppearanceMode.E_INK_LIGHT, systemDark = true))
        assertFalse(usesDarkSystemBarIcons(AppearanceMode.DARK, systemDark = false))
        assertFalse(usesDarkSystemBarIcons(AppearanceMode.E_INK_DARK, systemDark = false))
    }

    @Test fun e_ink_light_palette_uses_neutral_paper_with_high_contrast_color_accents() {
        val scheme = resolveColorScheme(AppearanceMode.E_INK_LIGHT, systemDark = false)

        assertEquals(Color(0xFFF4F4EF), scheme.background)
        assertEquals(Color(0xFF171816), scheme.onBackground)
        assertEquals(Color(0xFFF4F4EF), scheme.surface)
        assertEquals(Color(0xFF171816), scheme.onSurface)
        assertEquals(Color(0xFFE8E8E1), scheme.surfaceVariant)
        assertEquals(Color(0xFF444640), scheme.onSurfaceVariant)
        assertEquals(Color(0xFFD6D8D0), scheme.primaryContainer)
        assertEquals(Color(0xFF171816), scheme.onPrimaryContainer)
        assertEquals(Color(0xFFD8DAD2), scheme.secondaryContainer)
        assertEquals(Color(0xFF171816), scheme.onSecondaryContainer)
        assertEquals(Color(0xFF806200), scheme.tertiary)
        assertEquals(Color(0xFFEFE5C5), scheme.tertiaryContainer)
        assertEquals(Color(0xFF302400), scheme.onTertiaryContainer)
        assertEquals(Color(0xFFDDDED7), scheme.surfaceDim)
        assertEquals(Color(0xFFFAFAF6), scheme.surfaceBright)
        assertEquals(Color(0xFFFAFAF6), scheme.surfaceContainerLowest)
        assertEquals(Color(0xFFF0F0EA), scheme.surfaceContainerLow)
        assertEquals(Color(0xFFEBEBE4), scheme.surfaceContainer)
        assertEquals(Color(0xFFE5E6DF), scheme.surfaceContainerHigh)
        assertEquals(Color(0xFFDEE0D8), scheme.surfaceContainerHighest)
        assertEquals(Color(0xFFB5B8AF), scheme.outlineVariant)
    }

    @Test fun e_ink_dark_palette_uses_charcoal_surfaces_with_warm_text_and_restrained_accents() {
        val scheme = resolveColorScheme(AppearanceMode.E_INK_DARK, systemDark = false)

        assertEquals(Color(0xFF171816), scheme.background)
        assertEquals(Color(0xFFF3F2E8), scheme.onBackground)
        assertEquals(Color(0xFF171816), scheme.surface)
        assertEquals(Color(0xFFF3F2E8), scheme.onSurface)
        assertEquals(Color(0xFF30322E), scheme.surfaceVariant)
        assertEquals(Color(0xFFD2D3CA), scheme.onSurfaceVariant)
        assertEquals(Color(0xFF444740), scheme.primaryContainer)
        assertEquals(Color(0xFFFAFAF2), scheme.onPrimaryContainer)
        assertEquals(Color(0xFF373934), scheme.secondaryContainer)
        assertEquals(Color(0xFFF2F1E8), scheme.onSecondaryContainer)
        assertEquals(Color(0xFFE0BC52), scheme.tertiary)
        assertEquals(Color(0xFF443A1C), scheme.tertiaryContainer)
        assertEquals(Color(0xFFF6E6B3), scheme.onTertiaryContainer)
        assertEquals(Color(0xFFF0A8A8), scheme.error)
        assertEquals(Color(0xFF11120F), scheme.surfaceDim)
        assertEquals(Color(0xFF3B3D38), scheme.surfaceBright)
        assertEquals(Color(0xFF0E0F0D), scheme.surfaceContainerLowest)
        assertEquals(Color(0xFF1B1C19), scheme.surfaceContainerLow)
        assertEquals(Color(0xFF20211E), scheme.surfaceContainer)
        assertEquals(Color(0xFF282A26), scheme.surfaceContainerHigh)
        assertEquals(Color(0xFF32342F), scheme.surfaceContainerHighest)
        assertEquals(Color(0xFF4A4C46), scheme.outlineVariant)
    }

}
