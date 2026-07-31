package com.folium.reader

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.ocr.OcrException
import com.folium.reader.core.ocr.OcrFailure
import com.folium.reader.core.ocr.PageImage
import com.folium.reader.core.ocr.PixelFormat
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.engine_mupdf.MuPdfEngine
import com.folium.reader.ocr_tesseract.TesseractOcrEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.Normalizer

@RunWith(AndroidJUnit4::class)
class TesseractFixtureTest {
    @Test fun scannedSpanishAndEnglishFixturesMeetQualityAndGeometryAcceptance() {
        listOf(
            Fixture("scan-spanish.pdf", listOf("biblioteca", "lectura")),
            Fixture("scan-english.pdf", listOf("english", "reader"))
        ).map { fixture -> measure(fixture).also { Log.i("Rco005Metrics", it.logLine()) } }
            .forEach { result ->
                assertTrue("${result.fixture} recall=${result.recall}", result.recall >= 0.9)
                assertTrue("${result.fixture} has invalid geometry", result.validGeometry)
            }
    }

    @Test fun cancellationClosedAndDataFailuresAreTyped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = pageImage("scan-english.pdf")
        TesseractOcrEngine(context).use { engine ->
            assertEquals(OcrFailure.Cancelled, org.junit.Assert.assertThrows(OcrException::class.java) {
                engine.recognize(image, cancellationSignal = CancellationSignal { true })
            }.failure)
        }
        val closed = TesseractOcrEngine(context)
        closed.close()
        assertEquals(OcrFailure.Closed, org.junit.Assert.assertThrows(OcrException::class.java) {
            closed.recognize(image)
        }.failure)
        val blockedRoot = File(context.cacheDir, "rco005-data-root").also { it.writeText("not-a-directory") }
        TesseractOcrEngine(context, blockedRoot).use { engine ->
            assertEquals(OcrFailure.LanguageData, org.junit.Assert.assertThrows(OcrException::class.java) {
                engine.recognize(image)
            }.failure)
        }
    }

    private fun measure(fixture: Fixture): Measurement {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = pageImage(fixture.name)
        val expected = fixture.expected.map(::normalize)
        val timings = mutableListOf<Long>()
        val rss = mutableListOf<Long>()
        val nativeHeap = mutableListOf<Long>()
        var recognized = emptyList<String>()
        var boxesValid = true
        TesseractOcrEngine(context).use { engine ->
            repeat(5) {
                rss += rssKb()
                nativeHeap += Debug.getNativeHeapAllocatedSize()
                val started = SystemClock.elapsedRealtimeNanos()
                val output = engine.recognize(image)
                timings += (SystemClock.elapsedRealtimeNanos() - started) / 1_000
                rss += rssKb()
                nativeHeap += Debug.getNativeHeapAllocatedSize()
                recognized = output.words.map { normalize(it.text) }
                boxesValid = boxesValid && output.words.isNotEmpty() && output.words.all { word ->
                    word.box.left >= 0f && word.box.top >= 0f && word.box.right <= 1f && word.box.bottom <= 1f &&
                        word.box.left < 0.5f && word.box.top in 0.05f..0.35f
                }
            }
        }
        rss += rssKb()
        nativeHeap += Debug.getNativeHeapAllocatedSize()
        val found = expected.count { token -> recognized.any { it == token } }
        val expectedText = expected.joinToString(" ")
        val recognizedText = recognized.joinToString(" ")
        return Measurement(
            fixture.name,
            expected.size,
            found,
            found.toDouble() / expected.size,
            characterErrorRate(expectedText, recognizedText),
            wordErrorRate(expected, recognized),
            boxesValid,
            timings,
            rss,
            nativeHeap
        )
    }

    private fun pageImage(name: String): PageImage {
        MuPdfEngine().open(PdfSource(fixture(name).absolutePath)).use { document ->
            document.buildDisplayList(0).use { displayList ->
                val raster = displayList.render(RenderSpec(900, 1200))
                return PageImage(raster.width, raster.height, PixelFormat.RGBA_8888, raster.rgba)
            }
        }
    }

    private fun fixture(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assetContext = instrumentation.context
        return File(instrumentation.targetContext.filesDir, name).also { output ->
            output.parentFile?.mkdirs()
            assetContext.assets.open("pdf/$name").use { input -> output.outputStream().use(input::copyTo) }
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase().filter(Char::isLetterOrDigit)

    private fun characterErrorRate(expected: String, actual: String): Double = distance(expected.toList(), actual.toList()).toDouble() / expected.length.coerceAtLeast(1)
    private fun wordErrorRate(expected: List<String>, actual: List<String>): Double = distance(expected, actual).toDouble() / expected.size.coerceAtLeast(1)

    private fun <T> distance(expected: List<T>, actual: List<T>): Int {
        var previous = IntArray(actual.size + 1) { it }
        expected.forEachIndexed { row, item ->
            val current = IntArray(actual.size + 1)
            current[0] = row + 1
            actual.forEachIndexed { column, candidate ->
                current[column + 1] = minOf(previous[column + 1] + 1, current[column] + 1, previous[column] + if (item == candidate) 0 else 1)
            }
            previous = current
        }
        return previous.last()
    }

    private fun rssKb(): Long = File("/proc/self/status").useLines { lines ->
        lines.first { it.startsWith("VmRSS:") }.trim().split(Regex("\\s+")).getOrNull(1)?.toLong() ?: 0L
    }

    private data class Fixture(val name: String, val expected: List<String>)
    private data class Measurement(
        val fixture: String,
        val expected: Int,
        val found: Int,
        val recall: Double,
        val cer: Double,
        val wer: Double,
        val validGeometry: Boolean,
        val timings: List<Long>,
        val rss: List<Long>,
        val nativeHeap: List<Long>
    ) {
        fun logLine(): String {
            val sorted = timings.sorted()
            return "fixture=$fixture expected=$expected found=$found recall=$recall cer=$cer wer=$wer geometry=normalized-unrotated-valid:$validGeometry iterations=${timings.size} timingMicros=min:${sorted.first()},median:${sorted[sorted.size / 2]},p95:${sorted[((sorted.size - 1) * 95) / 100]},max:${sorted.last()} rssKb=${rss.first()}/${rss.max()}/${rss.last()} nativeHeapBytes=${nativeHeap.first()}/${nativeHeap.max()}/${nativeHeap.last()}"
        }
    }
}
