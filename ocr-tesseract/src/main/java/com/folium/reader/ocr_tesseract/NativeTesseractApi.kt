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

internal object AndroidRecognitionBitmapFactory : RecognitionBitmapFactory {
    override fun create(image: com.folium.reader.core.ocr.PageImage): RecognitionBitmap {
        val rgba = image.pixels()
        val argb = IntArray(image.width * image.height) { index ->
            val offset = index * 4
            ((rgba[offset + 3].toInt() and 0xff) shl 24) or
                ((rgba[offset].toInt() and 0xff) shl 16) or
                ((rgba[offset + 1].toInt() and 0xff) shl 8) or
                (rgba[offset + 2].toInt() and 0xff)
        }
        return AndroidRecognitionBitmap(Bitmap.createBitmap(argb, image.width, image.height, Bitmap.Config.ARGB_8888))
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
    override fun delete() = iterator.delete()
}
