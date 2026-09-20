package com.folium.reader.ink

import com.folium.reader.core.ink.InkInputKind
import com.folium.reader.core.ink.InkSample
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.InkTip
import com.folium.reader.core.ink.InkTool
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.StrokeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialEraseSessionTest {

    private fun strokeId(seed: Int) = StrokeId("33333333-3333-3333-3333-33333333333$seed")

    private fun idGenerator(start: Int = 0): () -> StrokeId {
        var next = start
        return { strokeId(next++) }
    }

    private fun sequenceGenerator(start: Long = 100L): () -> Long {
        var next = start
        return { next++ }
    }

    private fun horizontalStroke(id: StrokeId, xs: List<Float>, widthSheetUnits: Float = 0.01f): InkStroke = InkStroke(
        id = id,
        tool = InkTool.PEN,
        tip = InkTip.BALLPOINT,
        colorArgb = 0,
        widthSheetUnits = widthSheetUnits,
        inputKind = InkInputKind.STYLUS,
        samples = xs.mapIndexed { index, x -> InkSample(x = x, y = 0f, elapsedMillis = index * 10) },
        sequence = 0
    )

    @Test
    fun aSinglePassSplitsOneStrokeIntoTwoFragments() {
        val original = horizontalStroke(strokeId(9), (0..10).map { it / 10f })
        val session = PartialEraseSession(listOf(original))
        val eraserSegment = listOf(SheetPoint(0.5f, -1f), SheetPoint(0.5f, 1f))

        val step = session.apply(eraserSegment, eraserRadius = 0.05f, newId = idGenerator(), newSequence = sequenceGenerator())

        assertEquals(listOf(original), step.removedNow)
        assertEquals(2, step.addedNow.size)

        val result = session.result()!!
        assertEquals(listOf(original), result.removed)
        assertEquals(step.addedNow, result.added)
    }

    @Test
    fun nothingTouchedLeavesTheStepEmptyAndTheResultNull() {
        val original = horizontalStroke(strokeId(9), (0..10).map { it / 10f })
        val session = PartialEraseSession(listOf(original))
        val farSegment = listOf(SheetPoint(0.5f, 100f), SheetPoint(0.5f, 101f))

        val step = session.apply(farSegment, eraserRadius = 0.01f, newId = idGenerator(), newSequence = sequenceGenerator())

        assertTrue(step.removedNow.isEmpty())
        assertTrue(step.addedNow.isEmpty())
        assertNull(session.result())
    }

    @Test
    fun erasingEverythingRemovesTheOriginalAndAddsNothing() {
        val original = horizontalStroke(strokeId(9), (0..10).map { it / 10f })
        val session = PartialEraseSession(listOf(original))
        val eraserSegment = listOf(SheetPoint(0.5f, -1f), SheetPoint(0.5f, 1f))

        session.apply(eraserSegment, eraserRadius = 2f, newId = idGenerator(), newSequence = sequenceGenerator())

        val result = session.result()!!
        assertEquals(listOf(original), result.removed)
        assertTrue(result.added.isEmpty())
    }

    @Test
    fun twoPassesOverTheSameStrokeYieldTheOriginalRemovedAndTheFinalFragmentsAdded() {
        val original = horizontalStroke(strokeId(9), (0..20).map { it / 20f })
        val session = PartialEraseSession(listOf(original))

        // First pass cuts a gap around x = 0.5, leaving two fragments: [0, 0.45] and [0.55, 1].
        session.apply(
            listOf(SheetPoint(0.5f, -1f), SheetPoint(0.5f, 1f)),
            eraserRadius = 0.05f,
            newId = idGenerator(start = 0),
            newSequence = sequenceGenerator(start = 100L)
        )

        // Second pass cuts the left fragment again, around x = 0.15, splitting it further.
        val secondStep = session.apply(
            listOf(SheetPoint(0.15f, -1f), SheetPoint(0.15f, 1f)),
            eraserRadius = 0.02f,
            newId = idGenerator(start = 10),
            newSequence = sequenceGenerator(start = 200L)
        )

        assertEquals(1, secondStep.removedNow.size)
        assertTrue(secondStep.removedNow.single().id != original.id)

        val result = session.result()!!
        assertEquals(listOf(original), result.removed)
        assertEquals(3, result.added.size)
        assertTrue(result.added.none { it.id == original.id })
    }

    @Test
    fun aFragmentCreatedThenErasedAgainInTheSameGestureAppearsInNeitherList() {
        val original = horizontalStroke(strokeId(9), (0..20).map { it / 20f })
        val session = PartialEraseSession(listOf(original))

        // First pass produces one fragment: [0.55, 1].
        session.apply(
            listOf(SheetPoint(0.5f, -1f), SheetPoint(0.5f, 1f)),
            eraserRadius = 0.05f,
            newId = idGenerator(start = 0),
            newSequence = sequenceGenerator(start = 100L)
        )

        // Second pass wipes that fragment out entirely.
        session.apply(
            listOf(SheetPoint(0.7f, -1f), SheetPoint(0.7f, 1f)),
            eraserRadius = 2f,
            newId = idGenerator(start = 10),
            newSequence = sequenceGenerator(start = 200L)
        )

        val result = session.result()!!
        assertEquals(listOf(original), result.removed)
        assertTrue(result.added.isEmpty())
    }

    @Test
    fun originalsReturnsTheGesturesStartingStrokes() {
        val a = horizontalStroke(strokeId(1), listOf(0f, 1f))
        val b = horizontalStroke(strokeId(2), listOf(2f, 3f))
        val session = PartialEraseSession(listOf(a, b))

        assertEquals(listOf(a, b), session.originals())
    }
}
