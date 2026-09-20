package com.folium.reader.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EPSILON = 1e-3f

class InkPartialEraserTest {

    private fun strokeId(seed: Int) = StrokeId("22222222-2222-2222-2222-22222222222$seed")

    private fun idGenerator(): () -> StrokeId {
        var next = 0
        return { strokeId(next++) }
    }

    private fun sequenceGenerator(start: Long = 100L): () -> Long {
        var next = start
        return { next++ }
    }

    private fun horizontalStroke(
        xs: List<Float>,
        widthSheetUnits: Float = 0.01f,
        tool: InkTool = InkTool.PEN,
        elapsedStepMillis: Int = 10,
        pressures: List<Float>? = null
    ): InkStroke = InkStroke(
        id = strokeId(9),
        tool = tool,
        tip = InkTip.BALLPOINT,
        colorArgb = 0,
        widthSheetUnits = widthSheetUnits,
        inputKind = InkInputKind.STYLUS,
        samples = xs.mapIndexed { index, x ->
            InkSample(x = x, y = 0f, elapsedMillis = index * elapsedStepMillis, pressure = pressures?.get(index))
        },
        sequence = 0
    )

    @Test
    fun anUntouchedStrokeReturnsNull() {
        val stroke = horizontalStroke(listOf(0f, 1f))
        val eraserPath = listOf(SheetPoint(0.5f, 100f), SheetPoint(0.5f, 101f))
        val result = erasePartially(stroke, eraserPath, eraserRadius = 0.01f, newId = idGenerator(), newSequence = sequenceGenerator())
        assertNull(result)
    }

    @Test
    fun anEraserThroughTheMiddleProducesTwoFragmentsMeetingTheEraserRadius() {
        val xs = (0..10).map { it / 10f }
        val stroke = horizontalStroke(xs, widthSheetUnits = 0.01f)
        val eraserRadius = 0.05f
        val eraserPath = listOf(SheetPoint(0.5f, -1f), SheetPoint(0.5f, 1f))
        val threshold = eraserRadius + stroke.widthSheetUnits / 2f

        val fragments = erasePartially(stroke, eraserPath, eraserRadius, idGenerator(), sequenceGenerator())
        assertNotNull(fragments)
        assertEquals(2, fragments!!.size)

        val first = fragments[0]
        val second = fragments[1]

        assertEquals(0f, first.samples.first().x, EPSILON)
        assertEquals(1f, second.samples.last().x, EPSILON)

        assertEquals(threshold, Math.abs(first.samples.last().x - 0.5f), EPSILON)
        assertEquals(threshold, Math.abs(second.samples.first().x - 0.5f), EPSILON)
    }

    @Test
    fun anEraserOverOneEndProducesOneFragment() {
        val xs = (0..10).map { it / 10f }
        val stroke = horizontalStroke(xs, widthSheetUnits = 0.01f)
        val eraserRadius = 0.2f
        val eraserPath = listOf(SheetPoint(1f, -1f), SheetPoint(1f, 1f))

        val fragments = erasePartially(stroke, eraserPath, eraserRadius, idGenerator(), sequenceGenerator())
        assertNotNull(fragments)
        assertEquals(1, fragments!!.size)
        assertEquals(0f, fragments[0].samples.first().x, EPSILON)
    }

    @Test
    fun anEraserCoveringTheWholeStrokeReturnsAnEmptyList() {
        val xs = (0..10).map { it / 10f }
        val stroke = horizontalStroke(xs, widthSheetUnits = 0.01f)
        val eraserPath = listOf(SheetPoint(0.5f, -1f), SheetPoint(0.5f, 1f))

        val fragments = erasePartially(stroke, eraserPath, eraserRadius = 2f, idGenerator(), sequenceGenerator())
        assertNotNull(fragments)
        assertTrue(fragments!!.isEmpty())
    }

    @Test
    fun aClosedSelfCrossingStrokeSplitsOnItsOwnPathRatherThanOnGeometry() {
        // A square loop, finely sampled, whose bottom and top edges both pass through x = 0.5 at
        // different points along the stroke's own path: a self-crossing shape when read as geometry,
        // even though nothing here actually crosses in parameter order.
        val bottom = (0..10).map { SheetPoint(it / 10f, 0f) }
        val right = (1..10).map { SheetPoint(1f, it / 10f) }
        val top = (9 downTo 0).map { SheetPoint(it / 10f, 1f) }
        val left = (9 downTo 0).map { SheetPoint(0f, it / 10f) }
        val loop = bottom + right + top + left

        val stroke = InkStroke(
            id = strokeId(3), tool = InkTool.PEN, tip = InkTip.BALLPOINT, colorArgb = 0,
            widthSheetUnits = 0.02f, inputKind = InkInputKind.STYLUS,
            samples = loop.mapIndexed { index, p -> InkSample(p.x, p.y, index * 10) },
            sequence = 0
        )

        // A vertical eraser line through x = 0.5 only reaches the bottom and top edges (the sides sit
        // 0.5 sheet units away), biting one point out of each: two separate gaps in the stroke's own
        // parameter order, splitting the loop into three fragments rather than the two a planar "inside
        // the square" reading might expect.
        val eraserPath = listOf(SheetPoint(0.5f, -1f), SheetPoint(0.5f, 2f))
        val fragments = erasePartially(stroke, eraserPath, eraserRadius = 0.05f, idGenerator(), sequenceGenerator())

        assertNotNull(fragments)
        assertEquals(3, fragments!!.size)
        assertEquals(SheetPoint(0f, 0f), fragments[0].samples.first().let { SheetPoint(it.x, it.y) })
        assertEquals(SheetPoint(0f, 0f), fragments[2].samples.last().let { SheetPoint(it.x, it.y) })
    }

    @Test
    fun interpolatedSamplesLieBetweenTheirNeighboursAndFragmentTimingStartsAtZeroAndIncreases() {
        val stroke = horizontalStroke(
            listOf(0f, 0.6f),
            widthSheetUnits = 0.02f,
            elapsedStepMillis = 100,
            pressures = listOf(0.2f, 0.8f)
        )
        val eraserPath = listOf(SheetPoint(0.6f, -1f), SheetPoint(0.6f, 1f))

        val fragments = erasePartially(stroke, eraserPath, eraserRadius = 0f, idGenerator(), sequenceGenerator())
        assertNotNull(fragments)
        assertEquals(1, fragments!!.size)

        val samples = fragments[0].samples
        assertEquals(2, samples.size)
        assertEquals(0, samples[0].elapsedMillis)
        assertTrue(samples[1].elapsedMillis in 0..100)
        assertTrue(samples[1].elapsedMillis > samples[0].elapsedMillis)

        val pressure = samples[1].pressure!!
        assertTrue(pressure > 0.2f && pressure < 0.8f)
    }

    @Test
    fun aFragmentShorterThanTheStrokeWidthIsDroppedAsACrumbWhileALongerOneSurvives() {
        val stroke = horizontalStroke(listOf(0f, 0.01f, 0.5f, 0.99f, 3.0f), widthSheetUnits = 0.4f)
        val eraserPath = listOf(SheetPoint(0.5f, -1f), SheetPoint(0.5f, 1f))

        val fragments = erasePartially(stroke, eraserPath, eraserRadius = 0f, idGenerator(), sequenceGenerator())
        assertNotNull(fragments)
        assertEquals(1, fragments!!.size)
        assertEquals(3.0f, fragments[0].samples.last().x, EPSILON)
    }

    @Test
    fun aMultiPointEraserPathSplitsAStrokeTheSameWayAStraightOneWould() {
        val xs = (0..10).map { it / 10f }
        val stroke = horizontalStroke(xs, widthSheetUnits = 0.01f)
        val eraserRadius = 0.05f
        val bentEraserPath = listOf(SheetPoint(0.5f, -1f), SheetPoint(0.5f, 0.5f), SheetPoint(2f, 0.5f))

        val fragments = erasePartially(stroke, bentEraserPath, eraserRadius, idGenerator(), sequenceGenerator())
        assertNotNull(fragments)
        assertEquals(2, fragments!!.size)
        assertEquals(0f, fragments[0].samples.first().x, EPSILON)
        assertEquals(1f, fragments[1].samples.last().x, EPSILON)
    }

    @Test
    fun theHighlighterToolIsPreservedAcrossFragments() {
        val stroke = horizontalStroke(listOf(0f, 0.6f), widthSheetUnits = 0.02f, tool = InkTool.HIGHLIGHTER)
        val eraserPath = listOf(SheetPoint(0.6f, -1f), SheetPoint(0.6f, 1f))

        val fragments = erasePartially(stroke, eraserPath, eraserRadius = 0f, idGenerator(), sequenceGenerator())
        assertNotNull(fragments)
        assertEquals(1, fragments!!.size)
        assertEquals(InkTool.HIGHLIGHTER, fragments[0].tool)
    }

    @Test
    fun fragmentsGetFreshIdsAndSequences() {
        val stroke = horizontalStroke((0..10).map { it / 10f }, widthSheetUnits = 0.01f)
        val eraserPath = listOf(SheetPoint(0.5f, -1f), SheetPoint(0.5f, 1f))

        val fragments = erasePartially(stroke, eraserPath, eraserRadius = 0.05f, idGenerator(), sequenceGenerator(start = 500L))
        assertNotNull(fragments)
        assertEquals(2, fragments!!.size)
        assertTrue(fragments.all { it.id != stroke.id })
        assertEquals(listOf(500L, 501L), fragments.map { it.sequence })
    }

    @Test fun `an eraser crossing the middle of a two-sample line cuts it in two`() {
        val line = InkStroke(
            id = StrokeId("line"),
            tool = InkTool.PEN,
            tip = InkTip.BALLPOINT,
            colorArgb = 0xFF000000.toInt(),
            widthSheetUnits = 0.002f,
            inputKind = InkInputKind.FINGER,
            samples = listOf(InkSample(0.1f, 0.5f, 0), InkSample(0.9f, 0.5f, 3200)),
            sequence = 1
        )
        var next = 100L

        val fragments = erasePartially(line, listOf(SheetPoint(0.5f, 0.4f), SheetPoint(0.5f, 0.6f)), 0.01f, { StrokeId("f" + next) }, { next++ })

        assertEquals(2, fragments!!.size)
        assertEquals(0.1f, fragments[0].samples.first().x, 1e-4f)
        assertEquals(0.5f - 0.011f, fragments[0].samples.last().x, 1e-3f)
        assertEquals(0.5f + 0.011f, fragments[1].samples.first().x, 1e-3f)
        assertEquals(0.9f, fragments[1].samples.last().x, 1e-4f)
    }
}
