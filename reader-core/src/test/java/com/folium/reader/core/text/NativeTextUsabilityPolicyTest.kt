package com.folium.reader.core.text

import com.folium.reader.core.pdf.PageSpaceRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTextUsabilityPolicyTest {
    @Test fun exposesStableVersion() {
        assertEquals("native-usability-v1", NATIVE_TEXT_USABILITY_POLICY_VERSION)
    }

    @Test fun acceptsLettersDigitsAndSupplementaryUnicode() {
        assertTrue(page("hello").hasUsableNativeText())
        assertTrue(page("12345").hasUsableNativeText())
        assertTrue(page("\uD801\uDC00").hasUsableNativeText()) // U+10400 DESERET CAPITAL LETTER LONG I
        assertTrue(page("…", "a1!").hasUsableNativeText())
    }

    @Test fun rejectsEmptyAndPunctuationOnlyPages() {
        assertFalse(TextPage(emptyList(), TextSource.NATIVE_PDF).hasUsableNativeText())
        assertFalse(page("…!?—").hasUsableNativeText())
    }

    private fun page(vararg text: String): TextPage {
        val words = text.mapIndexed { index, value ->
            TextWord(value, PageSpaceRect(0f, 0f, 1f, 1f), index)
        }
        return TextPage(listOf(TextBlock(listOf(TextLine(words, 0)), 0)), TextSource.NATIVE_PDF)
    }
}
