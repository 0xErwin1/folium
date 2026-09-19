package com.folium.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FoliumSheetSettleTest {

    private val anchors = mapOf(
        FoliumSheetAnchor.EXPANDED to 0f,
        FoliumSheetAnchor.HALF to 400f,
        FoliumSheetAnchor.HIDDEN to 800f
    )

    private fun target(current: FoliumSheetAnchor, offset: Float, velocity: Float = 0f) =
        FoliumSheetSettle.target(current, anchors, offset, velocity, velocityThresholdPxPerSecond = 1000f)

    @Test fun `a slow release from expanded past halfway to half settles at half, never hidden`() {
        assertEquals(FoliumSheetAnchor.HALF, target(FoliumSheetAnchor.EXPANDED, offset = 700f))
    }

    @Test fun `a slow release from expanded short of halfway to half returns to expanded`() {
        assertEquals(FoliumSheetAnchor.EXPANDED, target(FoliumSheetAnchor.EXPANDED, offset = 100f))
    }

    @Test fun `a slow release from half past halfway toward hidden settles at hidden`() {
        assertEquals(FoliumSheetAnchor.HIDDEN, target(FoliumSheetAnchor.HALF, offset = 700f))
    }

    @Test fun `a slow release from half toward expanded settles at expanded`() {
        assertEquals(FoliumSheetAnchor.EXPANDED, target(FoliumSheetAnchor.HALF, offset = 50f))
    }

    @Test fun `a small movement returns to the anchor the drag started at`() {
        assertEquals(FoliumSheetAnchor.HALF, target(FoliumSheetAnchor.HALF, offset = 410f))
    }

    @Test fun `a fast downward fling wins over a position close to the starting anchor`() {
        assertEquals(FoliumSheetAnchor.HALF, target(FoliumSheetAnchor.EXPANDED, offset = 10f, velocity = 1500f))
    }

    @Test fun `a fast upward fling wins over a position close to the starting anchor`() {
        assertEquals(FoliumSheetAnchor.HALF, target(FoliumSheetAnchor.HIDDEN, offset = 790f, velocity = -1500f))
    }

    @Test fun `a fling never skips past the immediate neighbour`() {
        assertEquals(FoliumSheetAnchor.HALF, target(FoliumSheetAnchor.EXPANDED, offset = 700f, velocity = 1500f))
    }

    @Test fun `there is no neighbour left to fling toward, so the sheet stays put`() {
        assertEquals(FoliumSheetAnchor.HIDDEN, target(FoliumSheetAnchor.HIDDEN, offset = 800f, velocity = 1500f))
    }
}
