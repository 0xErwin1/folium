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

    @Test fun `case sensitive remains accent insensitive`() {
        val page = page(line(0, "Café", "CAFÉ", "cafe"))
        val spec = TextSearchSpec("Cafe", caseSensitive = true)
        assertEquals(listOf(0..0), success(page, spec).map { it.wordRange })
    }

    @Test fun `whole word uses unicode letters digits marks and connector punctuation`() {
        val page = page(line(0, "élan", "élan2", "x_élan", "élan-x", "élan"))
        val spec = TextSearchSpec("elan", wholeWord = true)
        assertEquals(listOf(0..0, 3..3, 4..4), success(page, spec).map { it.wordRange })
    }

    @Test fun `regex is accent stripped ordered non overlapping and keeps original geometry`() {
        val page = page(line(0, "Café", "cafe", "banana"))
        val matches = success(page, TextSearchSpec("caf.|ana", TextSearchMode.REGEX))
        assertEquals(listOf(0..0, 1..1, 2..2), matches.map { it.wordRange })
        assertEquals(listOf(PageSpaceRect(.1f, .1f, .2f, .15f)), matches.first().boxes)
    }

    @Test fun `regex case toggle and unicode property syntax are preserved`() {
        val page = page(line(0, "ÄBC", "abc", "123"))
        assertEquals(2, success(page, TextSearchSpec("\\p{L}+", TextSearchMode.REGEX)).size)
        assertEquals(1, success(page, TextSearchSpec("ABC", TextSearchMode.REGEX, caseSensitive = true)).size)
    }

    @Test fun `invalid zero length and unsupported regex return typed errors`() {
        assertEquals(TextSearchError.InvalidPattern, failure(TextSearchSpec("[", TextSearchMode.REGEX)))
        assertEquals(TextSearchError.ZeroLengthPattern, failure(TextSearchSpec("a*", TextSearchMode.REGEX)))
        assertEquals(TextSearchError.ZeroLengthPattern, failure(TextSearchSpec("\\b", TextSearchMode.REGEX)))
        assertEquals(TextSearchError.ZeroLengthPattern, failure(TextSearchSpec("^", TextSearchMode.REGEX)))
        assertEquals(TextSearchError.ZeroLengthPattern, failure(TextSearchSpec("$", TextSearchMode.REGEX)))
        assertEquals(TextSearchError.UnsupportedPattern, failure(TextSearchSpec("(.)\\1", TextSearchMode.REGEX)))
        assertEquals(TextSearchError.UnsupportedPattern, failure(TextSearchSpec("(?<=x)y", TextSearchMode.REGEX)))
        assertEquals(TextSearchError.UnsupportedPattern, failure(TextSearchSpec("a++", TextSearchMode.REGEX)))
        assertEquals(TextSearchError.UnsupportedPattern, failure(TextSearchSpec("\\Gword", TextSearchMode.REGEX)))
    }

    @Test fun `escaped unsupported-looking syntax remains literal regex text`() {
        val page = page(line(0, "\\1", "(?", "plain"))

        assertEquals(listOf(0..0), success(page, TextSearchSpec("\\\\1", TextSearchMode.REGEX)).map { it.wordRange })
        assertEquals(listOf(1..1), success(page, TextSearchSpec("\\(\\?", TextSearchMode.REGEX)).map { it.wordRange })
        assertEquals(TextSearchError.InvalidPattern, failure(TextSearchSpec("[abc", TextSearchMode.REGEX)))
    }

    @Test fun `matcher honors remaining limit and reports truncation`() {
        val result = TextPageMatcher.find(page(line(0, "aaaa")), TextSearchSpec("a"), limit = 3)
            as TextPageMatchResult.Success

        assertEquals(3, result.matches.size)
        assertTrue(result.truncated)
    }

    private fun success(page: TextPage, spec: TextSearchSpec): List<TextPageMatch> =
        (TextPageMatcher.find(page, spec) as TextPageMatchResult.Success).matches

    private fun failure(spec: TextSearchSpec): TextSearchError =
        (TextPageMatcher.find(page(line(0, "text")), spec) as TextPageMatchResult.Failure).error

    private fun page(vararg lines: TextLine) = TextPage(
        listOf(TextBlock(lines.toList(), 0)), TextSource.NATIVE_PDF
    )

    private fun line(lineIndex: Int, vararg words: String): TextLine {
        return TextLine(words.mapIndexed { index, text ->
            TextWord(text, PageSpaceRect(index / 10f + .1f, lineIndex / 10f + .1f, index / 10f + .2f, lineIndex / 10f + .15f), index)
        }, lineIndex)
    }

}
