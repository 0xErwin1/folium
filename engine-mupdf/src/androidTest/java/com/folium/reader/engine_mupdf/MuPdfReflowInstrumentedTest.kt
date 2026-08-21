package com.folium.reader.engine_mupdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.PdfSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val EXPECTED_REFLOW_ASPECT = 450f / 675f
private const val ASPECT_TOLERANCE = 0.001f

/**
 * Pins the canonical reflow box ([com.folium.reader.core.pdf.ReflowLayoutBox.BOX_1]) reaching the
 * engine, and that the gate it replaced still keeps a PDF's own layout untouched.
 */
@RunWith(AndroidJUnit4::class)
class MuPdfReflowInstrumentedTest {

    private fun fixture(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        return File(context.cacheDir, name).also { output ->
            context.assets.open(name).use { input -> output.outputStream().use(input::copyTo) }
        }
    }

    @Test fun reflowableLongEpubOpensWithMultiplePagesLaidOutInTheCanonicalBox() {
        MuPdfEngine().open(PdfSource(fixture("reflowable-long.epub").absolutePath)).use { document ->
            assertTrue("a multi-chapter EPUB must lay out to more than one page", document.pageCount > 1)

            val firstPageAspect = document.pageInfo(0).let { it.width / it.height }
            assertEquals(
                "page-0 aspect must match the canonical box, proving BOX_1 reached the engine",
                EXPECTED_REFLOW_ASPECT,
                firstPageAspect,
                ASPECT_TOLERANCE
            )

            for (index in 0 until document.pageCount) {
                val info = document.pageInfo(index)
                assertEquals(
                    "every page of one laid-out EPUB must share the first page's aspect",
                    firstPageAspect,
                    info.width / info.height,
                    ASPECT_TOLERANCE
                )
            }
        }
    }

    @Test fun theSameEpubLaidOutTwiceReportsTheSamePageCountAndPageZeroBounds() {
        val engine = MuPdfEngine()

        val (firstCount, firstBounds) = engine.open(PdfSource(fixture("reflowable-long.epub").absolutePath)).use { document ->
            document.pageCount to document.pageInfo(0)
        }
        val (secondCount, secondBounds) = MuPdfEngine().open(PdfSource(fixture("reflowable-long.epub").absolutePath)).use { document ->
            document.pageCount to document.pageInfo(0)
        }

        assertEquals(
            "a stored page index rests on the layout being reproducible across opens",
            firstCount,
            secondCount
        )
        assertEquals(firstBounds.width, secondBounds.width, ASPECT_TOLERANCE)
        assertEquals(firstBounds.height, secondBounds.height, ASPECT_TOLERANCE)
    }

    @Test fun nativePdfPageZeroAspectIsUnaffectedByTheReflowLayoutBox() {
        MuPdfEngine().open(PdfSource(fixture("native-english.pdf").absolutePath)).use { document ->
            val info = document.pageInfo(0)
            assertEquals(612f, info.width, 0.01f)
            assertEquals(792f, info.height, 0.01f)
        }
    }

    @Test fun unsupportedTextFileThrowsUnsupported() {
        val error = assertThrows(PdfException::class.java) {
            MuPdfEngine().open(PdfSource(fixture("unsupported.txt").absolutePath))
        }
        assertEquals(PdfFailure.Unsupported, error.failure)
    }

    @Test fun corruptEpubThrowsCorrupt() {
        val error = assertThrows(PdfException::class.java) {
            MuPdfEngine().open(PdfSource(fixture("corrupt.epub").absolutePath))
        }
        assertEquals(PdfFailure.Corrupt, error.failure)
    }

    @Test fun nativeOwnershipIsBalancedAcrossAnEpubOpenAndClose() {
        val baseline = MuPdfNativeOwnerTracker.snapshot()

        MuPdfEngine().open(PdfSource(fixture("reflowable-long.epub").absolutePath)).use { document ->
            document.pageInfo(0)
        }

        assertEquals(baseline, MuPdfNativeOwnerTracker.snapshot())
    }
}
