package com.folium.reader.core.text

import com.folium.reader.core.pdf.PageSpaceRect
import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import java.text.Normalizer
import java.util.Locale

private const val DEFAULT_SNIPPET_CHARS = 120

/** Engine-neutral match with geometry in the TextPage's original reading order. */
data class TextPageMatch(
    val wordRange: IntRange,
    val boxes: List<PageSpaceRect>,
    val snippet: String
)

/**
 * A query that has been validated and, for regex mode, compiled.
 *
 * Compiling is the expensive half of a search and it does not depend on the page: building the
 * pattern and preflighting it for zero-length matches costs the same whether one page is searched
 * or a thousand. Sweeping a document therefore compiles once and reuses the result, instead of
 * paying for a fresh compile and preflight on every page.
 */
sealed interface TextSearchProgram {
    data class Invalid(val error: TextSearchError) : TextSearchProgram

    class Compiled internal constructor(
        val spec: TextSearchSpec,
        private val pattern: Pattern?
    ) : TextSearchProgram {
        private val normalizedQuery: String =
            if (pattern == null) normalize(spec.query, spec.caseSensitive).trim() else ""

        fun find(
            page: TextPage,
            snippetChars: Int = DEFAULT_SNIPPET_CHARS,
            limit: Int = MAX_TEXT_SEARCH_RESULTS
        ): TextPageMatchResult {
            require(snippetChars > 0)
            require(limit >= 0)
            if (page.words.isEmpty() || spec.query.isBlank()) return TextPageMatchResult.Success(emptyList())

            val flattened = flatten(page, spec.caseSensitive)
            return if (pattern == null) {
                literal(flattened, page, snippetChars, limit)
            } else {
                regex(pattern, flattened, page, snippetChars, limit)
            }
        }

        /** Compact candidate check for persisted page text; geometry is still resolved by [find]. */
        fun contains(value: String): Boolean {
            if (spec.query.isBlank()) return false
            val normalized = normalize(value, spec.caseSensitive)
            return if (pattern == null) {
                containsLiteral(normalized)
            } else {
                containsPattern(pattern, normalized)
            }
        }

        private fun containsLiteral(normalized: String): Boolean {
            var from = 0
            while (from <= normalized.length - normalizedQuery.length) {
                val start = normalized.indexOf(normalizedQuery, from)
                if (start < 0) return false
                val end = start + normalizedQuery.length
                if (!spec.wholeWord || normalized.hasWholeWordBoundaries(start, end)) return true
                from = start + 1
            }
            return false
        }

        private fun containsPattern(pattern: Pattern, normalized: String): Boolean {
            val matcher = pattern.matcher(normalized)
            while (matcher.find()) {
                if (!spec.wholeWord || normalized.hasWholeWordBoundaries(matcher.start(), matcher.end())) {
                    return true
                }
            }
            return false
        }

        private fun literal(
            flattened: FlattenedText,
            page: TextPage,
            snippetChars: Int,
            limit: Int
        ): TextPageMatchResult {
            if (normalizedQuery.isEmpty()) return TextPageMatchResult.Success(emptyList())
            val matches = mutableListOf<TextPageMatch>()
            var truncated = false
            var from = 0
            while (from <= flattened.normalized.length - normalizedQuery.length) {
                val start = flattened.normalized.indexOf(normalizedQuery, from)
                if (start < 0) break
                val endExclusive = start + normalizedQuery.length
                if (!spec.wholeWord || flattened.hasWholeWordBoundaries(start, endExclusive)) {
                    if (matches.size == limit) {
                        truncated = true
                        break
                    }
                    flattened.toMatch(page, start, endExclusive - 1, snippetChars)?.let(matches::add)
                }
                from = start + 1
            }
            return TextPageMatchResult.Success(matches, truncated)
        }

        private fun regex(
            pattern: Pattern,
            flattened: FlattenedText,
            page: TextPage,
            snippetChars: Int,
            limit: Int
        ): TextPageMatchResult {
            val matcher = pattern.matcher(flattened.normalized)
            val matches = mutableListOf<TextPageMatch>()
            while (matcher.find()) {
                val start = matcher.start()
                val endExclusive = matcher.end()
                if (start == endExclusive) return TextPageMatchResult.Failure(TextSearchError.ZeroLengthPattern)
                if (!spec.wholeWord || flattened.hasWholeWordBoundaries(start, endExclusive)) {
                    if (matches.size == limit) return TextPageMatchResult.Success(matches, truncated = true)
                    flattened.toMatch(page, start, endExclusive - 1, snippetChars)?.let(matches::add)
                }
            }
            return TextPageMatchResult.Success(matches)
        }
    }
}

object TextPageMatcher {
    fun find(page: TextPage, query: String, snippetChars: Int = DEFAULT_SNIPPET_CHARS): List<TextPageMatch> =
        when (val result = find(page, TextSearchSpec(query), snippetChars)) {
            is TextPageMatchResult.Success -> result.matches
            is TextPageMatchResult.Failure -> emptyList()
        }

    fun find(
        page: TextPage,
        spec: TextSearchSpec,
        snippetChars: Int = DEFAULT_SNIPPET_CHARS,
        limit: Int = MAX_TEXT_SEARCH_RESULTS
    ): TextPageMatchResult = when (val program = compile(spec)) {
        is TextSearchProgram.Invalid -> {
            require(snippetChars > 0)
            require(limit >= 0)
            TextPageMatchResult.Failure(program.error)
        }
        is TextSearchProgram.Compiled -> program.find(page, snippetChars, limit)
    }

    /** Validates and, for regex mode, compiles [spec] once so a document sweep can reuse the result. */
    fun compile(spec: TextSearchSpec): TextSearchProgram {
        if (spec.query.length > MAX_TEXT_SEARCH_QUERY_LENGTH) {
            return TextSearchProgram.Invalid(TextSearchError.QueryTooLong)
        }
        if (spec.query.isBlank() || spec.mode == TextSearchMode.LITERAL) {
            return TextSearchProgram.Compiled(spec, pattern = null)
        }
        if (containsUnsupportedRegex(spec.query)) {
            return TextSearchProgram.Invalid(TextSearchError.UnsupportedPattern)
        }
        val pattern = try {
            compileRegex(spec)
        } catch (_: PatternSyntaxException) {
            return TextSearchProgram.Invalid(TextSearchError.InvalidPattern)
        }
        if (ZERO_LENGTH_PREFLIGHT.any { sample -> pattern.matcher(sample).run { find() && start() == end() } }) {
            return TextSearchProgram.Invalid(TextSearchError.ZeroLengthPattern)
        }
        return TextSearchProgram.Compiled(spec, pattern)
    }

    fun validate(spec: TextSearchSpec): TextSearchError? = (compile(spec) as? TextSearchProgram.Invalid)?.error

    /** Compact candidate check for persisted page text; geometry is still resolved by [find]. */
    fun contains(value: String, spec: TextSearchSpec): Boolean =
        (compile(spec) as? TextSearchProgram.Compiled)?.contains(value) == true

    /** Accent-insensitive, case-insensitive normalization used by literal candidate indexes. */
    fun normalizeLiteral(value: String): String = normalize(value, caseSensitive = false).trim()
}

private fun compileRegex(spec: TextSearchSpec): Pattern {
    val normalizedPattern = normalizePattern(spec.query)
    val flags = if (spec.caseSensitive) 0 else Pattern.CASE_INSENSITIVE
    return Pattern.compile(normalizedPattern, flags)
}

private fun flatten(page: TextPage, caseSensitive: Boolean): FlattenedText {
    val builder = MappingNormalizer(caseSensitive)
    page.words.forEachIndexed { wordIndex, word ->
        if (wordIndex > 0) builder.append(" ", null)
        builder.append(word.text, wordIndex)
    }
    return builder.build()
}

private fun snippet(flattened: FlattenedText, start: Int, end: Int, limit: Int): String {
    val original = flattened.original
    if (original.length <= limit) return original
    val sourceStart = flattened.sourceStarts[start]
    val sourceEnd = flattened.sourceEnds[end]
    val matchLength = sourceEnd - sourceStart
    val remaining = (limit - matchLength).coerceAtLeast(0)
    val snippetStart = (sourceStart - remaining / 2).coerceAtLeast(0).atCodePointStart(original)
    val snippetEnd = (snippetStart + limit).coerceAtMost(original.length).atCodePointEnd(original)
    val adjustedStart = (snippetEnd - limit).coerceAtLeast(0).atCodePointStart(original)
    return buildString {
        if (adjustedStart > 0) append('…')
        append(original.substring(adjustedStart, snippetEnd))
        if (snippetEnd < original.length) append('…')
    }
}

private fun FlattenedText.toMatch(
    page: TextPage,
    start: Int,
    end: Int,
    snippetChars: Int
): TextPageMatch? {
    val firstWord = wordAt(start, forward = true)
    val lastWord = wordAt(end, forward = false)
    if (firstWord == null || lastWord == null || firstWord > lastWord) return null
    val range = firstWord..lastWord
    return TextPageMatch(range, lineBands(page, range), snippet(this, start, end, snippetChars))
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

    fun hasWholeWordBoundaries(start: Int, endExclusive: Int): Boolean =
        !isWordCodePointBefore(start) && !isWordCodePointAt(endExclusive)

    private fun isWordCodePointBefore(index: Int): Boolean {
        if (index <= 0) return false
        val codePoint = normalized.codePointBefore(index)
        return isSearchWordCodePoint(codePoint)
    }

    private fun isWordCodePointAt(index: Int): Boolean {
        if (index >= normalized.length) return false
        return isSearchWordCodePoint(normalized.codePointAt(index))
    }
}

private class MappingNormalizer(private val caseSensitive: Boolean) {
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
            fold(codePoint, caseSensitive).forEachCodePoint { appendFolded(it, wordIndex, sourceStart, sourceEnd) }
            offset += Character.charCount(codePoint)
        }
    }

    fun build() = FlattenedText(normalized.toString(), original.toString(), words, sourceStarts, sourceEnds)

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

private fun normalize(value: String, caseSensitive: Boolean): String {
    val result = StringBuilder()
    var offset = 0
    var previousWhitespace = false
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        fold(codePoint, caseSensitive).forEachCodePoint { folded ->
            val whitespace = Character.isWhitespace(folded)
            if (whitespace) {
                if (!previousWhitespace) result.append(' ')
            } else result.appendCodePoint(folded)
            previousWhitespace = whitespace
        }
        offset += Character.charCount(codePoint)
    }
    return result.toString()
}

/** Strip accents without changing regex syntax; escaped literals are normalized like normal text. */
private fun normalizePattern(value: String): String = buildString {
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        append(fold(codePoint, caseSensitive = true))
        offset += Character.charCount(codePoint)
    }
}

private fun fold(codePoint: Int, caseSensitive: Boolean): String {
    val source = String(Character.toChars(codePoint))
    val cased = if (caseSensitive) source else source.uppercase(Locale.ROOT)
    return Normalizer.normalize(cased, Normalizer.Form.NFD)
        .filterNot { Character.getType(it) in COMBINING_MARK_TYPES }
}

private fun containsUnsupportedRegex(pattern: String): Boolean {
    var index = 0
    var inCharacterClass = false
    while (index < pattern.length) {
        val current = pattern[index]
        if (current == '\\') {
            val escaped = pattern.getOrNull(index + 1) ?: return false
            if (!inCharacterClass && (
                    escaped in '1'..'9' || escaped in "aAgGzZ" ||
                        escaped == 'k' && pattern.getOrNull(index + 2) in listOf('<', '{', '\'')
                    )) return true
            index += 2
            continue
        }
        if (current == '[' && !inCharacterClass) {
            inCharacterClass = true
            index++
            continue
        }
        if (current == ']' && inCharacterClass) {
            inCharacterClass = false
            index++
            continue
        }
        if (!inCharacterClass) {
            if (pattern.startsWith("(?=", index) || pattern.startsWith("(?!", index) ||
                pattern.startsWith("(?<=", index) || pattern.startsWith("(?<!", index) ||
                pattern.startsWith("(?>", index) || pattern.startsWith("(?(", index) ||
                pattern.startsWith("(?|", index) || pattern.startsWith("(?R", index)
            ) return true
            if (current == '+' && index > 0 && (
                    pattern[index - 1] in "?*+" ||
                        pattern[index - 1] == '}' && pattern.hasNumericQuantifierEndingAt(index - 1)
                    )) return true
        }
        index++
    }
    return false
}

private fun String.hasNumericQuantifierEndingAt(end: Int): Boolean {
    val start = lastIndexOf('{', end)
    if (start < 0) return false
    return substring(start + 1, end).matches(Regex("\\d+(?:,\\d*)?"))
}

private val ZERO_LENGTH_PREFLIGHT = listOf("", "a", " ", "0", "word boundary", "\n")

private fun isSearchWordCodePoint(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(),
    Character.TITLECASE_LETTER.toInt(), Character.MODIFIER_LETTER.toInt(),
    Character.OTHER_LETTER.toInt(), Character.DECIMAL_DIGIT_NUMBER.toInt(),
    Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(), Character.CONNECTOR_PUNCTUATION.toInt() -> true
    else -> false
}

private fun String.hasWholeWordBoundaries(start: Int, endExclusive: Int): Boolean {
    val before = start > 0 && isSearchWordCodePoint(codePointBefore(start))
    val after = endExclusive < length && isSearchWordCodePoint(codePointAt(endExclusive))
    return !before && !after
}

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
