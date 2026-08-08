package com.folium.reader.reader

import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.text.TextBlock
import com.folium.reader.core.text.TextLine
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSelection
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageTextSelectionTest {
    private fun textPage(): TextPage = TextPage(
        listOf(TextBlock(listOf(TextLine(listOf(
            TextWord("same", PageSpaceRect(.1f, .1f, .3f, .2f), 0)
        ), 0)), 0)),
        TextSource.NATIVE_PDF
    )

    @Test fun oldRangeIsNeverExposedForANewPageEvenWhenCachedTextArrivesImmediately() {
        val oldText = textPage()
        val oldSelection = PageTextSelection(0, oldText, TextSelection(0, 0))
        val cachedNewText = textPage()

        assertNull(oldSelection.rangeFor(pageIndex = 1, textPage = cachedNewText))
    }

    @Test fun equalButDistinctTextPageIdentityInvalidatesWordIndicesSynchronously() {
        val oldText = textPage()
        val reloadedEqualText = textPage()
        assertEquals(oldText, reloadedEqualText)

        val selection = PageTextSelection(2, oldText, TextSelection(0, 0))

        assertNull(selection.rangeFor(pageIndex = 2, textPage = reloadedEqualText))
        assertEquals(TextSelection(0, 0), selection.rangeFor(pageIndex = 2, textPage = oldText))
    }
}
