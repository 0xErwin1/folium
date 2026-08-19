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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.Normalizer

@RunWith(AndroidJUnit4::class)
class TesseractFixtureTest {
    @Test fun scannedFixturesMeetManifestTokenAndGeometryAcceptance() {
        scannedFixtures().map { fixture -> measure(fixture).also { Log.i("Rco005Metrics", it.logLine()) } }
            .forEach { result ->
                assertTrue("${result.corpusId} recall=${result.recall}", result.recall >= 0.9)
                assertTrue("${result.corpusId} has invalid geometry", result.validGeometry)
                assertTrue("${result.corpusId} is not NFC", result.nfc)
            }
    }

    @Test fun cancellationClosedAndDataFailuresAreTyped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = pageImage(scannedFixtures().first().assetName)
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

    private fun scannedFixtures(): List<Fixture> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val entries = JSONObject(assets.open("ocr-manifest.json").bufferedReader().use { it.readText() }).getJSONArray("fixtures")
        return (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { it.getJSONArray("pageTraits").toString().contains("raster-image-only") && it.getJSONObject("expectedGeometry").has("corpusId") }
            .map { entry ->
                val geometry = entry.getJSONObject("expectedGeometry")
                Fixture(
                    geometry.getString("corpusId"),
                    entry.getString("file"),
                    (0 until entry.getJSONArray("expectedTokens").length()).map { normalize(entry.getJSONArray("expectedTokens").getString(it)) },
                    (0 until geometry.getJSONArray("expectedRegions").length()).map { index ->
                        geometry.getJSONArray("expectedRegions").getJSONArray(index).let { Region(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat()) }
                    }
                )
            }
    }

    private fun measure(fixture: Fixture): Measurement {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = pageImage(fixture.assetName)
        val timings = mutableListOf<Long>()
        val rss = mutableListOf<Long>()
        val nativeHeap = mutableListOf<Long>()
        var recognized = emptyList<String>()
        var nfc = true
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
                nfc = nfc && output.words.all { word -> word.text == Normalizer.normalize(word.text, Normalizer.Form.NFC) }
                boxesValid = boxesValid && output.words.isNotEmpty() && output.words.all { word ->
                    word.box.left >= 0f && word.box.top >= 0f && word.box.right <= 1f && word.box.bottom <= 1f
                } && fixture.expectedTokens.indices.all { index ->
                    output.words.any { word -> normalize(word.text) == fixture.expectedTokens[index] && fixture.regions[index].overlaps(word.box.left, word.box.top, word.box.right, word.box.bottom) }
                }
            }
        }
        rss += rssKb()
        nativeHeap += Debug.getNativeHeapAllocatedSize()
        val found = fixture.expectedTokens.count { token -> recognized.any { it == token } }
        return Measurement(fixture.corpusId, fixture.expectedTokens.size, found, found.toDouble() / fixture.expectedTokens.size, boxesValid, nfc, timings, rss, nativeHeap)
    }

    private fun pageImage(assetName: String): PageImage {
        MuPdfEngine().open(PdfSource(fixture(assetName).absolutePath)).use { document ->
            document.buildDisplayList(0).use { displayList ->
                val raster = displayList.render(RenderSpec(900, 1200))
                return PageImage(raster.width, raster.height, PixelFormat.RGBA_8888, raster.rgba).also(::assertOcrSuitableRaster)
            }
        }
    }

    private fun fixture(assetName: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return File(instrumentation.targetContext.filesDir, assetName).also { output ->
            output.parentFile?.mkdirs()
            instrumentation.context.assets.open("pdf/$assetName").use { input -> output.outputStream().use(input::copyTo) }
        }
    }

    private fun assertOcrSuitableRaster(image: PageImage) {
        assertEquals(PixelFormat.RGBA_8888, image.pixelFormat)
        assertEquals(0.75f, image.width.toFloat() / image.height, 0.01f)
        assertTrue("long edge was ${image.height}", image.height >= 1_600)
        val pixels = image.pixels()
        assertEquals(image.width * image.height * 4, pixels.size)
        assertTrue(pixels.indices.step(4).any { (pixels[it].toInt() and 0xff) < 128 })
        pixels.indices.step(4).forEach { offset ->
            assertEquals(pixels[offset], pixels[offset + 1])
            assertEquals(pixels[offset + 1], pixels[offset + 2])
            assertEquals(255.toByte(), pixels[offset + 3])
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun rssKb(): Long = File("/proc/self/status").useLines { lines -> lines.first { it.startsWith("VmRSS:") }.trim().split(Regex("\\s+")).getOrNull(1)?.toLong() ?: 0L }

    private data class Fixture(val corpusId: String, val assetName: String, val expectedTokens: List<String>, val regions: List<Region>)
    private data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun overlaps(wordLeft: Float, wordTop: Float, wordRight: Float, wordBottom: Float): Boolean = wordLeft < right && wordRight > left && wordTop < bottom && wordBottom > top
    }
    private data class Measurement(val corpusId: String, val expected: Int, val found: Int, val recall: Double, val validGeometry: Boolean, val nfc: Boolean, val timings: List<Long>, val rss: List<Long>, val nativeHeap: List<Long>) {
        fun logLine(): String {
            val sorted = timings.sorted()
            val p95 = sorted[(kotlin.math.ceil(sorted.size * 0.95).toInt().coerceIn(1, sorted.size) - 1)]
            return "corpusId=$corpusId expected=$expected found=$found recall=$recall thresholdAtLeast90Percent=${recall >= 0.9} geometry=manifest-region-overlap:$validGeometry nfc:$nfc iterations=${timings.size} timingMicros=min:${sorted.first()},median:${sorted[sorted.size / 2]},p95:$p95,max:${sorted.last()} rssKb=${rss.first()}/${rss.max()}/${rss.last()} nativeHeapBytes=${nativeHeap.first()}/${nativeHeap.max()}/${nativeHeap.last()}"
        }
    }
}
