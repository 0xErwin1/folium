package com.folium.reader.ocr_tesseract

import android.content.Context
import com.folium.reader.core.ocr.OcrEngine
import com.folium.reader.core.ocr.OcrEngineEnvironment
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
import java.io.InputStream
import java.io.IOException
import java.text.Normalizer

class TesseractOcrEngine internal constructor(
    private val dataRoot: File,
    private val nativeFactory: NativeTesseractFactory,
    private val bitmapFactory: RecognitionBitmapFactory,
    private val openTrainedData: (String) -> InputStream
) : OcrEngine {
    constructor(context: Context, dataRoot: File = File(context.filesDir, "folium-ocr")) : this(
        dataRoot,
        AndroidNativeTesseractFactory,
        AndroidRecognitionBitmapFactory,
        { name -> context.applicationContext.assets.open("tessdata/$name.traineddata") }
    )

    internal constructor(
        context: Context,
        dataRoot: File,
        nativeFactory: NativeTesseractFactory,
        bitmapFactory: RecognitionBitmapFactory
    ) : this(
        dataRoot,
        nativeFactory,
        bitmapFactory,
        { name -> context.applicationContext.assets.open("tessdata/$name.traineddata") }
    )

    internal constructor(environment: OcrEngineEnvironment) : this(
        environment.dataRoot,
        AndroidNativeTesseractFactory,
        AndroidRecognitionBitmapFactory,
        { name -> environment.openData(OcrLanguage.entries.single { it.code == name }) }
    )

    private val ownerThread = Thread.currentThread()
    private val apiOwner = NativeApiOwner<NativeTesseractApi>(NativeTesseractApi::recycle)
    private val installer = TrainedDataInstaller(dataRoot, TRAINED_DATA, openTrainedData)
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
            installer.install()
        } catch (failure: OcrException) {
            throw failure
        } catch (failure: Exception) {
            throw mapLanguageDataInstallFailure(failure)
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
            val paragraphs = iterator.useWords(image.width, image.height, request)
            checkpoint(cancellationSignal)
            val blocks = paragraphs.mapIndexed { blockIndex, lines ->
                TextBlock(lines.mapIndexed { lineIndex, words -> TextLine(words, lineIndex) }, blockIndex)
            }
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

    /**
     * Groups the recognized words into paragraphs of lines.
     *
     * The paragraph level matters beyond layout: [com.folium.reader.core.text.TextSelectionPolicy]
     * separates blocks with a blank line when text is copied, so recognizing a page as one block
     * meant every OCR'd page was copied as a single run of lines with its paragraph breaks lost.
     */
    private fun NativeResultIterator.useWords(
        width: Int,
        height: Int,
        request: OcrRequest
    ): List<List<List<TextWord>>> {
        try {
            val languageTag = request.languages.singleOrNull()?.languageTag
            val paragraphs = mutableListOf<MutableList<List<TextWord>>>()
            var currentParagraph = mutableListOf<List<TextWord>>()
            var currentLine = mutableListOf<TextWord>()
            begin()
            do {
                val text = wordText()?.trim()?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
                val box = boundingBox()
                if (!text.isNullOrBlank() && box != null) {
                    currentLine += TextWord(
                        text = text,
                        box = TesseractGeometry.toPageSpace(box, width, height),
                        readingOrder = currentLine.size,
                        languageTag = languageTag,
                        confidence = (confidence() / 100f).coerceIn(0f, 1f)
                    )
                }
                val endsLine = isAtFinalWordOfLine()
                val endsParagraph = isAtFinalWordOfParagraph()
                // A paragraph boundary is also a line boundary, but closing a paragraph over an
                // unfinished line would strand its words in whichever paragraph came next, so the
                // line is closed on either signal rather than relying on that.
                if ((endsLine || endsParagraph) && currentLine.isNotEmpty()) {
                    currentParagraph += currentLine
                    currentLine = mutableListOf()
                }
                if (endsParagraph && currentParagraph.isNotEmpty()) {
                    paragraphs += currentParagraph
                    currentParagraph = mutableListOf()
                }
            } while (next())
            if (currentLine.isNotEmpty()) currentParagraph += currentLine
            if (currentParagraph.isNotEmpty()) paragraphs += currentParagraph
            return paragraphs
        } finally {
            delete()
        }
    }

    private class RecognitionStageException(stage: String) : IllegalStateException(stage)

}

internal class MissingBundledLanguageDataException(cause: Throwable) : IOException(cause)
internal class LanguageDataIntegrityException(cause: Throwable? = null) : IllegalStateException(cause)

internal fun mapLanguageDataInstallFailure(failure: Exception): OcrException = when (failure) {
    is MissingBundledLanguageDataException,
    is LanguageDataIntegrityException -> OcrException(OcrFailure.LanguageData, failure)
    is IOException,
    is SecurityException -> OcrException(OcrFailure.Resource(retryable = true), failure)
    else -> OcrException(OcrFailure.LanguageData, failure)
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
