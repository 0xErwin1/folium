package com.folium.reader.engine_mupdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.text.TextSource
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            assertTrue(text.text.contains("English library: reader search"))
        }
        assertEquals(baseline, MuPdfNativeOwnerTracker.snapshot())

        MuPdfNativeOwnerTracker.failAfterNextTextExtraction()
        try {
            MuPdfEngine().open(PdfSource(fixture("native-english.pdf").absolutePath)).use { document ->
                val failure = org.junit.Assert.assertThrows(PdfException::class.java) { document.extractText(0) }
                assertEquals(PdfFailure.TextExtraction, failure.failure)
                assertEquals("forced text extraction failure", failure.cause?.message)
            }
        } finally {
            MuPdfNativeOwnerTracker.clearFailure()
        }
        assertEquals(baseline, MuPdfNativeOwnerTracker.snapshot())
    }

    @Test fun structuredTextMatchesNativeScannedMixedAndTransformedCorpus() {
        val expectedNativeWords = mapOf(
            "native-spanish.pdf" to listOf("Biblioteca", "espanola:", "corazon", "lectura"),
            "native-english.pdf" to listOf("English", "library:", "reader", "search"),
            "native-mixed.pdf" to listOf("Biblioteca", "library:", "espanol", "English")
        )
        expectedNativeWords.forEach { (name, expectedWords) ->
            MuPdfEngine().open(PdfSource(corpusFixture(name).absolutePath)).use { document ->
                val page = document.extractText(0)
                assertStructuredNativePage(page)
                assertEquals("$name reading order", expectedWords, page.words.map { it.text })
                assertLeftToRight(page.words.map { it.box })
                assertEquals("$name first word starts at authored x=72", 72f / 612f, page.words.first().box.left, 0.002f)
                assertEquals("$name text top must match the authored y=720 baseline", 0.0665f, page.words.first().box.top, 0.002f)
                assertEquals(page, document.extractText(0))
            }
        }

        listOf("scan-spanish.pdf", "scan-english.pdf").forEach { name ->
            MuPdfEngine().open(PdfSource(corpusFixture(name).absolutePath)).use { document ->
                val page = document.extractText(0)
                assertEquals(TextSource.NATIVE_PDF, page.source)
                assertTrue("$name must have no native blocks", page.blocks.isEmpty())
                assertTrue("$name must have no native lines", page.lines.isEmpty())
                assertTrue("$name must have no native words", page.words.isEmpty())
                assertEquals("", page.text)
            }
        }

        MuPdfEngine().open(PdfSource(corpusFixture("mixed-native-scanned.pdf").absolutePath)).use { document ->
            val nativePage = document.extractText(0)
            assertStructuredNativePage(nativePage)
            assertEquals(listOf("Native", "evidence:", "reader"), nativePage.words.map { it.text })
            assertLeftToRight(nativePage.words.map { it.box })
            assertEquals(72f / 612f, nativePage.words.first().box.left, 0.002f)

            val scannedPage = document.extractText(1)
            assertEquals(TextSource.NATIVE_PDF, scannedPage.source)
            assertTrue(scannedPage.blocks.isEmpty())
            assertTrue(scannedPage.lines.isEmpty())
            assertTrue(scannedPage.words.isEmpty())
            assertEquals("", scannedPage.text)
        }

        MuPdfEngine().open(PdfSource(corpusFixture("rotated-cropped-large.pdf").absolutePath)).use { document ->
            val page = document.extractText(0)
            assertStructuredNativePage(page)
            assertFalse("the full token authored outside the CropBox must not be exposed", page.text.contains("Rotated"))

            val visibleWords = page.words.filter { it.text in setOf("cropped", "large", "page") }
            assertEquals(listOf("cropped", "large", "page"), visibleWords.map { it.text })
            assertTrue("rotation must turn the authored horizontal line into vertical PageSpace geometry", visibleWords.all {
                (it.box.bottom - it.box.top) > (it.box.right - it.box.left)
            })
            assertTrue("logical reading order must follow transformed top coordinates at 90 degrees", visibleWords.zipWithNext().all {
                (previous, next) -> next.box.top > previous.box.top
            })
            assertTrue("all words from one rotated line must share its narrow horizontal band", visibleWords.maxOf { it.box.right } - visibleWords.minOf { it.box.left } < 0.02f)
        }
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

    private fun corpusFixture(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        return File(context.cacheDir, "corpus-$name").also { output ->
            context.assets.open(name).use { input -> output.outputStream().use(input::copyTo) }
        }
    }

    private fun assertStructuredNativePage(page: com.folium.reader.core.text.TextPage) {
        assertEquals(TextSource.NATIVE_PDF, page.source)
        assertTrue(page.blocks.isNotEmpty())
        assertTrue(page.lines.isNotEmpty())
        assertTrue(page.words.isNotEmpty())
        assertTrue(page.words.all { it.fonts.isNotEmpty() })
        assertTrue(page.words.all { it.text == Normalizer.normalize(it.text, Normalizer.Form.NFC) })
        assertEquals(1, page.blocks.size)
        assertEquals(1, page.lines.size)
    }

    private fun assertLeftToRight(boxes: List<PageSpaceRect>) {
        assertTrue("word boxes must follow authored left-to-right order", boxes.zipWithNext().all { (previous, next) ->
            next.left > previous.left && kotlin.math.abs(next.top - previous.top) < 0.01f
        })
    }
}
