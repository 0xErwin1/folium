package com.folium.reader.ocr_tesseract

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.folium.reader.core.pdf.PageSpaceRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TesseractGeometryTest {
    @Test fun mapsNativeImageBoxToUnrotatedNormalizedPageSpace() {
        assertEquals(PageSpaceRect(0.1f, 0.2f, 0.4f, 0.5f), TesseractGeometry.toPageSpace(intArrayOf(90, 240, 360, 600), 900, 1200))
    }

    @Test fun rejectsInvalidNativeBox() {
        assertThrows(IllegalArgumentException::class.java) {
            TesseractGeometry.toPageSpace(intArrayOf(360, 240, 90, 600), 900, 1200)
        }
    }
}
