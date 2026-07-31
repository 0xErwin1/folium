package com.folium.reader.ocr_tesseract

import android.content.Context
import android.graphics.Bitmap
import com.folium.reader.core.ocr.OcrEngine
import com.folium.reader.core.ocr.OcrException
import com.folium.reader.core.ocr.OcrFailure
import com.folium.reader.core.ocr.OcrLanguage
import com.folium.reader.core.ocr.OcrLine
import com.folium.reader.core.ocr.OcrRequest
import com.folium.reader.core.ocr.OcrResult
import com.folium.reader.core.ocr.OcrWord
import com.folium.reader.core.ocr.PageImage
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.PageSpaceRect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.security.MessageDigest

class TesseractOcrEngine(context: Context, private val dataRoot: File = File(context.filesDir, "folium-ocr")) : OcrEngine {
    private val applicationContext = context.applicationContext
    private val ownerThread = Thread.currentThread()
    private var api: TessBaseAPI? = null
    private var closed = false

    override fun recognize(image: PageImage, request: OcrRequest, cancellationSignal: CancellationSignal): OcrResult {
        checkOwnerAndOpen()
        checkpoint(cancellationSignal)
        val tess = api ?: initialize(request)
        checkpoint(cancellationSignal)
        val bitmap = image.toBitmap()
        try {
            tess.setImage(bitmap)
            checkpoint(cancellationSignal)
            val words = tess.readWords(image.width, image.height)
            checkpoint(cancellationSignal)
            return OcrResult(words.map(::OcrLine))
        } catch (error: OcrException) {
            throw error
        } catch (_: OutOfMemoryError) {
            throw OcrException(OcrFailure.Resource(retryable = true))
        } catch (_: RuntimeException) {
            throw OcrException(OcrFailure.Recognition)
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        if (!closed) {
            checkOwner()
            closed = true
            api?.recycle()
            api = null
        }
    }

    private fun initialize(request: OcrRequest): TessBaseAPI {
        try {
            installData(dataRoot)
        } catch (_: Exception) {
            throw OcrException(OcrFailure.LanguageData)
        }
        val created = try { TessBaseAPI() } catch (_: RuntimeException) { throw OcrException(OcrFailure.Initialization) }
        val languages = request.languages.sortedBy { it.code }.joinToString("+") { it.code }
        if (!created.init(dataRoot.absolutePath, languages)) {
            created.recycle()
            throw OcrException(OcrFailure.LanguageData)
        }
        api = created
        return created
    }

    private fun installData(root: File) {
        val directory = File(root, "tessdata")
        if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("Cannot create OCR data directory")
        DATA.forEach { (name, expectedHash) ->
            val destination = File(directory, "$name.traineddata")
            if (!destination.exists() || destination.sha256() != expectedHash) {
                applicationContext.assets.open("tessdata/$name.traineddata").use { input ->
                    destination.outputStream().use(input::copyTo)
                }
            }
            check(destination.sha256() == expectedHash)
        }
    }

    private fun checkpoint(cancellationSignal: CancellationSignal) {
        if (cancellationSignal.isCancelled()) throw OcrException(OcrFailure.Cancelled)
    }

    private fun checkOwnerAndOpen() {
        checkOwner()
        if (closed) throw OcrException(OcrFailure.Closed)
    }

    private fun checkOwner() {
        if (Thread.currentThread() !== ownerThread) throw OcrException(OcrFailure.Resource(retryable = true))
    }

    private fun File.sha256(): String = inputStream().use { input ->
        MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it) }
    }

    private fun PageImage.toBitmap(): Bitmap {
        val rgba = pixels()
        val argb = IntArray(width * height) { index ->
            val offset = index * 4
            ((rgba[offset + 3].toInt() and 0xff) shl 24) or
                ((rgba[offset].toInt() and 0xff) shl 16) or
                ((rgba[offset + 1].toInt() and 0xff) shl 8) or
                (rgba[offset + 2].toInt() and 0xff)
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun TessBaseAPI.readWords(width: Int, height: Int): List<List<OcrWord>> {
        val iterator = resultIterator ?: return emptyList()
        val lines = mutableListOf<MutableList<OcrWord>>()
        var current = mutableListOf<OcrWord>()
        iterator.begin()
        do {
            val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim()
            val box = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)
            if (!text.isNullOrBlank() && box != null) {
                val language = if (text.any { it in "áéíóúüñÁÉÍÓÚÜÑ" }) OcrLanguage.SPANISH else OcrLanguage.ENGLISH
                current += OcrWord(text, TesseractGeometry.toPageSpace(box, width, height), (iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD) / 100f).coerceIn(0f, 1f), language)
            }
            if (iterator.isAtFinalElement(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE, TessBaseAPI.PageIteratorLevel.RIL_WORD) && current.isNotEmpty()) {
                lines += current
                current = mutableListOf()
            }
        } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        if (current.isNotEmpty()) lines += current
        iterator.delete()
        return lines
    }

    private companion object {
        val DATA = mapOf(
            "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
            "spa" to "6f2e04d02774a18f01bed44b1111f2cd7f3ba7ac9dc4373cd3f898a40ea6b464"
        )
    }
}

internal object TesseractGeometry {
    fun toPageSpace(box: IntArray, width: Int, height: Int): PageSpaceRect {
        require(box.size == 4 && width > 0 && height > 0)
        val left = box[0] / width.toFloat()
        val top = box[1] / height.toFloat()
        val right = box[2] / width.toFloat()
        val bottom = box[3] / height.toFloat()
        return PageSpaceRect(left, top, right, bottom)
    }
}
