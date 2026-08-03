package com.folium.reader.engine_mupdf

import android.os.SystemClock
import android.util.Log
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Below this, a render that simply finished is indistinguishable from one that was aborted. */
private const val MINIMUM_MEANINGFUL_RENDER_MILLIS = 110L

/** Long enough that the signal can only turn while the render is genuinely inside fitz. */
private const val INSIDE_FITZ_SETTLE_MILLIS = 15L

/** Tuned so the synthetic fixture below takes a few hundred milliseconds to rasterize. */
private const val OVERDRAW_OPERATIONS = 100

private const val ABORT_SHARE_OF_FULL_RENDER_NUMERATOR = 1
private const val ABORT_SHARE_OF_FULL_RENDER_DENOMINATOR = 4

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

    /**
     * Cancellation used to be honoured only between native calls, so a superseded render still
     * rasterized to completion — holding the document lock the visible page needs — and only then
     * reported itself cancelled. This asserts the render actually stops: the same signal is flipped
     * once the render is demonstrably inside fitz, and the call must come back in well under the
     * time the identical render takes when nothing cancels it.
     *
     * The baseline is measured on the same device in the same test, both to calibrate the comparison
     * and to prove the fixture is expensive enough for a fast return to mean anything at all — a
     * render that finished on its own before the signal was ever flipped would prove nothing
     * (`folium/probe-timeout-scaling`). [ABORT_SHARE_OF_FULL_RENDER_NUMERATOR] over
     * [ABORT_SHARE_OF_FULL_RENDER_DENOMINATOR] sits far from both measured populations on
     * `emulator-5554`: an aborted render returns in about 0.02 of the full render, and one whose
     * cookie is not passed to fitz in about 0.95 of it.
     */
    @Test fun anInFlightRenderStopsWhenTheSignalItAlreadyPollsTurnsCancelled() {
        val baseline = MuPdfNativeOwnerTracker.snapshot()
        val spec = RenderSpec(1080, 2160)
        val workers = Executors.newSingleThreadExecutor()

        try {
            MuPdfEngine().open(PdfSource(overdrawnFixture().absolutePath)).use { document ->
                document.buildDisplayList(0).use { displayList ->
                    displayList.render(spec, CancellationSignal { false })

                    val uncancelledMillis = (1..3).map {
                        val startedAt = SystemClock.elapsedRealtimeNanos()
                        displayList.render(spec, CancellationSignal { false })
                        (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000
                    }.sorted()[1]

                    assertTrue(
                        "this fixture renders in ${uncancelledMillis}ms, too cheap for an abort to be distinguishable " +
                            "from simply finishing -- render something larger",
                        uncancelledMillis >= MINIMUM_MEANINGFUL_RENDER_MILLIS
                    )

                    val cancelled = AtomicBoolean(false)
                    val enteredRender = CountDownLatch(1)
                    MuPdfNativeOwnerTracker.setBeforeRenderProbe { enteredRender.countDown() }

                    val render = workers.submit<Throwable> {
                        try {
                            displayList.render(spec, CancellationSignal { cancelled.get() })
                            null
                        } catch (failure: Throwable) {
                            failure
                        }
                    }

                    assertTrue(enteredRender.await(30, TimeUnit.SECONDS))
                    Thread.sleep(INSIDE_FITZ_SETTLE_MILLIS)

                    val cancelledAt = SystemClock.elapsedRealtimeNanos()
                    cancelled.set(true)
                    val failure = render.get(60, TimeUnit.SECONDS)
                    val abortMillis = (SystemClock.elapsedRealtimeNanos() - cancelledAt) / 1_000_000

                    Log.i("RenderAbortMetrics", "uncancelledMillis=$uncancelledMillis abortMillis=$abortMillis")

                    assertEquals(PdfFailure.Resource(true), (failure as PdfException).failure)
                    assertTrue(
                        "an aborted render must stop, not run to completion: it returned ${abortMillis}ms after the " +
                            "signal turned, against ${uncancelledMillis}ms for the same render uncancelled",
                        abortMillis * ABORT_SHARE_OF_FULL_RENDER_DENOMINATOR <
                            uncancelledMillis * ABORT_SHARE_OF_FULL_RENDER_NUMERATOR
                    )
                }
            }
        } finally {
            MuPdfNativeOwnerTracker.setBeforeRenderProbe(null)
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

    /**
     * A page built out of [OVERDRAW_OPERATIONS] full-page fills: expensive to rasterize, and
     * expensive in a way that can be interrupted, because fitz looks at the cookie between display
     * list nodes. The checked-in fixtures cannot show this: a scanned page is one enormous image
     * node, so aborting inside it is indistinguishable from finishing slightly early, and the
     * synthetic text page is too cheap to abort at all.
     *
     * Written per test run rather than checked in as a binary, since it is entirely mechanical and
     * its only interesting property is how many operations it contains.
     */
    private fun overdrawnFixture(): File {
        val content = buildString {
            repeat(OVERDRAW_OPERATIONS) { index ->
                val grey = (index % 90) / 100f
                append("$grey $grey $grey rg 0 0 612 792 re f\n")
            }
        }.toByteArray()

        val objects = listOf(
            "<</Type/Catalog/Pages 2 0 R>>".toByteArray(),
            "<</Type/Pages/Kids[3 0 R]/Count 1>>".toByteArray(),
            "<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents 4 0 R/Resources<<>>>>".toByteArray(),
            "<</Length ${content.size}>>stream\n".toByteArray() + content + "\nendstream".toByteArray()
        )

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
        return File(context.cacheDir, "overdrawn.pdf").also { it.writeBytes(document.toByteArray()) }
    }

    private fun fixture(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        return File(context.cacheDir, name).also { output ->
            context.assets.open("pdf/$name").use { input -> output.outputStream().use(input::copyTo) }
        }
    }
}
