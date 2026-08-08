package com.folium.reader.reader

import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.text.TextBlock
import com.folium.reader.core.text.TextLine
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSelection
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextWord
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSelectionOverlayTest {
    @Test fun selectionBandsJoinEveryWordOnOneOriginalLineEvenAcrossALargeHorizontalSpace() {
        val page = textPage(
            block(line(
                word("one", .10f, .20f, .20f, .30f, 0),
                word("two", .24f, .21f, .36f, .31f, 1),
                word("three", .50f, .20f, .64f, .30f, 2)
            ))
        )

        assertEquals(
            listOf(
                PageSpaceRect(.10f, .20f, .64f, .31f)
            ),
            selectionBands(page, TextSelection(0, 2))
        )
    }

    @Test fun selectionBandsKeepLinesAndBlocksSeparateEvenWhenTheirBoxesOverlap() {
        val page = textPage(
            block(
                line(word("line one", .10f, .20f, .30f, .30f, 0)),
                line(word("line two", .28f, .25f, .48f, .35f, 0))
            ),
            block(line(word("block two", .46f, .25f, .66f, .35f, 0)))
        )

        assertEquals(
            listOf(
                PageSpaceRect(.10f, .20f, .30f, .30f),
                PageSpaceRect(.28f, .25f, .48f, .35f),
                PageSpaceRect(.46f, .25f, .66f, .35f)
            ),
            selectionBands(page, TextSelection(0, 2))
        )
    }

    private fun textPage(vararg blocks: TextBlock) = TextPage(
        blocks.mapIndexed { index, block -> block.copy(readingOrder = index) },
        TextSource.NATIVE_PDF
    )

    private fun block(vararg lines: TextLine) = TextBlock(
        lines.mapIndexed { index, line -> line.copy(readingOrder = index) },
        0
    )

    private fun line(vararg words: TextWord) = TextLine(words.toList(), 0)

    private fun word(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        index: Int
    ) = TextWord(text, PageSpaceRect(left, top, right, bottom), index)
}
