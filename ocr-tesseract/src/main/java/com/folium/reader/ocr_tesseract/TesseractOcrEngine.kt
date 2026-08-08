package com.folium.reader.ocr_tesseract

import android.content.Context
import com.folium.reader.core.ocr.OcrEngine
import com.folium.reader.core.ocr.OcrException
import com.folium.reader.core.ocr.OcrFailure
import com.folium.reader.core.ocr.OcrLanguage
import com.folium.reader.core.ocr.OcrRequest
import com.folium.reader.core.ocr.PageImage
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.text.TextBlock
import com.folium.reader.core.text.TextLine
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextWord
import com.folium.reader.core.text.TextEngineVersion
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer

class TesseractOcrEngine internal constructor(
    context: Context,
    private val dataRoot: File,
    private val nativeFactory: NativeTesseractFactory,
    private val bitmapFactory: RecognitionBitmapFactory
) : OcrEngine {
    constructor(context: Context, dataRoot: File = File(context.filesDir, "folium-ocr")) : this(
        context,
        dataRoot,
        AndroidNativeTesseractFactory,
        AndroidRecognitionBitmapFactory
    )


    private val applicationContext = context.applicationContext
    private val ownerThread = Thread.currentThread()
    private val apiOwner = NativeApiOwner<NativeTesseractApi>(NativeTesseractApi::recycle)
    private var closed = false

    override fun textEngineVersion(request: OcrRequest): TextEngineVersion = tesseractTextEngineVersion(request)

    override fun recognize(image: PageImage, request: OcrRequest, cancellationSignal: CancellationSignal): TextPage {
        checkOwnerAndOpen()
        checkpoint(cancellationSignal)
        return try {
            val tess = apiFor(request)
            checkpoint(cancellationSignal)
            bitmapFactory.create(image).useForRecognition(tess, image, request, cancellationSignal)
        } catch (error: OcrException) {
            throw error
        } catch (_: OutOfMemoryError) {
            throw OcrException(OcrFailure.Resource(retryable = true))
        } catch (error: Exception) {
            throw OcrException(OcrFailure.Recognition, error)
        }
    }

    override fun close() {
        if (!closed) {
            checkOwner()
            closed = true
            apiOwner.close()
        }
    }

    private fun apiFor(request: OcrRequest): NativeTesseractApi {
        try {
            installData(dataRoot)
        } catch (_: Exception) {
            throw OcrException(OcrFailure.LanguageData)
        }
        val languageCodes = request.languages.map { it.code }.toSet()
        return try {
            apiOwner.acquire(
                languageCodes,
                create = nativeFactory::create,
                initialize = { created ->
                    val languages = languageCodes.sorted().joinToString("+")
                    if (!created.init(dataRoot.absolutePath, languages)) throw OcrException(OcrFailure.LanguageData)
                    true
                }
            )
        } catch (failure: OcrException) {
            throw failure
        } catch (failure: Error) {
            throw failure
        } catch (failure: Exception) {
            throw OcrException(OcrFailure.Initialization, failure)
        }
    }

    private fun installData(root: File) {
        val directory = File(root, "tessdata")
        if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("Cannot create OCR data directory")
        TRAINED_DATA.forEach { (name, expectedHash) ->
            val destination = File(directory, "$name.traineddata")
            if (!destination.exists() || destination.sha256() != expectedHash) {
                val temporary = File(directory, ".$name.traineddata.installing")
                try {
                    applicationContext.assets.open("tessdata/$name.traineddata").use { input ->
                        temporary.outputStream().use(input::copyTo)
                    }
                    check(temporary.sha256() == expectedHash)
                    if (destination.exists() && !destination.delete()) throw IllegalStateException("Cannot replace OCR data")
                    if (!temporary.renameTo(destination)) throw IllegalStateException("Cannot install OCR data")
                } finally {
                    if (temporary.exists()) temporary.delete()
                }
            }
            check(destination.sha256() == expectedHash)
        }
    }

    private fun RecognitionBitmap.useForRecognition(
        tess: NativeTesseractApi,
        image: PageImage,
        request: OcrRequest,
        cancellationSignal: CancellationSignal
    ): TextPage {
        try {
            tess.setImage(bitmap)
            checkpoint(cancellationSignal)
            tess.getUTF8Text() ?: throw RecognitionStageException("recognition-returned-null")
            checkpoint(cancellationSignal)
            val iterator = tess.resultIterator() ?: throw RecognitionStageException("result-iterator-unavailable")
            val lines = iterator.useWords(image.width, image.height, request)
            checkpoint(cancellationSignal)
            val textLines = lines.mapIndexed { index, words -> TextLine(words, index) }
            val blocks = if (textLines.isEmpty()) emptyList() else listOf(TextBlock(textLines, 0))
            return TextPage(blocks, TextSource.OCR)
        } finally {
            recycle()
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

    private fun NativeResultIterator.useWords(width: Int, height: Int, request: OcrRequest): List<List<TextWord>> {
        try {
            val languageTag = request.languages.singleOrNull()?.languageTag
            val lines = mutableListOf<MutableList<TextWord>>()
            var current = mutableListOf<TextWord>()
            begin()
            do {
                val text = wordText()?.trim()?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
                val box = boundingBox()
                if (!text.isNullOrBlank() && box != null) {
                    current += TextWord(
                        text = text,
                        box = TesseractGeometry.toPageSpace(box, width, height),
                        readingOrder = current.size,
                        languageTag = languageTag,
                        confidence = (confidence() / 100f).coerceIn(0f, 1f)
                    )
                }
                if (isAtFinalWordOfLine() && current.isNotEmpty()) {
                    lines += current
                    current = mutableListOf()
                }
            } while (next())
            if (current.isNotEmpty()) lines += current
            return lines
        } finally {
            delete()
        }
    }

    private class RecognitionStageException(stage: String) : IllegalStateException(stage)

}

private val TRAINED_DATA = mapOf(
    "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
    "spa" to "6f2e04d02774a18f01bed44b1111f2cd7f3ba7ac9dc4373cd3f898a40ea6b464"
)

internal fun tesseractTextEngineVersion(request: OcrRequest): TextEngineVersion {
    val requestedData = request.languages.sortedBy(OcrLanguage::code).joinToString(",") { language ->
        "${language.code}:${TRAINED_DATA.getValue(language.code)}"
    }
    return TextEngineVersion("tesseract4android-4.9.0|$requestedData")
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
