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

    @Test fun toolbarIsCenteredAboveTheActiveBand() {
        assertEquals(
            SelectionToolbarPlacement(126f, 94f),
            place(
                viewportWidth = 300f,
                viewportHeight = 300f,
                anchor = ViewportPoint(150f, 170f),
                activeBand = ViewportRect(130f, 150f, 40f, 20f)
            )
        )
    }

    @Test fun toolbarClampsAtTheLeftAndRightViewportMargins() {
        assertEquals(
            8f,
            place(
                viewportWidth = 300f,
                viewportHeight = 300f,
                anchor = ViewportPoint(10f, 170f),
                activeBand = ViewportRect(0f, 150f, 20f, 20f)
            )?.left
        )
        assertEquals(
            244f,
            place(
                viewportWidth = 300f,
                viewportHeight = 300f,
                anchor = ViewportPoint(295f, 170f),
                activeBand = ViewportRect(280f, 150f, 20f, 20f)
            )?.left
        )
    }

    @Test fun toolbarFallsBelowWhenThereIsNoRoomAbove() {
        val band = ViewportRect(0f, 20f, 300f, 20f)

        assertEquals(
            SelectionToolbarPlacement(126f, 48f),
            place(
                viewportWidth = 300f,
                viewportHeight = 300f,
                anchor = ViewportPoint(150f, 40f),
                activeBand = band,
                selectedBands = listOf(band)
            )
        )
    }

    @Test fun toolbarFallsBelowWhenVisibleChromeOccludesThePreferredPosition() {
        val band = ViewportRect(130f, 80f, 40f, 20f)

        assertEquals(
            SelectionToolbarPlacement(126f, 108f),
            place(
                viewportWidth = 300f,
                viewportHeight = 300f,
                anchor = ViewportPoint(150f, 100f),
                activeBand = band,
                minimumTop = 68f
            )
        )
    }

    @Test fun toolbarStaysInsideTheBottomViewportMargin() {
        val placement = place(
            viewportWidth = 300f,
            viewportHeight = 180f,
            anchor = ViewportPoint(150f, 178f),
            activeBand = ViewportRect(130f, 160f, 40f, 18f)
        )

        requireNotNull(placement)
        assertEquals(true, placement.top in 8f..124f)
    }

    @Test fun toolbarNeverIntersectsAnySelectedBand() {
        val previousLine = ViewportRect(0f, 90f, 300f, 40f)
        val activeLine = ViewportRect(0f, 150f, 300f, 20f)
        val placement = requireNotNull(place(
            viewportWidth = 300f,
            viewportHeight = 300f,
            anchor = ViewportPoint(150f, 170f),
            activeBand = activeLine,
            selectedBands = listOf(previousLine, activeLine)
        ))

        assertEquals(178f, placement.top)
        listOf(previousLine, activeLine).forEach { band ->
            assertEquals(false, intersects(placement, band))
        }
    }

    private fun place(
        viewportWidth: Float,
        viewportHeight: Float,
        anchor: ViewportPoint,
        activeBand: ViewportRect,
        selectedBands: List<ViewportRect> = listOf(activeBand),
        minimumTop: Float = 8f
    ) = selectionToolbarPlacement(
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        anchor = anchor,
        activeBand = activeBand,
        selectedBands = selectedBands,
        toolbarSize = 48f,
        gap = 8f,
        margin = 8f,
        minimumTop = minimumTop
    )

    private fun intersects(placement: SelectionToolbarPlacement, band: ViewportRect): Boolean =
        placement.left < band.left + band.width &&
            placement.left + 48f > band.left &&
            placement.top < band.top + band.height &&
            placement.top + 48f > band.top

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
