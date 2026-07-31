package com.folium.reader.core.ocr

import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.PageSpaceRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OcrEngineContractTest {
    @Test fun defaultLanguagesAndImageMetadataAreNeutralAndImmutable() {
        assertEquals(setOf(OcrLanguage.SPANISH, OcrLanguage.ENGLISH), OcrRequest.DEFAULT.languages)
        assertEquals(
            PixelFormat.RGBA_8888,
            PageImage(2, 1, PixelFormat.RGBA_8888, byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)).pixelFormat
        )
        assertThrows(IllegalArgumentException::class.java) {
            PageImage(2, 1, PixelFormat.RGBA_8888, ByteArray(7))
        }
    }

    @Test fun resultOnlyAcceptsOrderedNormalizedGeometry() {
        val word = OcrWord("lectura", PageSpaceRect(0.1f, 0.2f, 0.4f, 0.3f), 0.95f, OcrLanguage.SPANISH)
        assertEquals("lectura", OcrResult(listOf(OcrLine(listOf(word)))).text)
        assertThrows(IllegalArgumentException::class.java) {
            OcrWord("bad", PageSpaceRect(0.1f, 0.2f, 0.4f, 0.3f), 1.1f, OcrLanguage.ENGLISH)
        }
    }

    @Test fun typedFailuresAndCancellationStayAdapterNeutral() {
        assertEquals(OcrFailure.Cancelled, OcrException(OcrFailure.Cancelled).failure)
        assertEquals(OcrFailure.Closed, OcrException(OcrFailure.Closed).failure)
        assertEquals(CancellationSignal { true }.isCancelled(), true)
    }
}
