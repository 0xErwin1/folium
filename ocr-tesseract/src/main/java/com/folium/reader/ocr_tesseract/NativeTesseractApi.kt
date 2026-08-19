package com.folium.reader.ocr_tesseract

import android.graphics.Bitmap
import com.googlecode.tesseract.android.ResultIterator
import com.googlecode.tesseract.android.TessBaseAPI

internal fun interface NativeTesseractFactory {
    fun create(): NativeTesseractApi
}

internal interface NativeTesseractApi {
    fun init(dataPath: String, languages: String): Boolean
    fun setImage(bitmap: Bitmap)
    fun getUTF8Text(): String?
    fun resultIterator(): NativeResultIterator?
    fun recycle()
}

internal interface NativeResultIterator {
    fun begin()
    fun next(): Boolean
    fun wordText(): String?
    fun boundingBox(): IntArray?
    fun confidence(): Float
    fun isAtFinalWordOfLine(): Boolean
    fun isAtFinalWordOfParagraph(): Boolean
    fun delete()
}

internal fun interface RecognitionBitmapFactory {
    fun create(image: com.folium.reader.core.ocr.PageImage): RecognitionBitmap
}

internal interface RecognitionBitmap {
    val bitmap: Bitmap
    fun recycle()
}

internal object AndroidNativeTesseractFactory : NativeTesseractFactory {
    override fun create(): NativeTesseractApi = AndroidNativeTesseractApi(TessBaseAPI())
}

/**
 * The engine hands over 8-bit RGBA in memory order, which is exactly what an ARGB_8888 bitmap
 * stores, so the pixels are copied straight into the bitmap rather than being repacked into an
 * intermediate int array one pixel at a time. That array was a second full-page allocation and a
 * per-pixel loop in Kotlin for a conversion the platform does natively.
 */
internal object AndroidRecognitionBitmapFactory : RecognitionBitmapFactory {
    override fun create(image: com.folium.reader.core.ocr.PageImage): RecognitionBitmap {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(image.pixels()))
        bitmap.setHasAlpha(false)
        return AndroidRecognitionBitmap(bitmap)
    }
}

private class AndroidRecognitionBitmap(override val bitmap: Bitmap) : RecognitionBitmap {
    override fun recycle() = bitmap.recycle()
}

private class AndroidNativeTesseractApi(private val api: TessBaseAPI) : NativeTesseractApi {
    override fun init(dataPath: String, languages: String): Boolean = api.init(dataPath, languages)
    override fun setImage(bitmap: Bitmap) = api.setImage(bitmap)
    override fun getUTF8Text(): String? = api.getUTF8Text()
    override fun resultIterator(): NativeResultIterator? = api.resultIterator?.let(::AndroidNativeResultIterator)
    override fun recycle() = api.recycle()
}

private class AndroidNativeResultIterator(private val iterator: ResultIterator) : NativeResultIterator {
    override fun begin() = iterator.begin()
    override fun next(): Boolean = iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD)
    override fun wordText(): String? = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
    override fun boundingBox(): IntArray? = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)
    override fun confidence(): Float = iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
    override fun isAtFinalWordOfLine(): Boolean = iterator.isAtFinalElement(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE, TessBaseAPI.PageIteratorLevel.RIL_WORD)
    override fun isAtFinalWordOfParagraph(): Boolean = iterator.isAtFinalElement(TessBaseAPI.PageIteratorLevel.RIL_PARA, TessBaseAPI.PageIteratorLevel.RIL_WORD)
    override fun delete() = iterator.delete()
}
