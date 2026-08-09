package com.folium.reader.core.text

import com.folium.reader.core.pdf.PageSpaceRect
import java.text.Normalizer
import java.util.Locale

private const val DEFAULT_SNIPPET_CHARS = 120

/** Engine-neutral literal match with geometry in the TextPage's original reading order. */
data class TextPageMatch(
    val wordRange: IntRange,
    val boxes: List<PageSpaceRect>,
    val snippet: String
)

object TextPageMatcher {
    fun find(page: TextPage, query: String, snippetChars: Int = DEFAULT_SNIPPET_CHARS): List<TextPageMatch> {
        require(snippetChars > 0)
        val normalizedQuery = normalize(query).trim()
        if (normalizedQuery.isEmpty() || page.words.isEmpty()) return emptyList()

        val flattened = flatten(page)
        val results = mutableListOf<TextPageMatch>()
        var from = 0
        while (from <= flattened.normalized.length - normalizedQuery.length) {
            val start = flattened.normalized.indexOf(normalizedQuery, from)
            if (start < 0) break
            val end = start + normalizedQuery.length - 1
            val firstWord = flattened.wordAt(start, forward = true)
            val lastWord = flattened.wordAt(end, forward = false)
            if (firstWord != null && lastWord != null && firstWord <= lastWord) {
                val range = firstWord..lastWord
                results += TextPageMatch(
                    range,
                    lineBands(page, range),
                    snippet(flattened, start, end, snippetChars)
                )
            }
            from = start + 1
        }
        return results
    }

    fun normalizeLiteral(value: String): String = normalize(value).trim()

    private fun flatten(page: TextPage): FlattenedText {
        val builder = MappingNormalizer()
        page.words.forEachIndexed { wordIndex, word ->
            if (wordIndex > 0) builder.append(" ", null)
            builder.append(word.text, wordIndex)
        }
        return builder.build()
    }

    private fun normalize(value: String): String {
        val result = StringBuilder()
        var offset = 0
        var previousWhitespace = false
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            fold(codePoint).forEachCodePoint { folded ->
                val whitespace = Character.isWhitespace(folded)
                if (whitespace) {
                    if (!previousWhitespace) result.append(' ')
                } else {
                    result.appendCodePoint(folded)
                }
                previousWhitespace = whitespace
            }
            offset += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun lineBands(page: TextPage, range: IntRange): List<PageSpaceRect> {
        var flattenedWord = 0
        val bands = mutableListOf<PageSpaceRect>()
        page.lines.forEach { line ->
            val boxes = line.words.mapNotNull { word ->
                val include = flattenedWord in range
                flattenedWord++
                word.box.takeIf { include }
            }
            if (boxes.isNotEmpty()) bands += boxes.reduce(PageSpaceRect::union)
        }
        return bands
    }

    private fun snippet(flattened: FlattenedText, start: Int, end: Int, limit: Int): String {
        val original = flattened.original
        if (original.length <= limit) return original
        val sourceStart = flattened.sourceStarts[start]
        val sourceEnd = flattened.sourceEnds[end]
        val matchLength = sourceEnd - sourceStart
        val remaining = (limit - matchLength).coerceAtLeast(0)
        val snippetStart = (sourceStart - remaining / 2).coerceAtLeast(0).atCodePointStart(original)
        val snippetEnd = (snippetStart + limit).coerceAtMost(original.length)
            .atCodePointEnd(original)
        val adjustedStart = (snippetEnd - limit).coerceAtLeast(0).atCodePointStart(original)
        return buildString {
            if (adjustedStart > 0) append('…')
            append(original.substring(adjustedStart, snippetEnd))
            if (snippetEnd < original.length) append('…')
        }
    }
}

private data class FlattenedText(
    val normalized: String,
    val original: String,
    val words: List<Int?>,
    val sourceStarts: List<Int>,
    val sourceEnds: List<Int>
) {
    fun wordAt(index: Int, forward: Boolean): Int? {
        if (index !in words.indices) return null
        val indices = if (forward) index..words.lastIndex else index downTo 0
        return indices.firstNotNullOfOrNull { words[it] }
    }
}

private class MappingNormalizer {
    private val normalized = StringBuilder()
    private val original = StringBuilder()
    private val words = mutableListOf<Int?>()
    private val sourceStarts = mutableListOf<Int>()
    private val sourceEnds = mutableListOf<Int>()
    private var previousWhitespace = false

    fun append(value: String, wordIndex: Int?) {
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val sourceStart = original.length
            original.appendCodePoint(codePoint)
            val sourceEnd = original.length
            fold(codePoint).forEachCodePoint { appendFolded(it, wordIndex, sourceStart, sourceEnd) }
            offset += Character.charCount(codePoint)
        }
    }

    fun build() = FlattenedText(
        normalized.toString(), original.toString(), words, sourceStarts, sourceEnds
    )

    private fun appendFolded(codePoint: Int, wordIndex: Int?, sourceStart: Int, sourceEnd: Int) {
        val whitespace = Character.isWhitespace(codePoint)
        if (whitespace && previousWhitespace) return
        val value = if (whitespace) " " else String(Character.toChars(codePoint))
        normalized.append(value)
        repeat(value.length) {
            words += wordIndex
            sourceStarts += sourceStart
            sourceEnds += sourceEnd
        }
        previousWhitespace = whitespace
    }
}

private fun fold(codePoint: Int): String = Normalizer.normalize(
    String(Character.toChars(codePoint)).uppercase(Locale.ROOT),
    Normalizer.Form.NFD
).filterNot { Character.getType(it) in COMBINING_MARK_TYPES }

private inline fun String.forEachCodePoint(action: (Int) -> Unit) {
    var offset = 0
    while (offset < length) {
        val codePoint = codePointAt(offset)
        action(codePoint)
        offset += Character.charCount(codePoint)
    }
}

private fun Int.atCodePointStart(value: String): Int =
    if (this in 1 until value.length && Character.isLowSurrogate(value[this])) this - 1 else this

private fun Int.atCodePointEnd(value: String): Int =
    if (this in 1 until value.length && Character.isHighSurrogate(value[this - 1])) this + 1 else this

private val COMBINING_MARK_TYPES = setOf(
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt()
)

private fun PageSpaceRect.union(other: PageSpaceRect) = PageSpaceRect(
    minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom)
)
