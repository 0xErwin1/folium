package com.folium.reader.core.text

import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.core.pdf.PageSpaceRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextSelectionTest {
    private fun word(text: String, left: Float, top: Float, right: Float, bottom: Float, order: Int) =
        TextWord(text, PageSpaceRect(left, top, right, bottom), order)

    private val page = TextPage(
        listOf(
            TextBlock(
                listOf(
                    TextLine(listOf(word("One", .1f, .1f, .25f, .2f, 0), word("two", .3f, .1f, .45f, .2f, 1)), 0),
                    TextLine(listOf(word("three", .1f, .3f, .35f, .4f, 0)), 1)
                ),
                0
            ),
            TextBlock(listOf(TextLine(listOf(word("Four", .1f, .6f, .3f, .7f, 0)), 0)), 1)
        ),
        TextSource.NATIVE_PDF
    )
    private val policy = TextSelectionPolicy(page)

    @Test fun exactHitMissAndNearestAreDistinct() {
        assertEquals(0, policy.hit(PageSpacePoint(.2f, .15f)))
        assertNull(policy.hit(PageSpacePoint(.28f, .15f)))
        assertEquals(1, policy.nearest(PageSpacePoint(.28f, .15f)))
        assertEquals(3, policy.nearest(PageSpacePoint(1f, 1f)))
    }

    @Test fun overlapUsesSmallestBoxThenReadingOrder() {
        val overlap = TextPage(
            listOf(TextBlock(listOf(TextLine(listOf(
                word("large", .1f, .1f, .8f, .8f, 0),
                word("small", .2f, .2f, .4f, .4f, 1)
            ), 0)), 0)),
            TextSource.OCR
        )
        assertEquals(1, TextSelectionPolicy(overlap).hit(PageSpacePoint(.3f, .3f)))

        val tied = TextPage(
            listOf(TextBlock(listOf(TextLine(listOf(
                word("first", .1f, .1f, .4f, .4f, 0),
                word("second", .1f, .1f, .4f, .4f, 1)
            ), 0)), 0)),
            TextSource.OCR
        )
        assertEquals(0, TextSelectionPolicy(tied).hit(PageSpacePoint(.2f, .2f)))
    }

    @Test fun forwardReverseAndCrossedHandlesShareOneInclusiveRange() {
        assertEquals("One two\nthree", policy.selected(TextSelection(0, 2))?.text)
        assertEquals("One two\nthree", policy.selected(TextSelection(2, 0))?.text)

        val crossed = TextSelection(0, 1, SelectionEndpoint.ANCHOR).moveActiveTo(3)
        assertEquals(SelectionEndpoint.ANCHOR, crossed.activeEndpoint)
        assertEquals(1, crossed.firstWord)
        assertEquals(3, crossed.lastWord)
        assertEquals("two\nthree\n\nFour", policy.selected(crossed)?.text)
    }

    @Test fun copiedTextPreservesLineAndBlockSeparatorsAndOrderedBoxes() {
        val selected = policy.selected(TextSelection(1, 3))!!
        assertEquals("two\nthree\n\nFour", selected.text)
        assertEquals(listOf(page.words[1].box, page.words[2].box, page.words[3].box), selected.boxes)
    }

    @Test fun emptyPageNeverHitsOrSelects() {
        val empty = TextSelectionPolicy(TextPage(emptyList(), TextSource.NATIVE_PDF))
        assertNull(empty.hit(PageSpacePoint(.5f, .5f)))
        assertNull(empty.nearest(PageSpacePoint(.5f, .5f)))
        assertNull(empty.selected(TextSelection(0, 0)))
    }
}
