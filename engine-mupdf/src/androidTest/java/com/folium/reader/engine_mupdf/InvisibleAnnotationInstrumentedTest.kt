package com.folium.reader.engine_mupdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.RenderSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `invisible-annotations.pdf` carries one page with an AutoCAD-style Square annotation (no /AP, no
 * /IC, zero-width /Border) alongside a visible Square annotation (an /IC fill, no /AP) and a Link
 * annotation, exercising the page's very first [com.folium.reader.core.pdf.PdfDocument.pageInfo]
 * and render through the same annotation-dropping path that [MuPdfDocument] runs before that first
 * load.
 */
@RunWith(AndroidJUnit4::class)
class InvisibleAnnotationInstrumentedTest {
    private fun fixture(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        return File(context.cacheDir, name).also { output ->
            context.assets.open("pdf/$name").use { input -> output.outputStream().use(input::copyTo) }
        }
    }

    @Test fun pageWithAnInvisibleAndAVisibleAnnotationStillOpensAndRendersTheVisibleOneOnly() {
        val baseline = MuPdfNativeOwnerTracker.snapshot()

        MuPdfEngine().open(PdfSource(fixture("invisible-annotations.pdf").absolutePath)).use { document ->
            assertEquals(1, document.pageCount)

            val info = document.pageInfo(0)
            assertEquals(612f, info.width, 0.01f)
            assertEquals(792f, info.height, 0.01f)

            document.buildDisplayList(0).use { displayList ->
                val raster = displayList.render(RenderSpec(612, 792))

                assertTrue(
                    "the /IC-filled Square annotation at PDF Rect [300 600 400 650] must still paint",
                    isRedPixel(raster.rgba, raster.width, x = 350, y = 167)
                )
                assertTrue(
                    "the AutoCAD-style Square annotation at PDF Rect [100 600 200 650] must paint nothing",
                    isWhitePixel(raster.rgba, raster.width, x = 150, y = 167)
                )
            }

            // Calling pageInfo again exercises the same page's second load, past the one-time
            // annotation filter, and must still see the same geometry.
            val secondInfo = document.pageInfo(0)
            assertEquals(info.width, secondInfo.width, 0.01f)
            assertEquals(info.height, secondInfo.height, 0.01f)
        }

        assertEquals(baseline, MuPdfNativeOwnerTracker.snapshot())
    }

    private fun isRedPixel(rgba: ByteArray, width: Int, x: Int, y: Int): Boolean {
        val offset = (y * width + x) * 4
        val red = rgba[offset].toInt() and 0xff
        val green = rgba[offset + 1].toInt() and 0xff
        val blue = rgba[offset + 2].toInt() and 0xff
        return red > 200 && green < 80 && blue < 80
    }

    private fun isWhitePixel(rgba: ByteArray, width: Int, x: Int, y: Int): Boolean {
        val offset = (y * width + x) * 4
        val red = rgba[offset].toInt() and 0xff
        val green = rgba[offset + 1].toInt() and 0xff
        val blue = rgba[offset + 2].toInt() and 0xff
        return red > 240 && green > 240 && blue > 240
    }
}
