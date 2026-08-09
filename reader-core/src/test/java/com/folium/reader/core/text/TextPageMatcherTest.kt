package com.folium.reader.core.text

import com.folium.reader.core.pdf.PageSpaceRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPageMatcherTest {
    @Test fun `matching is accent and case insensitive while punctuation stays literal`() {
        val page = page(line(0, "Café,", "CAFÉ", "cafe"))
        assertEquals(listOf(0..0, 1..1, 2..2), TextPageMatcher.find(page, "cAfÉ").map { it.wordRange })
        assertEquals(listOf(0..0), TextPageMatcher.find(page, "cafe,").map { it.wordRange })
        assertTrue(TextPageMatcher.find(page, "\" OR *").isEmpty())
    }

    @Test fun `repeated and cross word occurrences preserve word ranges`() {
        val page = page(line(0, "banana", "alpha"), line(1, "beta", "banana"))
        assertEquals(listOf(0..0, 0..0, 3..3, 3..3), TextPageMatcher.find(page, "ana").map { it.wordRange })
        assertEquals(listOf(1..2), TextPageMatcher.find(page, "ALPHA BÉTA").map { it.wordRange })
    }

    @Test fun `geometry is grouped into stable line bands and snippets are bounded`() {
        val page = page(line(0, "before", "matching"), line(1, "phrase", "after", "tail"))
        val match = TextPageMatcher.find(page, "matching phrase", snippetChars = 22).single()
        assertEquals(1..2, match.wordRange)
        assertEquals(2, match.boxes.size)
        assertEquals(PageSpaceRect(.2f, .1f, .3f, .15f), match.boxes.first())
        assertTrue(match.snippet.length <= 24)
        assertTrue(match.snippet.contains("matching phrase"))
    }

    @Test fun `unicode folding unifies sigma and preserves expansion geometry`() {
        val page = page(line(0, "ΟΣ", "ος", "οσ", "Straße", "STRASSE", "😀Café"))

        assertEquals(listOf(0..0, 1..1, 2..2), TextPageMatcher.find(page, "οσ").map { it.wordRange })
        assertEquals(listOf(0..0, 1..1, 2..2), TextPageMatcher.find(page, "ΟΣ").map { it.wordRange })
        val german = TextPageMatcher.find(page, "strasse")
        assertEquals(listOf(3..3, 4..4), german.map { it.wordRange })
        assertEquals(listOf(PageSpaceRect(.4f, .1f, .5f, .15f)), german.first().boxes)
        assertEquals(listOf(5..5), TextPageMatcher.find(page, "cafe").map { it.wordRange })
        assertEquals("STRASSE", TextPageMatcher.normalizeLiteral("Straße"))
    }

    private fun page(vararg lines: TextLine) = TextPage(
        listOf(TextBlock(lines.toList(), 0)), TextSource.NATIVE_PDF
    )

    private fun line(lineIndex: Int, vararg words: String): TextLine {
        return TextLine(words.mapIndexed { index, text ->
            TextWord(text, PageSpaceRect(index / 10f + .1f, lineIndex / 10f + .1f, index / 10f + .2f, lineIndex / 10f + .15f), index)
        }, lineIndex)
    }

}
