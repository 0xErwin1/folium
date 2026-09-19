package com.folium.reader.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LayoutRetentionTest {

    @Test fun noLayoutsEvictsNothing() {
        assertEquals(emptyList<String>(), layoutsBeyondRetention(emptyList()))
    }

    @Test fun oneLayoutIsAlwaysKept() {
        assertEquals(emptyList<String>(), layoutsBeyondRetention(listOf("layout-a")))
    }

    @Test fun twoLayoutsAreBothKept() {
        assertEquals(emptyList<String>(), layoutsBeyondRetention(listOf("layout-b", "layout-a")))
    }

    @Test fun aThirdLayoutEvictsOnlyTheLeastRecentlyUsedOne() {
        assertEquals(
            listOf("layout-a"),
            layoutsBeyondRetention(listOf("layout-c", "layout-b", "layout-a"))
        )
    }

    @Test fun everyLayoutBeyondTheTwoMostRecentIsEvicted() {
        assertEquals(
            listOf("layout-b", "layout-a"),
            layoutsBeyondRetention(listOf("layout-d", "layout-c", "layout-b", "layout-a"))
        )
    }

    /**
     * The layout in use is always the caller-supplied head of [layoutsBeyondRetention]'s input,
     * since [RoomTextPageIndex.retainRecentLayouts] records it as most recent before ever computing
     * this list — so it can never appear in the result regardless of how many other layouts exist.
     */
    @Test fun theMostRecentLayoutIsNeverEvictedNoMatterHowManyOthersExist() {
        val manyOlderLayouts = (0 until 20).map { "layout-$it" }
        val ordered = listOf("current-layout") + manyOlderLayouts

        val evicted = layoutsBeyondRetention(ordered)

        assertEquals(false, "current-layout" in evicted)
        assertEquals(manyOlderLayouts.size - 1, evicted.size)
    }

    @Test fun retainOfZeroEvictsEveryLayoutIncludingTheMostRecent() {
        assertEquals(
            listOf("layout-b", "layout-a"),
            layoutsBeyondRetention(listOf("layout-b", "layout-a"), retain = 0)
        )
    }

    @Test fun negativeRetainIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            layoutsBeyondRetention(listOf("layout-a"), retain = -1)
        }
    }
}
