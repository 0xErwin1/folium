package com.folium.reader.engine_mupdf

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.RenderSpec
import org.junit.Assert.assertEquals
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

/** Name [RenderAbortWatcher] gives its own thread; used to target the throwing signal at it only. */
private const val WATCHER_THREAD_NAME = "mupdf-render-abort"

private const val OVERDRAW_OPERATIONS = 100
private const val INSIDE_FITZ_SETTLE_MILLIS = 15L
private const val MINIMUM_MEANINGFUL_RENDER_MILLIS = 110L
private const val ABORT_SHARE_OF_FULL_RENDER_NUMERATOR = 1
private const val ABORT_SHARE_OF_FULL_RENDER_DENOMINATOR = 4

/**
 * Regression coverage for the watcher-thread resilience described in `folium/perf-p2-followups`
 * (#12595): a [CancellationSignal] supplied by a caller is arbitrary code, and an [Error] thrown
 * out of it used to escape [RenderAbortWatcher]'s loop, reach Android's default uncaught-exception
 * handler, and kill the process -- while also leaving the watcher thread reference non-null, so no
 * later render could ever be interrupted again.
 */
@RunWith(AndroidJUnit4::class)
class RenderAbortWatcherRecoveryTest {
    @Test fun aSignalThatThrowsFromTheWatcherThreadDoesNotKillTheRenderOrStrandTheWatcher() {
        val spec = RenderSpec(1080, 2160)

        MuPdfEngine().open(PdfSource(overdrawnFixture().absolutePath)).use { document ->
            document.buildDisplayList(0).use { displayList ->
                val watcherCalls = AtomicInteger(0)
                val failingFromWatcherThread = CancellationSignal {
                    if (Thread.currentThread().name == WATCHER_THREAD_NAME) {
                        watcherCalls.incrementAndGet()
                        throw AssertionError("simulated failing cancellation signal")
                    }
                    false
                }

                // The render must complete despite the watcher thread repeatedly failing to poll it:
                // a process kill or a hung call would fail this line rather than any assertion below.
                displayList.render(spec, failingFromWatcherThread)

                assertTrue(
                    "the watcher thread must have actually been exercised for this test to prove anything",
                    watcherCalls.get() > 0
                )

                assertSubsequentRenderIsStillInterruptible(displayList, spec)
            }
        }
    }

    /**
     * Proves the watcher recovered: if it had been silently stranded by the failing signal above,
     * this render would run to completion instead of stopping when cancelled.
     */
    private fun assertSubsequentRenderIsStillInterruptible(displayList: DisplayList, spec: RenderSpec) {
        val workers = Executors.newSingleThreadExecutor()
        try {
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

            assertEquals(PdfFailure.Resource(true), (failure as PdfException).failure)
            assertTrue(
                "a subsequent render must still be abortable, proving the watcher thread recovered: it returned " +
                    "${abortMillis}ms after the signal turned, against ${uncancelledMillis}ms uncancelled",
                abortMillis * ABORT_SHARE_OF_FULL_RENDER_DENOMINATOR <
                    uncancelledMillis * ABORT_SHARE_OF_FULL_RENDER_NUMERATOR
            )
        } finally {
            MuPdfNativeOwnerTracker.setBeforeRenderProbe(null)
            workers.shutdownNow()
        }
    }

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
        return File(context.cacheDir, "overdrawn-watcher-recovery.pdf").also { it.writeBytes(document.toByteArray()) }
    }
}
