package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val SEPARATOR = ''

class TypographyRecordsTest {

    private fun line(vararg fields: String): String = fields.joinToString(SEPARATOR.toString())

    private fun preset(
        fontFamily: ReflowFontFamily = ReflowFontFamily.PUBLISHER,
        fontSizePoints: Float = 18f,
        lineHeight: Float? = null,
        marginEm: Float = 0f,
        textAlign: ReflowTextAlign = ReflowTextAlign.PUBLISHER,
        paragraphIndentEm: Float? = null,
        pageColors: Boolean = false
    ) = TypographyPreset(fontFamily, fontSizePoints, lineHeight, marginEm, textAlign, paragraphIndentEm, pageColors)

    @Test fun theDefaultPresetRoundTripsThroughEncodeAndDecode() {
        val encoded = TypographyRecords.encode(TypographyPreset.DEFAULT)
        assertEquals(TypographyPreset.DEFAULT, TypographyRecords.decode(encoded))
    }

    @Test fun everyFamilyAndAlignmentRoundTrips() {
        ReflowFontFamily.entries.forEach { family ->
            val encoded = TypographyRecords.encode(preset(fontFamily = family))
            assertEquals(family, TypographyRecords.decode(encoded)?.fontFamily)
        }
        ReflowTextAlign.entries.forEach { align ->
            val encoded = TypographyRecords.encode(preset(textAlign = align))
            assertEquals(align, TypographyRecords.decode(encoded)?.textAlign)
        }
    }

    @Test fun aFullyPopulatedPresetRoundTrips() {
        val original = preset(
            fontFamily = ReflowFontFamily.SERIF,
            fontSizePoints = 24.5f,
            lineHeight = 1.4f,
            marginEm = 1.25f,
            textAlign = ReflowTextAlign.JUSTIFY,
            paragraphIndentEm = 1.5f,
            pageColors = true
        )
        val encoded = TypographyRecords.encode(original)
        assertEquals(original, TypographyRecords.decode(encoded))
    }

    @Test fun decodeRejectsTheWrongArity() {
        assertNull(TypographyRecords.decode(line("publisher", "18", "", "0", "publisher", "", "0", "extra")))
        assertNull(TypographyRecords.decode(line("publisher", "18")))
    }

    @Test fun decodeRejectsAnUnparseableNumber() {
        assertNull(TypographyRecords.decode(line("publisher", "not-a-number", "", "0", "publisher", "", "0")))
        assertNull(TypographyRecords.decode(line("publisher", "18", "", "not-a-number", "publisher", "", "0")))
        assertNull(TypographyRecords.decode(line("publisher", "18", "not-a-number", "0", "publisher", "", "0")))
    }

    @Test fun decodeRejectsAnUnrecognizedToken() {
        assertNull(TypographyRecords.decode(line("comic-sans", "18", "", "0", "publisher", "", "0")))
        assertNull(TypographyRecords.decode(line("publisher", "18", "", "0", "diagonal", "", "0")))
        assertNull(TypographyRecords.decode(line("publisher", "18", "", "0", "publisher", "", "2")))
    }

    @Test fun decodeRejectsAValueOutsideTheDocumentedRange() {
        assertNull(TypographyRecords.decode(line("publisher", "11.999", "", "0", "publisher", "", "0")))
        assertNull(TypographyRecords.decode(line("publisher", "32.001", "", "0", "publisher", "", "0")))
        assertNull(TypographyRecords.decode(line("publisher", "18", "", "-0.001", "publisher", "", "0")))
        assertNull(TypographyRecords.decode(line("publisher", "18", "", "4.001", "publisher", "", "0")))
    }

    @Test fun decodeRejectsTheVersionMarkerItself() {
        assertNull(TypographyRecords.decode(TYPOGRAPHY_VERSION_MARKER))
    }
}
