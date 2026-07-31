package com.folium.reader.engine_mupdf

import android.os.Debug
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NativeCleanupStressTest {
    @Test fun repeatedOpenRenderCancelCloseReturnsEveryOwnerToBaseline() {
        val baseline = MuPdfNativeOwnerTracker.snapshot()
        val rssSamplesKb = mutableListOf(rssKb())
        val nativeHeapSamples = mutableListOf(Debug.getNativeHeapAllocatedSize())
        val latenciesMicros = mutableListOf<Long>()
        repeat(24) {
            val calls = AtomicInteger()
            var cancellationRequestedAt = 0L
            val cancellation = CancellationSignal {
                if (calls.incrementAndGet() >= 4 && cancellationRequestedAt == 0L) {
                    cancellationRequestedAt = SystemClock.elapsedRealtimeNanos()
                }
                cancellationRequestedAt != 0L
            }
            MuPdfEngine().open(PdfSource(fixture("scan-english.pdf").absolutePath)).use { document ->
                document.buildDisplayList(0).use { displayList ->
                    val failure = assertThrows(PdfException::class.java) {
                        displayList.render(RenderSpec(1200, 1600), cancellation)
                    }
                    assertEquals(PdfFailure.Resource(true), failure.failure)
                    assertTrue(cancellationRequestedAt != 0L)
                    latenciesMicros += (SystemClock.elapsedRealtimeNanos() - cancellationRequestedAt) / 1_000
                }
            }
            assertOwnersAtBaseline(baseline)
            rssSamplesKb += rssKb()
            nativeHeapSamples += Debug.getNativeHeapAllocatedSize()
        }
        rssSamplesKb += rssKb()
        nativeHeapSamples += Debug.getNativeHeapAllocatedSize()
        val peakRssKb = rssSamplesKb.max()
        val peakNativeHeap = nativeHeapSamples.max()
        assertTrue(peakRssKb >= rssSamplesKb.first() && peakRssKb >= rssSamplesKb.last())
        assertTrue(peakNativeHeap >= nativeHeapSamples.first() && peakNativeHeap >= nativeHeapSamples.last())
        assertEquals(24, latenciesMicros.size)
        val sorted = latenciesMicros.sorted()
        Log.i(
            "Rco004Metrics",
            "iterations=24 rssKb=${rssSamplesKb.first()}/$peakRssKb/${rssSamplesKb.last()} rssKbSamples=${rssSamplesKb.joinToString(",")} nativeHeapBytes=${nativeHeapSamples.first()}/$peakNativeHeap/${nativeHeapSamples.last()} nativeHeapSamples=${nativeHeapSamples.joinToString(",")} cancellationMicros=count:${sorted.size},min:${sorted.first()},median:${sorted[sorted.size / 2]},p95:${sorted[((sorted.size - 1) * 95) / 100]},max:${sorted.last()}"
        )
    }

    @Test fun closeRaceWaitsForNativeRenderAndReturnsAllOwnersToBaseline() {
        val baseline = MuPdfNativeOwnerTracker.snapshot()
        val enteredRender = CountDownLatch(1)
        val releaseRender = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val engine = MuPdfEngine()
        val document = engine.open(PdfSource(fixture("native-english.pdf").absolutePath))
        val displayList = document.buildDisplayList(0)
        MuPdfNativeOwnerTracker.setBeforeRenderProbe {
            enteredRender.countDown()
            releaseRender.await(1, TimeUnit.SECONDS)
        }
        try {
            val render = workers.submit { displayList.render(RenderSpec(240, 320)) }
            assertTrue(enteredRender.await(1, TimeUnit.SECONDS))
            val close = workers.submit { document.close() }
            assertTrue(!close.isDone)
            releaseRender.countDown()
            render.get(2, TimeUnit.SECONDS)
            close.get(2, TimeUnit.SECONDS)
            val failure = assertThrows(PdfException::class.java) { displayList.render(RenderSpec(20, 20)) }
            assertEquals(PdfFailure.Closed, failure.failure)
        } finally {
            MuPdfNativeOwnerTracker.setBeforeRenderProbe(null)
            releaseRender.countDown()
            workers.shutdownNow()
            document.close()
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

    private fun rssKb(): Long = File("/proc/self/status").useLines { lines ->
        lines.first { it.startsWith("VmRSS:") }.trim().split(Regex("\\s+")).getOrNull(1)?.toLong() ?: 0L
    }

    private fun fixture(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().context
        return File(context.cacheDir, name).also { output ->
            context.assets.open("pdf/$name").use { input -> output.outputStream().use(input::copyTo) }
        }
    }
}
