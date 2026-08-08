package com.folium.reader.core.text

import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.core.pdf.PageSpaceRect
import kotlin.math.pow

enum class SelectionEndpoint { ANCHOR, FOCUS }

/** An inclusive, single-page word range. Anchor and focus retain their identity when they cross. */
data class TextSelection(
    val anchorWord: Int,
    val focusWord: Int,
    val activeEndpoint: SelectionEndpoint = SelectionEndpoint.FOCUS
) {
    init {
        require(anchorWord >= 0 && focusWord >= 0)
    }

    val firstWord: Int get() = minOf(anchorWord, focusWord)
    val lastWord: Int get() = maxOf(anchorWord, focusWord)

    fun moveActiveTo(wordIndex: Int): TextSelection {
        require(wordIndex >= 0)
        return when (activeEndpoint) {
            SelectionEndpoint.ANCHOR -> copy(anchorWord = wordIndex)
            SelectionEndpoint.FOCUS -> copy(focusWord = wordIndex)
        }
    }

    fun withActiveEndpoint(endpoint: SelectionEndpoint): TextSelection = copy(activeEndpoint = endpoint)
}

data class SelectedText(val text: String, val boxes: List<PageSpaceRect>)

/** Engine-neutral hit testing and inclusive word-range policy for a [TextPage]. */
class TextSelectionPolicy(private val page: TextPage) {
    private data class OrderedWord(
        val word: TextWord,
        val blockIndex: Int,
        val lineIndex: Int
    )

    private val orderedWords = page.blocks.flatMapIndexed { blockIndex, block ->
        block.lines.flatMapIndexed { lineIndex, line ->
            line.words.map { OrderedWord(it, blockIndex, lineIndex) }
        }
    }

    fun hit(point: PageSpacePoint): Int? = orderedWords.indices
        .filter { orderedWords[it].word.box.contains(point) }
        .minWithOrNull(compareBy<Int> { orderedWords[it].word.box.area }.thenBy { it })

    fun nearest(point: PageSpacePoint): Int? = orderedWords.indices.minWithOrNull(
        compareBy<Int> { orderedWords[it].word.box.distanceSquaredTo(point) }.thenBy { it }
    )

    fun selectWord(point: PageSpacePoint): TextSelection? = hit(point)?.let { TextSelection(it, it) }

    fun selected(selection: TextSelection): SelectedText? {
        if (selection.firstWord !in orderedWords.indices || selection.lastWord !in orderedWords.indices) return null

        val words = orderedWords.subList(selection.firstWord, selection.lastWord + 1)
        val text = buildString {
            words.forEachIndexed { index, current ->
                if (index > 0) append(separator(words[index - 1], current))
                append(current.word.text)
            }
        }
        return SelectedText(text, words.map { it.word.box })
    }

    fun endpointBox(selection: TextSelection, endpoint: SelectionEndpoint): PageSpaceRect? {
        val index = when (endpoint) {
            SelectionEndpoint.ANCHOR -> selection.anchorWord
            SelectionEndpoint.FOCUS -> selection.focusWord
        }
        return orderedWords.getOrNull(index)?.word?.box
    }

    private fun separator(previous: OrderedWord, current: OrderedWord): String = when {
        previous.blockIndex != current.blockIndex -> "\n\n"
        previous.lineIndex != current.lineIndex -> "\n"
        else -> " "
    }
}

private val PageSpaceRect.area: Float get() = (right - left) * (bottom - top)

private fun PageSpaceRect.contains(point: PageSpacePoint): Boolean =
    point.x in left..right && point.y in top..bottom

private fun PageSpaceRect.distanceSquaredTo(point: PageSpacePoint): Float {
    val nearestX = point.x.coerceIn(left, right)
    val nearestY = point.y.coerceIn(top, bottom)
    return (point.x - nearestX).pow(2) + (point.y - nearestY).pow(2)
}
