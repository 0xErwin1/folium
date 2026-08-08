package com.folium.reader.ocr_tesseract

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.ocr.OcrException
import com.folium.reader.core.ocr.OcrFailure
import com.folium.reader.core.ocr.OcrLanguage
import com.folium.reader.core.ocr.OcrRequest
import com.folium.reader.core.ocr.PageImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TesseractRuntimeFailureTest {
    @Test fun wrongThreadIsRejectedBeforeNativeCreationAndClosedAndCancelledRemainTyped() {
        var creations = 0
        val engine = engine(NativeTesseractFactory { creations++ ; FakeApi() })
        val wrongThread = AtomicReference<Throwable>()
        Thread { wrongThread.set(assertThrows(OcrException::class.java) { engine.recognize(image()) }) }.apply { start(); join() }
        assertEquals(OcrFailure.Resource(retryable = true), (wrongThread.get() as OcrException).failure)
        assertEquals(0, creations)
        assertEquals(OcrFailure.Cancelled, assertThrows(OcrException::class.java) {
            engine.recognize(image(), cancellationSignal = com.folium.reader.core.pdf.CancellationSignal { true })
        }.failure)
        engine.close()
        assertEquals(OcrFailure.Closed, assertThrows(OcrException::class.java) { engine.recognize(image()) }.failure)
    }

    @Test fun bitmapAndSetImageOutOfMemoryMapToRetryableResourceAndRecycleExactlyOnce() {
        val creationError = assertThrows(OcrException::class.java) {
            engine(FakeFactory()).recognize(image())
        }
        assertEquals(OcrFailure.Resource(retryable = true), creationError.failure)
        val bitmap = FakeBitmap()
        val api = FakeApi().apply { setImageFailure = OutOfMemoryError("set-image") }
        val setImageError = assertThrows(OcrException::class.java) {
            engine(FakeFactory(api), RecognitionBitmapFactory { bitmap }).recognize(image())
        }
        assertEquals(OcrFailure.Resource(retryable = true), setImageError.failure)
        assertEquals(1, bitmap.recycles)
        val inputCause = IllegalStateException("input")
        val inputBitmap = FakeBitmap()
        val inputFailure = assertThrows(OcrException::class.java) {
            engine(FakeFactory(FakeApi().apply { setImageFailure = inputCause }), RecognitionBitmapFactory { inputBitmap }).recognize(image())
        }
        assertEquals(OcrFailure.Recognition, inputFailure.failure)
        assertSame(inputCause, inputFailure.cause)
        assertEquals(1, inputBitmap.recycles)
    }

    @Test fun getUtf8TextFailureMapsToRecognitionKeepsCauseAndRecyclesBitmap() {
        val cause = IllegalStateException("trigger")
        val bitmap = FakeBitmap()
        val api = FakeApi().apply { textFailure = cause }
        val failure = assertThrows(OcrException::class.java) {
            engine(FakeFactory(api), RecognitionBitmapFactory { bitmap }).recognize(image())
        }
        assertEquals(OcrFailure.Recognition, failure.failure)
        assertSame(cause, failure.cause)
        assertEquals(1, bitmap.recycles)
    }

    @Test fun iteratorAcquisitionFailuresMapToRecognitionAndRecycleBitmap() {
        val missingBitmap = FakeBitmap()
        val missing = assertThrows(OcrException::class.java) {
            engine(FakeFactory(FakeApi().apply { iterator = null }), RecognitionBitmapFactory { missingBitmap }).recognize(image())
        }
        assertEquals(OcrFailure.Recognition, missing.failure)
        assertEquals(1, missingBitmap.recycles)
        val cause = IllegalStateException("iterator")
        val failingBitmap = FakeBitmap()
        val failure = assertThrows(OcrException::class.java) {
            engine(FakeFactory(FakeApi().apply { iteratorFailure = cause }), RecognitionBitmapFactory { failingBitmap }).recognize(image())
        }
        assertEquals(OcrFailure.Recognition, failure.failure)
        assertSame(cause, failure.cause)
        assertEquals(1, failingBitmap.recycles)
    }

    @Test fun iteratorFailureMapsToRecognitionAndDeletesIteratorAndBitmapExactlyOnce() {
        val bitmap = FakeBitmap()
        val iterator = FakeIterator().apply { nextFailure = IllegalStateException("iterate") }
        val api = FakeApi(iterator = iterator)
        val failure = assertThrows(OcrException::class.java) {
            engine(FakeFactory(api), RecognitionBitmapFactory { bitmap }).recognize(image())
        }
        assertEquals(OcrFailure.Recognition, failure.failure)
        assertEquals(1, iterator.deletes)
        assertEquals(1, bitmap.recycles)
    }

    @Test fun initializationExceptionMapsToInitializationAndRecyclesNativeExactlyOnce() {
        val cause = IllegalStateException("init")
        val api = FakeApi().apply { initFailure = cause }
        val failure = assertThrows(OcrException::class.java) { engine(FakeFactory(api)).recognize(image()) }
        assertEquals(OcrFailure.Initialization, failure.failure)
        assertSame(cause, failure.cause)
        assertEquals(1, api.recycles)
    }

    @Test fun fatalNativeErrorRethrowsAndCleansUpAndSuccessTriggersTextBeforeIterator() {
        val fatalBitmap = FakeBitmap()
        val fatal = AssertionError("fatal")
        val fatalApi = FakeApi().apply { textFailure = fatal }
        assertSame(fatal, assertThrows(AssertionError::class.java) {
            engine(FakeFactory(fatalApi), RecognitionBitmapFactory { fatalBitmap }).recognize(image())
        })
        assertEquals(1, fatalBitmap.recycles)

        val events = mutableListOf<String>()
        val iterator = FakeIterator(events)
        val api = FakeApi(iterator, events)
        val bitmap = FakeBitmap()
        engine(FakeFactory(api), RecognitionBitmapFactory { bitmap }).use { result ->
            assertEquals("word", result.recognize(image()).text)
        }
        assertTrue(events.indexOf("text") < events.indexOf("iterator"))
        assertEquals(1, iterator.deletes)
        assertEquals(1, bitmap.recycles)
        assertEquals(1, api.recycles)
        assertFalse(events.isEmpty())
    }

    @Test fun languageMetadataIsOnlyClaimedForSingleLanguageRecognition() {
        val defaultResult = engine(FakeFactory(), RecognitionBitmapFactory { FakeBitmap() }).use { engine ->
            engine.recognize(image())
        }
        assertEquals(null, defaultResult.words.single().languageTag)

        val spanishResult = engine(FakeFactory(), RecognitionBitmapFactory { FakeBitmap() }).use { engine ->
            engine.recognize(image(), OcrRequest(setOf(OcrLanguage.SPANISH)))
        }
        assertEquals("es", spanishResult.words.single().languageTag)
    }

    private fun engine(
        factory: NativeTesseractFactory,
        bitmapFactory: RecognitionBitmapFactory = RecognitionBitmapFactory { throw OutOfMemoryError("bitmap") }
    ): TesseractOcrEngine {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "runtime-${System.nanoTime()}")
        return TesseractOcrEngine(context, root, factory, bitmapFactory)
    }

    private fun image() = PageImage(1, 1, com.folium.reader.core.ocr.PixelFormat.RGBA_8888, byteArrayOf(0, 0, 0, -1))

    private class FakeFactory(private val api: FakeApi = FakeApi()) : NativeTesseractFactory {
        override fun create(): NativeTesseractApi = api
    }

    private class FakeBitmap : RecognitionBitmap {
        override val bitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        var recycles = 0
        override fun recycle() { recycles++; bitmap.recycle() }
    }

    private class FakeApi(
        var iterator: NativeResultIterator? = FakeIterator(),
        private val events: MutableList<String>? = null
    ) : NativeTesseractApi {
        var initFailure: Throwable? = null
        var setImageFailure: Throwable? = null
        var textFailure: Throwable? = null
        var iteratorFailure: Throwable? = null
        var recycles = 0
        override fun init(dataPath: String, languages: String): Boolean { initFailure?.let { throw it }; return true }
        override fun setImage(bitmap: Bitmap) { setImageFailure?.let { throw it } }
        override fun getUTF8Text(): String? { events?.add("text"); textFailure?.let { throw it }; return "recognized" }
        override fun resultIterator(): NativeResultIterator? { events?.add("iterator"); iteratorFailure?.let { throw it }; return iterator }
        override fun recycle() { recycles++ }
    }

    private class FakeIterator(private val events: MutableList<String>? = null) : NativeResultIterator {
        var nextFailure: Throwable? = null
        var deletes = 0
        override fun begin() { events?.add("begin") }
        override fun next(): Boolean { nextFailure?.let { throw it }; return false }
        override fun wordText(): String? = "word"
        override fun boundingBox(): IntArray = intArrayOf(0, 0, 1, 1)
        override fun confidence(): Float = 100f
        override fun isAtFinalWordOfLine(): Boolean = true
        override fun delete() { deletes++ }
    }
}
