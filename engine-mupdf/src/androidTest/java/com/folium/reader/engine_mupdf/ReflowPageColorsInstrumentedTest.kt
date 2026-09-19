package com.folium.reader.engine_mupdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.ReflowFontFamily
import com.folium.reader.core.pdf.ReflowLayoutBox
import com.folium.reader.core.pdf.ReflowPageBackground
import com.folium.reader.core.pdf.ReflowPageColors
import com.folium.reader.core.pdf.ReflowSettings
import com.folium.reader.core.pdf.ReflowStyleSheet
import com.folium.reader.core.pdf.ReflowTextAlign
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.pdf.TypographyPreset
import java.io.File
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves that page colours the app resolves from an appearance mode actually reach the engine's
 * rendered raster — not just the CSS string handed to [ReflowStyleSheet.build] — by sampling a
 * background pixel before and after a relayout carries them.
 */
@RunWith(AndroidJUnit4::class)
class ReflowPageColorsInstrumentedTest {

    private fun fixture(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        return File(context.cacheDir, name).also { output ->
            context.assets.open(name).use { input -> output.outputStream().use(input::copyTo) }
        }
    }

    private val box = ReflowLayoutBox(450f, 675f, 18f)
    private val preset = TypographyPreset(
        fontFamily = ReflowFontFamily.PUBLISHER,
        fontSizePoints = 18f,
        lineHeight = null,
        marginEm = 0f,
        textAlign = ReflowTextAlign.PUBLISHER,
        paragraphIndentEm = null,
        pageBackground = ReflowPageBackground.MATCH_APP_THEME
    )

    // Mirrors app/ui/FoliumTheme.kt's own palettes: `surface` as the page background, `onSurface`
    // as the reading text, `tertiary` as the accent. Kept as literals here so this test proves the
    // pixels the engine actually draws rather than trusting the app module's own mapping.
    private val lightColors = ReflowPageColors(foregroundHex = "101010", backgroundHex = "FFFFFF", accentHex = "D54329")
    private val darkColors = ReflowPageColors(foregroundHex = "F2F2F2", backgroundHex = "0B0B0B", accentHex = "D9543C")
    private val eInkLightColors = ReflowPageColors(foregroundHex = "171816", backgroundHex = "F4F4EF", accentHex = "C83F27")
    private val eInkDarkColors = ReflowPageColors(foregroundHex = "F3F2E8", backgroundHex = "171816", accentHex = "DA563E")

    private fun backgroundPixel(colors: ReflowPageColors?): Triple<Int, Int, Int> {
        MuPdfEngine().open(PdfSource(fixture("reflowable.epub").absolutePath)).use { document ->
            val settings = ReflowSettings(box, ReflowStyleSheet.build(preset, colors))
            assertTrue(document.relayout(settings))

            document.buildDisplayList(0).use { displayList ->
                val raster = displayList.render(RenderSpec(60, 90))
                // Top-left corner: above and left of the first paragraph's margin, so it samples the
                // page background rather than glyph ink.
                val offset = ((2 * raster.width) + 2) * 4
                val r = raster.rgba[offset].toInt() and 0xFF
                val g = raster.rgba[offset + 1].toInt() and 0xFF
                val b = raster.rgba[offset + 2].toInt() and 0xFF
                return Triple(r, g, b)
            }
        }
    }

    private fun brightness(pixel: Triple<Int, Int, Int>): Int = (pixel.first + pixel.second + pixel.third) / 3

    @Test fun darkPaletteColoursDarkenTheRenderedBackgroundAgainstNoColours() {
        val uncoloured = backgroundPixel(null)
        val dark = backgroundPixel(darkColors)

        assertTrue("expected an uncoloured page to render light, was $uncoloured", brightness(uncoloured) > 200)
        assertTrue("expected a dark-palette page to render dark, was $dark", brightness(dark) < 60)
    }

    @Test fun theFourNonSystemModesProduceDistinguishableBackgrounds() {
        val light = backgroundPixel(lightColors)
        val dark = backgroundPixel(darkColors)
        val eInkLight = backgroundPixel(eInkLightColors)
        val eInkDark = backgroundPixel(eInkDarkColors)

        val backgrounds = listOf(light, dark, eInkLight, eInkDark)
        for (i in backgrounds.indices) {
            for (j in i + 1 until backgrounds.size) {
                assertNotEquals("backgrounds at $i and $j must differ", backgrounds[i], backgrounds[j])
            }
        }
    }
}
