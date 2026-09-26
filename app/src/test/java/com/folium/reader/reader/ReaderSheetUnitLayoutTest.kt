package com.folium.reader.reader

import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.sequence.SequenceItem
import com.folium.reader.core.sequence.SpreadUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the reader lays out a unit that shows a sheet: the slim chrome it draws over it, and the two
 * cells of a book page beside its sheet, bounded by the same insets and split by a ruled gap.
 */
class ReaderSheetUnitLayoutTest {

    private val sheet = SequenceItem.Sheet(SheetId("sheet-a"), pageIndex = 3, ordinal = 1)

    @Test fun `a unit of book pages keeps the book chrome`() {
        assertEquals(ReaderChromeStyle.BOOK, readerChromeStyle(null))
        assertEquals(ReaderChromeStyle.BOOK, readerChromeStyle(SpreadUnit(SequenceItem.Page(3), null)))
        assertEquals(ReaderChromeStyle.BOOK, readerChromeStyle(SpreadUnit(SequenceItem.Page(3), SequenceItem.Page(4))))
    }

    @Test fun `a unit with a sheet in either cell draws the slim sheet chrome`() {
        assertEquals(ReaderChromeStyle.SHEET, readerChromeStyle(SpreadUnit(sheet, null)))
        assertEquals(ReaderChromeStyle.SHEET, readerChromeStyle(SpreadUnit(SequenceItem.Page(3), sheet)))
        assertEquals(ReaderChromeStyle.SHEET, readerChromeStyle(SpreadUnit(sheet, SequenceItem.Page(4))))
    }

    @Test fun `a book page and its sheet split the width around a ruled gap`() {
        val geometry = sheetSpreadGeometry(widthPx = 1000, heightPx = 600, gapPx = 32, insets = SheetCellInsets(90f, 70f))

        assertEquals(
            SheetSpreadGeometry(
                pageLeftPx = 0,
                pageWidthPx = 484,
                ruleCenterPx = 500f,
                sheetLeftPx = 516,
                sheetWidthPx = 484,
                topPx = 90,
                heightPx = 440
            ),
            geometry
        )
    }

    @Test fun `an odd pixel of width goes to the sheet`() {
        val geometry = sheetSpreadGeometry(widthPx = 1001, heightPx = 600, gapPx = 32, insets = SheetCellInsets(0f, 0f))

        assertEquals(484, geometry.pageWidthPx)
        assertEquals(516, geometry.sheetLeftPx)
        assertEquals(485, geometry.sheetWidthPx)
        assertEquals(600, geometry.heightPx)
    }

    @Test fun `insets taller than the area leave cells with no height rather than a negative one`() {
        val geometry = sheetSpreadGeometry(widthPx = 1000, heightPx = 100, gapPx = 32, insets = SheetCellInsets(80f, 80f))

        assertEquals(0, geometry.heightPx)
    }

    @Test fun `a tall page fits the cell's height, centred across and at the top`() {
        val layout = sheetSpreadPageLayout(ReaderViewport(484, 440), pageAspect = 0.7f)

        assertEquals(308f, layout.pageWidth, 0.01f)
        assertEquals(440f, layout.pageHeight, 0.01f)
        assertEquals(88f, layout.originX, 0.01f)
        assertEquals(0f, layout.originY, 0.01f)
    }

    @Test fun `a wide page fits the cell's width and sits at the top rather than centred`() {
        val layout = sheetSpreadPageLayout(ReaderViewport(484, 440), pageAspect = 1.5f)

        assertEquals(484f, layout.pageWidth, 0.01f)
        assertEquals(322.67f, layout.pageHeight, 0.01f)
        assertEquals(0f, layout.originX, 0.01f)
        assertEquals(0f, layout.originY, 0.01f)
    }
}
