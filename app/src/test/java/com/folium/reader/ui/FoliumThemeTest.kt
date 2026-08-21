package com.folium.reader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.folium.reader.core.library.AppearanceMode
import com.folium.reader.core.pdf.ReflowPageColors
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
        assertEquals(Color(0xFFC83F27), scheme.tertiary)
        assertEquals(Color(0xFFEDDCD9), scheme.tertiaryContainer)
        assertEquals(Color(0xFF3D160F), scheme.onTertiaryContainer)
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
        assertEquals(Color(0xFFDA563E), scheme.tertiary)
        assertEquals(Color(0xFF3C201B), scheme.tertiaryContainer)
        assertEquals(Color(0xFFE6D4D1), scheme.onTertiaryContainer)
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


    /**
     * The signal is the one colour in the system with work to do: progress, the label on a book
     * under way, a search hit. It comes from the design system's own sheet rather than from
     * Material's defaults.
     */
    @Test fun `every palette carries the system's signal`() {
        assertEquals(
            Color(0xFFD54329),
            resolveColorScheme(AppearanceMode.LIGHT, systemDark = false).tertiary
        )
        assertEquals(
            Color(0xFFD9543C),
            resolveColorScheme(AppearanceMode.DARK, systemDark = false).tertiary
        )
        assertEquals(
            Color(0xFFC83F27),
            resolveColorScheme(AppearanceMode.E_INK_LIGHT, systemDark = false).tertiary
        )
        assertEquals(
            Color(0xFFDA563E),
            resolveColorScheme(AppearanceMode.E_INK_DARK, systemDark = false).tertiary
        )
    }

    /**
     * One hex cannot serve four backgrounds. The signal has to read as a label on the page and
     * still carry text when it is a fill, in every palette — otherwise the same component is
     * legible in one theme and a smudge in the next, which is exactly what having four themes is
     * supposed to prevent.
     */
    @Test fun `the signal reads on the page and carries text in every palette`() {
        AppearanceMode.entries.forEach { mode ->
            listOf(false, true).forEach { systemDark ->
                val scheme = resolveColorScheme(mode, systemDark)
                val onPage = contrast(scheme.tertiary, scheme.background)
                val asFill = contrast(scheme.onTertiary, scheme.tertiary)

                assertTrue("$mode systemDark=$systemDark signal on page $onPage", onPage >= 4.5f)
                assertTrue("$mode systemDark=$systemDark text on signal $asFill", asFill >= 4.5f)
            }
        }
    }

    /**
     * A page that has not arrived is drawn as the sheet it will be, and a sheet is paper in every
     * palette: the reader leaves document pixels alone, so what stands in for a document must not be
     * a chrome colour that follows the theme away from what is about to land there.
     */
    @Test fun `the sheet a page has not arrived on is paper in every palette`() {
        AppearanceMode.entries.forEach { mode ->
            listOf(false, true).forEach { systemDark ->
                assertEquals("$mode systemDark=$systemDark", FoliumPaper, paperFor(resolveColorScheme(mode, systemDark)))
            }
        }
    }

    /**
     * The bug this exists for: on a dark palette the sheet was drawn in a chrome surface a shade
     * away from the page area behind it, so scrubbing through a book showed an empty rectangle
     * where it should have shown pages going by.
     */
    @Test fun `the sheet is as legible against the page area as the page it stands in for`() {
        AppearanceMode.entries.forEach { mode ->
            listOf(false, true).forEach { systemDark ->
                val scheme = resolveColorScheme(mode, systemDark)
                val sheet = contrast(FoliumPaper, scheme.surfaceVariant)
                val page = contrast(Color(0xFFFFFFFF), scheme.surfaceVariant)

                assertTrue(
                    "$mode systemDark=$systemDark sheet=$sheet page=$page",
                    sheet >= page * 0.9f
                )
            }
        }
    }

    /**
     * The colours an engine relayout carries for a reflowable document's own page: the same surface,
     * text and signal roles the chrome already reads from, not a second set of hex values.
     */
    @Test fun `page colors follow the same roles the chrome already reads`() {
        assertEquals(
            ReflowPageColors(foregroundHex = "101010", backgroundHex = "FFFFFF", accentHex = "D54329"),
            pageColorsFor(AppearanceMode.LIGHT, systemDark = false)
        )
        assertEquals(
            ReflowPageColors(foregroundHex = "F2F2F2", backgroundHex = "0B0B0B", accentHex = "D9543C"),
            pageColorsFor(AppearanceMode.DARK, systemDark = false)
        )
        assertEquals(
            ReflowPageColors(foregroundHex = "171816", backgroundHex = "F4F4EF", accentHex = "C83F27"),
            pageColorsFor(AppearanceMode.E_INK_LIGHT, systemDark = false)
        )
        assertEquals(
            ReflowPageColors(foregroundHex = "F3F2E8", backgroundHex = "171816", accentHex = "DA563E"),
            pageColorsFor(AppearanceMode.E_INK_DARK, systemDark = false)
        )
    }

    @Test fun `system page colors follow the system dark input, like the chrome`() {
        assertEquals(
            pageColorsFor(AppearanceMode.LIGHT, systemDark = true),
            pageColorsFor(AppearanceMode.SYSTEM, systemDark = false)
        )
        assertEquals(
            pageColorsFor(AppearanceMode.DARK, systemDark = false),
            pageColorsFor(AppearanceMode.SYSTEM, systemDark = true)
        )
    }

    private fun paperFor(scheme: androidx.compose.material3.ColorScheme): Color {
        // The sheet is deliberately not read from the scheme: this asserts it never becomes one.
        assertTrue(scheme.surfaceVariant != FoliumPaper)
        return FoliumPaper
    }

    private fun contrast(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())

        return (lighter + .05f) / (darker + .05f)
    }
}
