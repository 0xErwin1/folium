package com.folium.reader.engine_mupdf

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.RenderSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Below this, a build that simply finished is indistinguishable from one that was aborted. */
private const val MINIMUM_MEANINGFUL_BUILD_MILLIS = 110L

/** Long enough that the signal can only turn while the build is genuinely inside fitz. */
private const val INSIDE_FITZ_SETTLE_MILLIS = 15L

/** Tuned so recording the fixture's page into a display list takes a few hundred milliseconds. */
private const val OVERDRAW_OPERATIONS = 20_000

private const val ABORT_SHARE_OF_FULL_BUILD_NUMERATOR = 1
private const val ABORT_SHARE_OF_FULL_BUILD_DENOMINATOR = 4

@RunWith(AndroidJUnit4::class)
class MuPdfRetainedDisplayListTest {
    @Test fun sameRenderedPageBuildsOnceAcrossTwoSizesAndMatchesIndependentBuilds() {
        val baseline = MuPdfNativeOwnerTracker.snapshot()
        val firstSpec = RenderSpec(80, 113)
        val secondSpec = RenderSpec(160, 226)

        MuPdfEngine().open(PdfSource(fixture("native-english.pdf").absolutePath)).use { document ->
            val firstRaster = document.renderPage(0, firstSpec)
            val afterFirstBuild = MuPdfNativeOwnerTracker.snapshot().displayLists
            assertEquals(baseline.displayLists + 1, afterFirstBuild)

            val secondRaster = document.renderPage(0, secondSpec)
            assertEquals(
                "rendering the same page at a second size must reuse the retained list, not build again",
                afterFirstBuild,
                MuPdfNativeOwnerTracker.snapshot().displayLists
            )

            val independentFirst = document.buildDisplayList(0).use { it.render(firstSpec) }
            val independentSecond = document.buildDisplayList(0).use { it.render(secondSpec) }

            assertArrayEquals(independentFirst.rgba, firstRaster.rgba)
            assertArrayEquals(independentSecond.rgba, secondRaster.rgba)
        }

        assertOwnersAtBaseline(baseline)
    }

    @Test fun retainedDisplayListCountNeverExceedsCapWhileCyclingThroughMorePagesThanTheCap() {
        val baseline = MuPdfNativeOwnerTracker.snapshot()
        val pageCount = RETAINED_DISPLAY_LIST_CAPACITY * 2
        val spec = RenderSpec(80, 113)

        MuPdfEngine().open(PdfSource(multiPageFixture(pageCount).absolutePath)).use { document ->
            var maxRetained = 0
            for (index in 0 until pageCount) {
                document.renderPage(index, spec)
                maxRetained = maxOf(maxRetained, MuPdfNativeOwnerTracker.snapshot().displayLists - baseline.displayLists)
            }

            assertTrue(
                "retained display lists exceeded the cap of $RETAINED_DISPLAY_LIST_CAPACITY: saw $maxRetained",
                maxRetained <= RETAINED_DISPLAY_LIST_CAPACITY
            )
            assertEquals(
                RETAINED_DISPLAY_LIST_CAPACITY,
                MuPdfNativeOwnerTracker.snapshot().displayLists - baseline.displayLists
            )
        }

        assertOwnersAtBaseline(baseline)
    }

    @Test fun closingTheDocumentBringsEveryNativeCounterBackToBaseline() {
        val baseline = MuPdfNativeOwnerTracker.snapshot()
        val pageCount = RETAINED_DISPLAY_LIST_CAPACITY * 2
        val spec = RenderSpec(80, 113)

        val document = MuPdfEngine().open(PdfSource(multiPageFixture(pageCount).absolutePath))
        for (index in 0 until pageCount) document.renderPage(index, spec)
        assertEquals(RETAINED_DISPLAY_LIST_CAPACITY, MuPdfNativeOwnerTracker.snapshot().displayLists - baseline.displayLists)

        document.close()

        assertOwnersAtBaseline(baseline)
    }

    @Test fun aCancelledBuildRaisesRetryableResourceFailureAndLeavesCountersBalanced() {
        val baseline = MuPdfNativeOwnerTracker.snapshot()
        val spec = RenderSpec(60, 84)
        val workers = Executors.newSingleThreadExecutor()

        try {
            MuPdfEngine().open(PdfSource(overdrawnMultiPageFixture(pageCount = 6).absolutePath)).use { document ->
                val uncancelledMillis = (0..2).map { index ->
                    val startedAt = SystemClock.elapsedRealtimeNanos()
                    document.renderPage(index, spec)
                    (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000
                }.sorted()[1]

                assertTrue(
                    "this fixture builds in ${uncancelledMillis}ms, too cheap for an abort to be distinguishable " +
                        "from simply finishing -- record more operations per page",
                    uncancelledMillis >= MINIMUM_MEANINGFUL_BUILD_MILLIS
                )

                val cancelled = AtomicBoolean(false)
                val enteredBuild = CountDownLatch(1)
                MuPdfNativeOwnerTracker.setBeforeDisplayListBuildProbe { enteredBuild.countDown() }

                val render = workers.submit<Throwable> {
                    try {
                        document.renderPage(3, spec, CancellationSignal { cancelled.get() })
                        null
                    } catch (failure: Throwable) {
                        failure
                    }
                }

                assertTrue(enteredBuild.await(30, TimeUnit.SECONDS))
                Thread.sleep(INSIDE_FITZ_SETTLE_MILLIS)

                val cancelledAt = SystemClock.elapsedRealtimeNanos()
                cancelled.set(true)
                val failure = render.get(60, TimeUnit.SECONDS)
                val abortMillis = (SystemClock.elapsedRealtimeNanos() - cancelledAt) / 1_000_000

                assertEquals(PdfFailure.Resource(true), (failure as PdfException).failure)
                assertTrue(
                    "an aborted build must stop, not run to completion: it returned ${abortMillis}ms after the " +
                        "signal turned, against ${uncancelledMillis}ms for the same build uncancelled",
                    abortMillis * ABORT_SHARE_OF_FULL_BUILD_DENOMINATOR <
                        uncancelledMillis * ABORT_SHARE_OF_FULL_BUILD_NUMERATOR
                )
            }
        } finally {
            MuPdfNativeOwnerTracker.setBeforeDisplayListBuildProbe(null)
            workers.shutdownNow()
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
        assertEquals("cookies", baseline.cookies, actual.cookies)
    }

    private fun fixture(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        return File(context.cacheDir, name).also { output ->
            context.assets.open("pdf/$name").use { input -> output.outputStream().use(input::copyTo) }
        }
    }

    private fun multiPageFixture(pageCount: Int, overdrawOperationsPerPage: Int = 1): File {
        val firstPageObjectNumber = 3
        val firstContentObjectNumber = firstPageObjectNumber + pageCount
        val kids = (0 until pageCount).joinToString(" ") { "${firstPageObjectNumber + it} 0 R" }

        val objects = mutableListOf<ByteArray>()
        objects += "<</Type/Catalog/Pages 2 0 R>>".toByteArray()
        objects += "<</Type/Pages/Kids[$kids]/Count $pageCount>>".toByteArray()
        repeat(pageCount) { index ->
            val contentObjectNumber = firstContentObjectNumber + index
            objects += "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 280]/Contents $contentObjectNumber 0 R/Resources<<>>>>"
                .toByteArray()
        }
        repeat(pageCount) { index ->
            val content = buildString {
                repeat(overdrawOperationsPerPage) { operation ->
                    val grey = ((index + operation) % 90) / 100f
                    append("$grey $grey $grey rg 0 0 200 280 re f\n")
                }
            }.toByteArray()
            objects += "<</Length ${content.size}>>stream\n".toByteArray() + content + "\nendstream".toByteArray()
        }

        val document = ByteArrayOutputStream()
        document.write("%PDF-1.4\n".toByteArray())

        val offsets = objects.mapIndexed { index, body ->
            val offset = document.size()
            document.write("${index + 1} 0 obj".toByteArray())
            document.write(body)
            document.write("endobj\n".toByteArray())
            offset
        }

        val xrefOffset = document.size()
        document.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray())
        offsets.forEach { document.write("%010d 00000 n \n".format(it).toByteArray()) }
        document.write("trailer<</Size ${objects.size + 1}/Root 1 0 R>>\nstartxref\n$xrefOffset\n%%EOF\n".toByteArray())

        val context = InstrumentationRegistry.getInstrumentation().context
        return File(context.cacheDir, "multi-page-$pageCount-$overdrawOperationsPerPage.pdf").also {
            it.writeBytes(document.toByteArray())
        }
    }

    private fun overdrawnMultiPageFixture(pageCount: Int): File = multiPageFixture(pageCount, OVERDRAW_OPERATIONS)
}
