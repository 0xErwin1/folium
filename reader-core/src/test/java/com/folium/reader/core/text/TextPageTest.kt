package com.folium.reader.core.text

import com.folium.reader.core.pdf.PageSpaceRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextPageTest {
    @Test fun nativeAndOcrUseOneOrderedPageModel() {
        val first = TextWord(
            text = "Biblioteca",
            box = PageSpaceRect(0.1f, 0.1f, 0.4f, 0.2f),
            readingOrder = 0,
            fonts = listOf(TextFont("Noto Sans", bold = false, italic = false, serif = false, monospaced = false))
        )
        val second = TextWord(
            text = "reader",
            box = PageSpaceRect(0.5f, 0.1f, 0.8f, 0.2f),
            readingOrder = 1,
            languageTag = "en",
            confidence = 0.9f
        )
        val page = TextPage(
            blocks = listOf(TextBlock(listOf(TextLine(listOf(first, second), 0)), 0)),
            source = TextSource.NATIVE_PDF
        )

        assertEquals("Biblioteca reader", page.text)
        assertEquals(PageSpaceRect(0.1f, 0.1f, 0.8f, 0.2f), page.blocks.single().box)
        assertEquals(listOf(first, second), page.words)
    }

    @Test fun textAndReadingOrderMustAlreadyBeCanonical() {
        val box = PageSpaceRect(0.1f, 0.1f, 0.4f, 0.2f)
        assertThrows(IllegalArgumentException::class.java) {
            TextWord("A\u0301", box, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextLine(listOf(TextWord("one", box, 1)), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextPage(listOf(TextBlock(listOf(TextLine(listOf(TextWord("one", box, 0)), 0)), 1)), TextSource.OCR)
        }
    }
}
