package com.folium.reader.core.ocr

import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextEngineVersion
import java.io.Closeable
import java.io.File
import java.io.InputStream

enum class PixelFormat(val bytesPerPixel: Int) { RGBA_8888(4) }

enum class OcrLanguage(val code: String, val languageTag: String) {
    SPANISH("spa", "es"),
    ENGLISH("eng", "en")
}

class PageImage(width: Int, height: Int, val pixelFormat: PixelFormat, pixels: ByteArray) : Closeable {
    val width: Int
    val height: Int
    private val storage: ByteArray
    private var closed = false

    init {
        require(width > 0 && height > 0)
        val expected = Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), pixelFormat.bytesPerPixel.toLong())
        require(expected == pixels.size.toLong())
        this.width = width
        this.height = height
        storage = pixels.copyOf()
    }

    @Synchronized fun pixels(): ByteArray {
        check(!closed)
        return storage.copyOf()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        storage.fill(0)
    }
}

data class OcrRequest(val languages: Set<OcrLanguage> = DEFAULT.languages) {
    init { require(languages.isNotEmpty()) }

    companion object { val DEFAULT = OcrRequest(setOf(OcrLanguage.SPANISH, OcrLanguage.ENGLISH)) }
}

sealed class OcrFailure {
    data object Initialization : OcrFailure()
    data object LanguageData : OcrFailure()
    data object Recognition : OcrFailure()
    data object Cancelled : OcrFailure()
    data object Closed : OcrFailure()
    data class Resource(val retryable: Boolean) : OcrFailure()
}

class OcrException(val failure: OcrFailure, cause: Throwable? = null) : RuntimeException(failure.javaClass.simpleName, cause)

/** A single engine instance is owned by one thread and must be closed deterministically. */
interface OcrEngine : Closeable {
    fun textEngineVersion(request: OcrRequest = OcrRequest.DEFAULT): TextEngineVersion
    fun recognize(image: PageImage, request: OcrRequest = OcrRequest.DEFAULT, cancellationSignal: CancellationSignal = CancellationSignal { false }): TextPage
    override fun close()
}

/** Engine-owned identity provider. Loading this descriptor never constructs or runs an OCR engine. */
interface OcrEngineDescriptor {
    fun textEngineVersion(request: OcrRequest = OcrRequest.DEFAULT): TextEngineVersion
    fun create(environment: OcrEngineEnvironment): OcrEngine
}

class OcrEngineEnvironment(
    val dataRoot: File,
    private val openLanguageData: (OcrLanguage) -> InputStream
) {
    fun openData(language: OcrLanguage): InputStream = openLanguageData(language)
}
