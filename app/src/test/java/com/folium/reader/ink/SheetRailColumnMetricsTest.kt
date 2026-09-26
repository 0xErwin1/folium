package com.folium.reader.ink

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a column rail fits the height it is given: the design's 60dp cells while they fit, shorter cells
 * down to 48dp — with the padding and gaps shrinking in proportion — and, below that, a tool list that
 * scrolls while the foot stays pinned.
 */
class SheetRailColumnMetricsTest {

    private val toolsAndFoot = 9

    @Test fun `the natural rail with its whole foot needs 605dp`() {
        assertEquals(605f, railColumnNaturalHeight(60.dp, toolsAndFoot).value, 0.001f)
    }

    @Test fun `a tall enough slot keeps the design's cells`() {
        assertEquals(NaturalRailColumnMetrics, railColumnMetrics(700.dp, toolsAndFoot))
        assertEquals(NaturalRailColumnMetrics, railColumnMetrics(605.dp, toolsAndFoot))
    }

    @Test fun `a shorter slot shrinks the cells, the padding and the gaps together`() {
        val metrics = railColumnMetrics(545.8.dp, toolsAndFoot)

        assertEquals(54f, metrics.cellHeight.value, 0.001f)
        assertEquals(3.6f, metrics.cellGap.value, 0.001f)
        assertEquals(7.2f, metrics.verticalPadding.value, 0.001f)
        assertFalse(metrics.toolsScroll)
    }

    @Test fun `shrunk cells never overflow the slot they were fitted to`() {
        listOf(520.dp, 546.dp, 560.dp, 590.dp).forEach { available ->
            val metrics = railColumnMetrics(available, toolsAndFoot)

            assertTrue(railColumnNaturalHeight(metrics.cellHeight, toolsAndFoot) <= available)
        }
    }

    @Test fun `below 48dp cells the tools scroll at 48dp`() {
        val metrics = railColumnMetrics(400.dp, toolsAndFoot)

        assertEquals(48f, metrics.cellHeight.value, 0.001f)
        assertEquals(3.2f, metrics.cellGap.value, 0.001f)
        assertEquals(6.4f, metrics.verticalPadding.value, 0.001f)
        assertTrue(metrics.toolsScroll)
    }

    @Test fun `a foot without new sheet needs one cell less`() {
        assertEquals(541f, railColumnNaturalHeight(60.dp, toolsAndFoot - 1).value, 0.001f)
        assertEquals(NaturalRailColumnMetrics, railColumnMetrics(541.dp, toolsAndFoot - 1))
    }

    @Test fun `a panel anchors to its tool at the cell height the rail is drawn with`() {
        val metrics = railColumnMetrics(545.8.dp, toolsAndFoot)

        assertEquals(
            (7.2f + (54f + 3.6f) * 2) - 10f,
            railAnchorCellTopOffset(railHidden = false, tool = SheetRailTool.HIGHLIGHT, metrics = metrics, toolScroll = 10.dp).value,
            0.001f
        )
        assertEquals(
            7.2f + 54f / 2,
            railAnchorConnectorTopOffset(railHidden = false, tool = SheetRailTool.VIEW, metrics = metrics).value,
            0.001f
        )
    }
}
