package com.folium.reader.engine_mupdf

import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.StructuredTextWalker
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.text.TextBlock
import com.folium.reader.core.text.TextFont
import com.folium.reader.core.text.TextLine
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextWord
import java.text.Normalizer

internal fun extractNativeText(structuredText: StructuredText, pageBounds: Rect): TextPage {
    val geometry = NativePageGeometry(pageBounds)
    val walker = NativeTextWalker(geometry)
    structuredText.walk(walker)
    return walker.result()
}

private class NativePageGeometry(bounds: Rect) {
    private val left = bounds.x0
    private val top = bounds.y0
    private val width = bounds.x1 - bounds.x0
    private val height = bounds.y1 - bounds.y0

    init {
        if (!left.isFinite() || !top.isFinite() || !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) {
            throw PdfException(PdfFailure.Resource(retryable = false))
        }
    }

    fun normalize(quad: Quad): PageSpaceRect? {
        val xs = floatArrayOf(quad.ul_x, quad.ur_x, quad.ll_x, quad.lr_x)
        val ys = floatArrayOf(quad.ul_y, quad.ur_y, quad.ll_y, quad.lr_y)
        if (xs.any { !it.isFinite() } || ys.any { !it.isFinite() }) return null

        val normalizedLeft = ((xs.min() - left) / width).coerceIn(0f, 1f)
        val normalizedTop = ((ys.min() - top) / height).coerceIn(0f, 1f)
        val normalizedRight = ((xs.max() - left) / width).coerceIn(0f, 1f)
        val normalizedBottom = ((ys.max() - top) / height).coerceIn(0f, 1f)
        if (normalizedRight <= normalizedLeft || normalizedBottom <= normalizedTop) return null

        return PageSpaceRect(normalizedLeft, normalizedTop, normalizedRight, normalizedBottom)
    }
}

private data class NativeCharacter(val codePoint: Int, val box: PageSpaceRect?, val font: TextFont)

private class NativeTextWalker(private val geometry: NativePageGeometry) : StructuredTextWalker {
    private val blocks = mutableListOf<TextBlock>()
    private var lines = mutableListOf<TextLine>()
    private var words = mutableListOf<TextWord>()
    private var characters = mutableListOf<NativeCharacter>()

    override fun beginTextBlock(bbox: Rect, flags: Int) {
        lines = mutableListOf()
    }

    override fun endTextBlock() {
        finishLine()
        if (lines.isNotEmpty()) {
            blocks += TextBlock(lines.toList(), blocks.size)
            lines = mutableListOf()
        }
    }

    override fun beginLine(bbox: Rect, wmode: Int, dir: Point) {
        words = mutableListOf()
        characters = mutableListOf()
    }

    override fun endLine() = finishLine()

    override fun onChar(c: Int, origin: Point, font: Font, size: Float, q: Quad, argb: Int, flags: Int, bidi: Int) {
        MuPdfNativeOwnerTracker.fontCreated()
        try {
            if (!Character.isValidCodePoint(c)) return
            if (Character.isWhitespace(c)) {
                finishWord()
                return
            }

            characters += NativeCharacter(c, geometry.normalize(q), font.toTextFont())
        } finally {
            try {
                font.destroy()
            } finally {
                MuPdfNativeOwnerTracker.fontDestroyed()
            }
        }
    }

    override fun onImageBlock(bbox: Rect, transform: Matrix, image: Image) {
        MuPdfNativeOwnerTracker.imageCreated()
        try {
            image.destroy()
        } finally {
            MuPdfNativeOwnerTracker.imageDestroyed()
        }
    }

    override fun beginStruct(standard: String?, raw: String?, index: Int) = Unit
    override fun endStruct() = Unit
    override fun onVector(bbox: Rect, info: StructuredTextWalker.VectorInfo, argb: Int) = Unit

    fun result(): TextPage {
        finishLine()
        if (lines.isNotEmpty()) blocks += TextBlock(lines.toList(), blocks.size)
        return TextPage(blocks.toList(), TextSource.NATIVE_PDF)
    }

    private fun finishLine() {
        finishWord()
        if (words.isNotEmpty()) lines += TextLine(words.toList(), lines.size)
        words = mutableListOf()
    }

    private fun finishWord() {
        if (characters.isEmpty()) return

        val boxes = characters.mapNotNull(NativeCharacter::box)
        if (boxes.isNotEmpty()) {
            val text = characters.joinToString("") { String(Character.toChars(it.codePoint)) }.toNfc()
            words += TextWord(
                text = text,
                box = boxes.reduce(PageSpaceRect::union),
                readingOrder = words.size,
                fonts = characters.map(NativeCharacter::font).distinct()
            )
        }
        characters = mutableListOf()
    }
}

private fun Font.toTextFont(): TextFont = TextFont(
    name = name?.takeIf(String::isNotBlank)?.toNfc(),
    bold = isBold,
    italic = isItalic,
    serif = isSerif,
    monospaced = isMono
)

private fun PageSpaceRect.union(other: PageSpaceRect) = PageSpaceRect(
    left = minOf(left, other.left),
    top = minOf(top, other.top),
    right = maxOf(right, other.right),
    bottom = maxOf(bottom, other.bottom)
)

private fun String.toNfc(): String = Normalizer.normalize(this, Normalizer.Form.NFC)
