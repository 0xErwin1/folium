package com.folium.reader.ocr_tesseract

import com.folium.reader.core.pdf.PageSpaceRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TesseractGeometryTest {
    @Test fun mapsImageCoordinatesToNormalizedUnrotatedPageSpace() {
        assertEquals(
            PageSpaceRect(0.1f, 0.2f, 0.4f, 0.5f),
            TesseractGeometry.toPageSpace(intArrayOf(10, 20, 40, 50), 100, 100)
        )
    }

    @Test fun nearestRankP95UsesTheFinalObservationForFiveSamples() {
        val samples = listOf(5L, 10L, 15L, 20L, 25L).sorted()
        assertEquals(25L, samples[(kotlin.math.ceil(samples.size * 0.95).toInt().coerceIn(1, samples.size) - 1)])
    }

    @Test fun rejectsNativeInvalidBoxesBeforeTheyLeak() {
        assertThrows(IllegalArgumentException::class.java) {
            TesseractGeometry.toPageSpace(intArrayOf(30, 20, 10, 50), 100, 100)
        }
    }
}
