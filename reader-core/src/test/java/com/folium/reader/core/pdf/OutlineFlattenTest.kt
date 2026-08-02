package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineFlattenTest {

    @Test fun emptyOutlineFlattensToNoRows() {
        assertTrue(flattenOutline(emptyList()).isEmpty())
    }

    @Test fun siblingsFlattenInOrderAtDepthZero() {
        val entries = listOf(
            OutlineEntry("Chapter 1", 0),
            OutlineEntry("Chapter 2", 10)
        )
        val rows = flattenOutline(entries)
        assertEquals(listOf(OutlineRow("Chapter 1", 0, 0), OutlineRow("Chapter 2", 10, 0)), rows)
    }

    @Test fun childrenAreDepthFirstAndOneDeeperThanTheirParent() {
        val entries = listOf(
            OutlineEntry(
                "Part I",
                pageIndex = 0,
                children = listOf(OutlineEntry("Chapter 1", 1), OutlineEntry("Chapter 2", 5))
            ),
            OutlineEntry("Part II", pageIndex = 10)
        )
        val rows = flattenOutline(entries)
        assertEquals(
            listOf(
                OutlineRow("Part I", 0, 0),
                OutlineRow("Chapter 1", 1, 1),
                OutlineRow("Chapter 2", 5, 1),
                OutlineRow("Part II", 10, 0)
            ),
            rows
        )
    }

    @Test fun entriesWithNoResolvablePageAreKeptAsNullPageRows() {
        val entries = listOf(OutlineEntry("Heading only", pageIndex = null, children = listOf(OutlineEntry("Child", 3))))
        val rows = flattenOutline(entries)
        assertEquals(listOf(OutlineRow("Heading only", null, 0), OutlineRow("Child", 3, 1)), rows)
    }

    @Test fun descentStopsAtMaxDepth() {
        fun nested(depth: Int): OutlineEntry =
            if (depth == 0) OutlineEntry("Leaf", 0) else OutlineEntry("Level $depth", null, listOf(nested(depth - 1)))

        val rows = flattenOutline(listOf(nested(5)), maxDepth = 2)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.depth <= 2 })
    }

    @Test fun deeplyCyclicShapedChainTerminatesRatherThanOverflowing() {
        fun chain(depth: Int): OutlineEntry =
            if (depth == 0) OutlineEntry("Leaf", 0) else OutlineEntry("Node", null, listOf(chain(depth - 1)))

        val rows = flattenOutline(listOf(chain(500)), maxDepth = 32)
        assertEquals(33, rows.size)
    }

    @Test fun outlineEntryRejectsANegativePageIndex() {
        try {
            OutlineEntry("Bad", -1)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
