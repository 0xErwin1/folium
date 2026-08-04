package com.folium.reader.reader

import com.folium.reader.core.pdf.OutlineRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decisions behind the jump dialog and the contents list that do not need a screen: which typed
 * entries name a page, what the field is allowed to hold, and what an entry the document left
 * untitled shows instead.
 */
class ReaderNavigationTest {

    @Test fun `a page inside the document is answered as a zero-based index`() {
        assertEquals(0, jumpTargetPage("1", pageCount = 500))
        assertEquals(299, jumpTargetPage("300", pageCount = 500))
        assertEquals(499, jumpTargetPage("500", pageCount = 500))
    }

    @Test fun `surrounding whitespace does not stop an entry naming a page`() {
        assertEquals(41, jumpTargetPage("  42  ", pageCount = 500))
    }

    @Test fun `a page outside the document names nothing rather than being clamped into it`() {
        assertNull(jumpTargetPage("0", pageCount = 500))
        assertNull(jumpTargetPage("501", pageCount = 500))
        assertNull(jumpTargetPage("9999", pageCount = 500))
    }

    @Test fun `an entry that is not a page number names nothing`() {
        assertNull(jumpTargetPage("", pageCount = 500))
        assertNull(jumpTargetPage("   ", pageCount = 500))
        assertNull(jumpTargetPage("twelve", pageCount = 500))
        assertNull(jumpTargetPage("1.5", pageCount = 500))
        assertNull(jumpTargetPage("-3", pageCount = 500))
        assertNull(jumpTargetPage("99999999999999999999", pageCount = 500))
    }

    @Test fun `the entry keeps only digits and never more of them than the last page needs`() {
        assertEquals("42", sanitizeJumpEntry("4a2", pageCount = 500))
        assertEquals("", sanitizeJumpEntry("twelve", pageCount = 500))
        assertEquals("123", sanitizeJumpEntry("123456", pageCount = 500))
        assertEquals("1234", sanitizeJumpEntry("123456", pageCount = 1000))
    }

    @Test fun `leading zeros are dropped before the width cap so they do not eat the budget`() {
        assertEquals("500", sanitizeJumpEntry("0500", pageCount = 500))
        assertEquals("42", sanitizeJumpEntry("0042", pageCount = 500))
        assertEquals("0", sanitizeJumpEntry("0", pageCount = 500))
        assertEquals("0", sanitizeJumpEntry("00", pageCount = 500))
        assertEquals("", sanitizeJumpEntry("", pageCount = 500))
    }

    @Test fun `an outline entry the document left untitled keeps its place under a placeholder`() {
        assertEquals("Chapter 1", contentsRowTitle("Chapter 1", placeholder = "—"))
        assertEquals("Chapter 1", contentsRowTitle("  Chapter 1  ", placeholder = "—"))
        assertEquals("—", contentsRowTitle("", placeholder = "—"))
        assertEquals("—", contentsRowTitle("   ", placeholder = "—"))
    }

    @Test fun `the active section is the last navigable row at or before the current page`() {
        val rows = listOf(
            OutlineRow("Chapter 1", 0, 0),
            OutlineRow("Topic", 4, 1),
            OutlineRow("Chapter 2", 10, 0)
        )

        assertEquals(0, activeContentsRowIndex(rows, currentPage = 3))
        assertEquals(1, activeContentsRowIndex(rows, currentPage = 4))
        assertEquals(1, activeContentsRowIndex(rows, currentPage = 9))
        assertEquals(2, activeContentsRowIndex(rows, currentPage = 10))
    }

    @Test fun `unresolved rows never become active`() {
        val rows = listOf(
            OutlineRow("Part I", null, 0),
            OutlineRow("Chapter 1", 2, 1),
            OutlineRow("Part II", null, 0)
        )

        assertNull(activeContentsRowIndex(rows, currentPage = 1))
        assertEquals(1, activeContentsRowIndex(rows, currentPage = 20))
    }

    @Test fun `the later outline row wins when destinations are duplicated`() {
        val rows = listOf(
            OutlineRow("Part I", 0, 0),
            OutlineRow("Chapter 1", 0, 1),
            OutlineRow("Topic", 0, 2)
        )

        assertEquals(2, activeContentsRowIndex(rows, currentPage = 0))
    }

    @Test fun `outline order wins even when destinations are not sorted`() {
        val rows = listOf(
            OutlineRow("Late chapter", 20, 0),
            OutlineRow("Earlier appendix", 5, 0)
        )

        assertEquals(1, activeContentsRowIndex(rows, currentPage = 20))
    }

    @Test fun `separate roots have no continuation lanes and do not connect`() {
        val tree = contentsTreeRows(rows(0, 0))

        assertEquals(
            listOf(
                ContentsTreeRow(0, emptyList(), false, false),
                ContentsTreeRow(0, emptyList(), true, false)
            ),
            tree
        )
    }

    @Test fun `siblings continue until the last sibling closes at its node`() {
        val tree = contentsTreeRows(rows(0, 1, 1, 1))

        assertEquals(false, tree[1].isLastSibling)
        assertEquals(false, tree[2].isLastSibling)
        assertEquals(true, tree[3].isLastSibling)
    }

    @Test fun `a parent reports children and its first child starts the branch`() {
        val tree = contentsTreeRows(rows(0, 1, 2))

        assertEquals(true, tree[0].hasChildren)
        assertEquals(true, tree[1].hasChildren)
        assertEquals(false, tree[2].hasChildren)
    }

    @Test fun `only unfinished ancestors continue through descendant rows`() {
        val tree = contentsTreeRows(rows(0, 1, 2, 1, 2, 0))

        assertEquals(listOf(true), tree[2].ancestorContinuations)
        assertEquals(emptyList<Boolean>(), tree[3].ancestorContinuations)
        assertEquals(listOf(false), tree[4].ancestorContinuations)

        val withFollowingRootChild = contentsTreeRows(rows(0, 1, 2, 1))
        assertEquals(listOf(true), withFollowingRootChild[2].ancestorContinuations)
    }

    @Test fun `null destinations have exactly the same topology as navigable rows`() {
        val navigable = rows(0, 1, 1)
        val unresolved = navigable.mapIndexed { index, row ->
            if (index == 1) row.copy(pageIndex = null) else row
        }

        assertEquals(contentsTreeRows(navigable), contentsTreeRows(unresolved))
    }

    @Test fun `depth is capped and deeper rows become siblings at the cap`() {
        val tree = contentsTreeRows(rows(0, 1, 2, 3, 4, 5, 6, 4, 0))

        assertEquals(listOf(0, 1, 2, 3, 4, 4, 4, 4, 0), tree.map { it.depth })
        assertEquals(false, tree[4].isLastSibling)
        assertEquals(false, tree[5].isLastSibling)
        assertEquals(false, tree[6].isLastSibling)
        assertEquals(true, tree[7].isLastSibling)
        assertEquals(false, tree[4].hasChildren)
    }

    private fun rows(vararg depths: Int): List<OutlineRow> = depths.mapIndexed { index, depth ->
        OutlineRow("Row $index", index, depth)
    }
}
