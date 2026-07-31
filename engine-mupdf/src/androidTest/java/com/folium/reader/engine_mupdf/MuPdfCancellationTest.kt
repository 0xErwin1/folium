package com.folium.reader.engine_mupdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.RenderSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MuPdfCancellationTest {
    @Test fun cancelledRenderDoesNotPublishAndReleasesEveryNativeOwner() {
        val baseline = MuPdfNativeOwnerTracker.snapshot()
        val calls = AtomicInteger()
        val cancellation = CancellationSignal { calls.incrementAndGet() >= 4 }

        MuPdfEngine().open(PdfSource(fixture("scan-english.pdf").absolutePath)).use { document ->
            document.buildDisplayList(0).use { displayList ->
                val failure = assertThrows(PdfException::class.java) {
                    displayList.render(RenderSpec(1200, 1600), cancellation)
                }
                assertEquals(PdfFailure.Resource(true), failure.failure)
            }
        }

        assertTrue(calls.get() >= 4)
        assertOwnersAtBaseline(baseline)
    }

    @Test fun cancellationBeforeNativeWorkDoesNotAllocatePixmap() {
        val baseline = MuPdfNativeOwnerTracker.snapshot()
        MuPdfEngine().open(PdfSource(fixture("native-english.pdf").absolutePath)).use { document ->
            document.buildDisplayList(0).use { displayList ->
                val failure = assertThrows(PdfException::class.java) {
                    displayList.render(RenderSpec(120, 160), CancellationSignal { true })
                }
                assertEquals(PdfFailure.Resource(true), failure.failure)
            }
        }
        assertOwnersAtBaseline(baseline)
    }

    @Test fun pageInfoAndBuildDisplayListTrackDocumentAndPageOwnersOnSuccessAndFailure() {
        val baseline = MuPdfNativeOwnerTracker.snapshot()
        MuPdfEngine().open(PdfSource(fixture("native-english.pdf").absolutePath)).use { document ->
            assertEquals(baseline.documents + 1, MuPdfNativeOwnerTracker.snapshot().documents)
            document.pageInfo(0)
            document.buildDisplayList(0).close()
            assertThrows(RuntimeException::class.java) { document.pageInfo(Int.MAX_VALUE) }
            assertThrows(RuntimeException::class.java) { document.buildDisplayList(Int.MAX_VALUE) }
        }
        assertOwnersAtBaseline(baseline)
    }

    private fun assertOwnersAtBaseline(baseline: MuPdfNativeOwnerTracker.Snapshot) {
        val actual = MuPdfNativeOwnerTracker.snapshot()
        assertEquals("documents", baseline.documents, actual.documents)
        assertEquals("pages", baseline.pages, actual.pages)
        assertEquals("displayLists", baseline.displayLists, actual.displayLists)
        assertEquals("pixmaps", baseline.pixmaps, actual.pixmaps)
        assertEquals("structuredTexts", baseline.structuredTexts, actual.structuredTexts)
    }

    private fun fixture(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        return File(context.cacheDir, name).also { output ->
            context.assets.open("pdf/$name").use { input -> output.outputStream().use(input::copyTo) }
        }
    }
}
