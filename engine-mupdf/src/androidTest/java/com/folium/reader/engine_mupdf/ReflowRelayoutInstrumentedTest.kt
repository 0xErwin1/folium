package com.folium.reader.engine_mupdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.ReflowLayoutBox
import com.folium.reader.core.pdf.ReflowSettings
import com.folium.reader.core.pdf.RenderSpec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReflowRelayoutInstrumentedTest {

    private fun fixture(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        return File(context.cacheDir, name).also { output ->
            context.assets.open(name).use { input -> output.outputStream().use(input::copyTo) }
        }
    }

    private val boxEm26 = ReflowLayoutBox(450f, 675f, 26f)

    @Test fun reflowableReflectsWhatTheEngineReports() {
        MuPdfEngine().open(PdfSource(fixture("reflowable-long.epub").absolutePath)).use { document ->
            assertTrue(document.reflowable)
        }
        MuPdfEngine().open(PdfSource(fixture("native-english.pdf").absolutePath)).use { document ->
            assertFalse(document.reflowable)
        }
    }

    @Test fun makePositionTokenIsNullForAFixedLayoutDocumentAndNonNullForAReflowableOne() {
        MuPdfEngine().open(PdfSource(fixture("native-english.pdf").absolutePath)).use { document ->
            assertNull(document.makePositionToken(0))
        }
        MuPdfEngine().open(PdfSource(fixture("reflowable-long.epub").absolutePath)).use { document ->
            assertNotNull(document.makePositionToken(0))
        }
    }

    /**
     * The assertion the whole feature rests on: a token minted at a mid-chapter page under one em
     * resolves, after a relayout to a different em, to the page carrying the same opening text.
     */
    @Test fun tokenMintedMidChapterResolvesToTheSameTextAfterRelayout() {
        MuPdfEngine().open(PdfSource(fixture("reflowable-long.epub").absolutePath)).use { document ->
            val originalPage = 1
            val originalOpening = document.extractText(originalPage).text.normalizedWhitespace().take(24)

            val token = document.makePositionToken(originalPage)
            assertNotNull(token)

            val relayouted = document.relayout(ReflowSettings(boxEm26, ""))
            assertTrue(relayouted)

            val resolvedPage = document.resolvePositionToken(token!!)
            assertNotNull(resolvedPage)

            val resolvedText = document.extractText(resolvedPage!!).text.normalizedWhitespace()
            assertTrue(
                "expected page $resolvedPage to contain '$originalOpening', was: $resolvedText",
                resolvedText.contains(originalOpening)
            )
        }
    }

    private fun String.normalizedWhitespace(): String = trim().replace(Regex("\\s+"), " ")

    @Test fun tokenMintedFromADifferentDocumentResolvesToNull() {
        val foreignToken = MuPdfEngine().open(PdfSource(fixture("reflowable.epub").absolutePath)).use { document ->
            document.makePositionToken(0)
        }
        assertNotNull(foreignToken)

        MuPdfEngine().open(PdfSource(fixture("reflowable-long.epub").absolutePath)).use { document ->
            assertNull(document.resolvePositionToken(foreignToken!!))
        }
    }

    @Test fun relayoutIsANoOpForAFixedLayoutDocument() {
        MuPdfEngine().open(PdfSource(fixture("native-english.pdf").absolutePath)).use { document ->
            val pageCountBefore = document.pageCount
            val outlineBefore = document.outline()

            val relayouted = document.relayout(ReflowSettings(boxEm26, ""))

            assertFalse(relayouted)
            assertEquals(pageCountBefore, document.pageCount)
            assertEquals(outlineBefore, document.outline())
        }
    }

    @Test fun relayoutChangesPageCountForAReflowableDocument() {
        MuPdfEngine().open(PdfSource(fixture("reflowable-long.epub").absolutePath)).use { document ->
            val pageCountAtEm18 = document.pageCount

            val relayouted = document.relayout(ReflowSettings(boxEm26, ""))

            assertTrue(relayouted)
            assertNotEqualsInt(pageCountAtEm18, document.pageCount)
        }
    }

    private fun assertNotEqualsInt(expectedDifferentFrom: Int, actual: Int) {
        assertTrue("expected $actual to differ from $expectedDifferentFrom", expectedDifferentFrom != actual)
    }

    @Test fun displayListBuiltBeforeRelayoutThrowsClosedAfterwards() {
        MuPdfEngine().open(PdfSource(fixture("reflowable-long.epub").absolutePath)).use { document ->
            val displayList = document.buildDisplayList(0)

            val relayouted = document.relayout(ReflowSettings(boxEm26, ""))
            assertTrue(relayouted)

            val failure = assertThrows(PdfException::class.java) {
                displayList.render(RenderSpec(10, 10))
            }
            assertEquals(PdfFailure.Closed, failure.failure)
        }
    }

    @Test fun nativeOwnershipIsBalancedAcrossDisplayListsAndARelayout() {
        val baseline = MuPdfNativeOwnerTracker.snapshot()

        MuPdfEngine().open(PdfSource(fixture("reflowable-long.epub").absolutePath)).use { document ->
            val displayList = document.buildDisplayList(0)
            displayList.render(RenderSpec(10, 10))

            document.relayout(ReflowSettings(boxEm26, ""))

            val secondDisplayList = document.buildDisplayList(0)
            secondDisplayList.render(RenderSpec(10, 10))
            secondDisplayList.close()
        }

        assertEquals(baseline, MuPdfNativeOwnerTracker.snapshot())
    }
}
