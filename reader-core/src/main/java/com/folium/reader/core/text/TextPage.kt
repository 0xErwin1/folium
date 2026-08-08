package com.folium.reader.core.text

import com.folium.reader.core.pdf.PageSpaceRect
import java.text.Normalizer

enum class TextSource { NATIVE_PDF, OCR }

/** Stable, adapter-neutral identity for text extraction behavior and its data inputs. */
@JvmInline
value class TextEngineVersion(val value: String) {
    init { require(value.isNotBlank() && value.none(Char::isISOControl)) }
}

data class TextFont(
    val name: String?,
    val bold: Boolean,
    val italic: Boolean,
    val serif: Boolean,
    val monospaced: Boolean
) {
    init {
        require(name == null || (name.isNotBlank() && name.isNfc()))
    }
}

data class TextWord(
    val text: String,
    val box: PageSpaceRect,
    val readingOrder: Int,
    val fonts: List<TextFont> = emptyList(),
    val languageTag: String? = null,
    val confidence: Float? = null
) {
    init {
        require(text.isNotBlank() && text.isNfc())
        require(readingOrder >= 0)
        require(fonts.distinct() == fonts)
        require(languageTag == null || languageTag.isNotBlank())
        require(confidence == null || confidence in 0f..1f)
    }
}

data class TextLine(val words: List<TextWord>, val readingOrder: Int) {
    init {
        require(words.isNotEmpty())
        require(readingOrder >= 0)
        require(words.map(TextWord::readingOrder) == words.indices.toList())
    }

    val box: PageSpaceRect = words.map(TextWord::box).reduce(PageSpaceRect::union)
    val text: String = words.joinToString(" ", transform = TextWord::text)
}

data class TextBlock(val lines: List<TextLine>, val readingOrder: Int) {
    init {
        require(lines.isNotEmpty())
        require(readingOrder >= 0)
        require(lines.map(TextLine::readingOrder) == lines.indices.toList())
    }

    val box: PageSpaceRect = lines.map(TextLine::box).reduce(PageSpaceRect::union)
    val text: String = lines.joinToString("\n", transform = TextLine::text)
}

data class TextPage(val blocks: List<TextBlock>, val source: TextSource) {
    init {
        require(blocks.map(TextBlock::readingOrder) == blocks.indices.toList())
    }

    val lines: List<TextLine> = blocks.flatMap(TextBlock::lines)
    val words: List<TextWord> = lines.flatMap(TextLine::words)
    val text: String = blocks.joinToString("\n\n", transform = TextBlock::text)
}

private fun PageSpaceRect.union(other: PageSpaceRect) = PageSpaceRect(
    left = minOf(left, other.left),
    top = minOf(top, other.top),
    right = maxOf(right, other.right),
    bottom = maxOf(bottom, other.bottom)
)

private fun String.isNfc(): Boolean = this == Normalizer.normalize(this, Normalizer.Form.NFC)
