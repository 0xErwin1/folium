package com.folium.reader.engine_mupdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.RenderSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MuPdfBridgeInstrumentedTest {
    private fun fixture(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        return File(context.cacheDir, name).also { output ->
            context.assets.open("pdf/$name").use { input -> output.outputStream().use(input::copyTo) }
        }
    }

    @Test fun oneEngineHasOneActiveDocumentAndCloseIsIdempotent() {
        val engine = MuPdfEngine()
        val document = engine.open(PdfSource(fixture("native-english.pdf").absolutePath))
        assertEquals(PdfFailure.Resource(true), org.junit.Assert.assertThrows(PdfException::class.java) {
            engine.open(PdfSource(fixture("scan-english.pdf").absolutePath))
        }.failure)
        val displayList = document.buildDisplayList(0)
        displayList.close()
        displayList.close()
        assertEquals(PdfFailure.Closed, org.junit.Assert.assertThrows(PdfException::class.java) {
            displayList.render(RenderSpec(10, 10))
        }.failure)
        document.close()
        document.close()
        displayList.close()
        assertEquals(PdfFailure.Closed, org.junit.Assert.assertThrows(PdfException::class.java) { document.pageCount }.failure)
        engine.open(PdfSource(fixture("native-english.pdf").absolutePath)).close()
    }

    @Test fun nativeTextExtractionCleansPageAndStructuredTextOnSuccessAndFailure() {
        val baseline = MuPdfNativeOwnerTracker.snapshot()
        MuPdfEngine().open(PdfSource(fixture("native-english.pdf").absolutePath)).use { document ->
            val text = document.extractText(0)
            assertTrue(text.contains("English library: reader search"))
        }
        assertEquals(baseline, MuPdfNativeOwnerTracker.snapshot())

        MuPdfNativeOwnerTracker.failAfterNextTextExtraction()
        try {
            MuPdfEngine().open(PdfSource(fixture("native-english.pdf").absolutePath)).use { document ->
                val failure = org.junit.Assert.assertThrows(IllegalStateException::class.java) { document.extractText(0) }
                assertEquals("forced text extraction failure", failure.message)
            }
        } finally {
            MuPdfNativeOwnerTracker.clearFailure()
        }
        assertEquals(baseline, MuPdfNativeOwnerTracker.snapshot())
    }

    @Test fun fixturePageInfoAndRasterGeometryAreExact() {
        MuPdfEngine().open(PdfSource(fixture("native-english.pdf").absolutePath)).use { document ->
            assertEquals(1, document.pageCount)
            val info = document.pageInfo(0)
            assertEquals(612f, info.width, 0.01f)
            assertEquals(792f, info.height, 0.01f)
            assertEquals(0, info.rotationDegrees)
            document.buildDisplayList(0).use { displayList ->
                val raster = displayList.render(RenderSpec(240, 320))
                assertEquals(240, raster.width)
                assertEquals(320, raster.height)
                assertMeaningfulPixels(raster.rgba)
            }
        }

        MuPdfEngine().open(PdfSource(fixture("rotated-cropped-large.pdf").absolutePath)).use { document ->
            assertEquals(1, document.pageCount)
            val info = document.pageInfo(0)
            assertEquals(1900f, info.width, 0.01f)
            assertEquals(1200f, info.height, 0.01f)
            assertEquals(90, info.rotationDegrees)
            document.buildDisplayList(0).use { displayList ->
                val full = displayList.render(RenderSpec(240, 320))
                val cropped = displayList.render(RenderSpec(240, 320, PageSpaceRect(.05f, .05f, .95f, .95f)))
                assertEquals(240, full.width)
                assertEquals(320, full.height)
                assertEquals(240, cropped.width)
                assertEquals(320, cropped.height)
                assertMeaningfulPixels(full.rgba)
                assertMeaningfulPixels(cropped.rgba)
                assertNotEquals(full.rgba.contentHashCode(), cropped.rgba.contentHashCode())
            }
        }
    }

    @Test fun mapsCorruptAndPasswordFailuresAndSurvivesRepeatedLifecycle() {
        val engine = MuPdfEngine()
        listOf("corrupt.pdf" to PdfFailure.Corrupt, "password-protected.pdf" to PdfFailure.PasswordRequired).forEach { (name, expected) ->
            val error = org.junit.Assert.assertThrows(PdfException::class.java) { engine.open(PdfSource(fixture(name).absolutePath)) }
            assertEquals(expected, error.failure)
        }
        repeat(10) {
            engine.open(PdfSource(fixture("scan-english.pdf").absolutePath)).use { document ->
                document.buildDisplayList(0).use { displayList ->
                    val raster = displayList.render(RenderSpec(90, 120))
                    assertEquals(90 * 120 * 4, raster.rgba.size)
                    assertMeaningfulPixels(raster.rgba)
                }
            }
        }
    }

    private fun assertMeaningfulPixels(pixels: ByteArray) {
        assertTrue(pixels.any { it.toInt() and 0xff != 255 })
        assertTrue(pixels.map { it.toInt() and 0xff }.toSet().size > 2)
    }
}
