package com.folium.reader.core.ocr

import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.text.TextBlock
import com.folium.reader.core.text.TextLine
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextWord
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

    @Test fun sharedResultOnlyAcceptsExplicitReadingOrder() {
        val first = TextWord("biblioteca", PageSpaceRect(0.1f, 0.3f, 0.4f, 0.4f), 0, languageTag = "es", confidence = 0.95f)
        val second = TextWord("lectura", PageSpaceRect(0.5f, 0.2f, 0.8f, 0.3f), 1, languageTag = "es", confidence = 0.95f)
        val page = TextPage(listOf(TextBlock(listOf(TextLine(listOf(first, second), 0)), 0)), TextSource.OCR)
        assertEquals("biblioteca lectura", page.text)
        assertThrows(IllegalArgumentException::class.java) {
            TextLine(listOf(second, first), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextWord("bad", PageSpaceRect(0.1f, 0.2f, 0.4f, 0.3f), 0, confidence = 1.1f)
        }
    }

    @Test fun typedFailuresAndCancellationStayAdapterNeutral() {
        assertEquals(OcrFailure.Cancelled, OcrException(OcrFailure.Cancelled).failure)
        assertEquals(OcrFailure.Closed, OcrException(OcrFailure.Closed).failure)
        assertEquals(CancellationSignal { true }.isCancelled(), true)
    }
}
