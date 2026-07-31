package com.folium.reader.core.ocr

import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.PageSpaceRect
import java.io.Closeable
import java.text.Normalizer

enum class PixelFormat(val bytesPerPixel: Int) { RGBA_8888(4) }

enum class OcrLanguage(val code: String) { SPANISH("spa"), ENGLISH("eng") }

enum class OcrSource { OCR }

class PageImage(width: Int, height: Int, val pixelFormat: PixelFormat, pixels: ByteArray) {
    val width: Int
    val height: Int
    private val storage: ByteArray

    init {
        require(width > 0 && height > 0)
        val expected = Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), pixelFormat.bytesPerPixel.toLong())
        require(expected == pixels.size.toLong())
        this.width = width
        this.height = height
        storage = pixels.copyOf()
    }

    fun pixels(): ByteArray = storage.copyOf()
}

data class OcrRequest(val languages: Set<OcrLanguage> = DEFAULT.languages) {
    init { require(languages.isNotEmpty()) }

    companion object { val DEFAULT = OcrRequest(setOf(OcrLanguage.SPANISH, OcrLanguage.ENGLISH)) }
}

data class OcrWord(val text: String, val box: PageSpaceRect, val confidence: Float, val language: OcrLanguage) {
    init {
        require(text == Normalizer.normalize(text, Normalizer.Form.NFC) && text.isNotBlank())
        require(confidence in 0f..1f)
    }
}

data class OcrLine(val words: List<OcrWord>) {
    init {
        require(words.isNotEmpty())
        require(words.zipWithNext().all { (first, second) ->
            second.box.top > first.box.top ||
                (second.box.top == first.box.top && second.box.left >= first.box.left)
        })
    }

    val text: String get() = words.joinToString(" ") { it.text }
}

data class OcrResult(val lines: List<OcrLine>, val source: OcrSource = OcrSource.OCR) {
    val words: List<OcrWord> get() = lines.flatMap { it.words }
    val text: String get() = lines.joinToString("\n") { it.text }
}

sealed class OcrFailure {
    data object Initialization : OcrFailure()
    data object LanguageData : OcrFailure()
    data object Recognition : OcrFailure()
    data object Cancelled : OcrFailure()
    data object Closed : OcrFailure()
    data class Resource(val retryable: Boolean) : OcrFailure()
}

class OcrException(val failure: OcrFailure) : RuntimeException(failure.javaClass.simpleName)

/** A single engine instance is owned by one thread and must be closed deterministically. */
interface OcrEngine : Closeable {
    fun recognize(image: PageImage, request: OcrRequest = OcrRequest.DEFAULT, cancellationSignal: CancellationSignal = CancellationSignal { false }): OcrResult
    override fun close()
}
