package com.folium.reader.ocr_tesseract

import com.folium.reader.core.ocr.OcrLanguage
import com.folium.reader.core.ocr.OcrRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TesseractTextEngineVersionTest {
    @Test fun versionIsDeterministicAndIncludesEngineRequestedLanguagesAndDataHashes() {
        val bilingual = tesseractTextEngineVersion(OcrRequest(setOf(OcrLanguage.SPANISH, OcrLanguage.ENGLISH)))
        val reordered = tesseractTextEngineVersion(OcrRequest(setOf(OcrLanguage.ENGLISH, OcrLanguage.SPANISH)))
        val english = tesseractTextEngineVersion(OcrRequest(setOf(OcrLanguage.ENGLISH)))

        assertEquals(bilingual, reordered)
        assertNotEquals(bilingual, english)
        assertTrue(bilingual.value.startsWith("tesseract4android-4.9.0|"))
        assertTrue(bilingual.value.contains("eng:7d4322bd"))
        assertTrue(bilingual.value.contains("spa:6f2e04d0"))
    }
}
