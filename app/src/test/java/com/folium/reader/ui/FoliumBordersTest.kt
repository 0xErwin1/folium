package com.folium.reader.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Dp.foliumStrokePx] rounds a Dp stroke to the nearest whole pixel instead of Compose's own
 * `ceil`, so a hairline and an ink rule keep their contrast on a density that is not a whole
 * number, and [foliumBorderGeometry] insets the rectangle a stroke of that width draws along.
 */
class FoliumBordersTest {

    @Test fun `rounds to the nearest pixel instead of always rounding up`() {
        assertEquals(1, 1.dp.foliumStrokePx(Density(1.0f)))
        assertEquals(1, 1.dp.foliumStrokePx(Density(1.0625f)))
        assertEquals(2, 1.dp.foliumStrokePx(Density(1.5f)))
        assertEquals(2, 1.dp.foliumStrokePx(Density(2.0f)))
        assertEquals(3, 1.dp.foliumStrokePx(Density(2.625f)))
        assertEquals(3, 1.dp.foliumStrokePx(Density(2.75f)))
        assertEquals(4, 1.dp.foliumStrokePx(Density(3.5f)))
        assertEquals(1, 1.dp.foliumStrokePx(Density(0.75f)))

        assertEquals(2, 2.dp.foliumStrokePx(Density(1.0f)))
        assertEquals(2, 2.dp.foliumStrokePx(Density(1.0625f)))
        assertEquals(3, 2.dp.foliumStrokePx(Density(1.5f)))
        assertEquals(4, 2.dp.foliumStrokePx(Density(2.0f)))
        assertEquals(5, 2.dp.foliumStrokePx(Density(2.625f)))
        assertEquals(6, 2.dp.foliumStrokePx(Density(2.75f)))
        assertEquals(7, 2.dp.foliumStrokePx(Density(3.5f)))
        assertEquals(2, 2.dp.foliumStrokePx(Density(0.75f)))
    }

    @Test fun `a hairline is never zero pixels wide`() {
        listOf(0.05f, 0.1f, 0.3f, 0.49f).forEach { density ->
            assertTrue(
                "a $density density hairline collapsed to zero pixels",
                1.dp.foliumStrokePx(Density(density)) >= 1
            )
        }
    }

    @Test fun `a 2dp rule is always strictly thicker than a 1dp hairline`() {
        listOf(1.0f, 1.0625f, 1.5f, 2.0f, 2.625f, 2.75f, 3.5f, 0.75f).forEach { density ->
            val hairline = 1.dp.foliumStrokePx(Density(density))
            val rule = 2.dp.foliumStrokePx(Density(density))
            assertTrue(
                "at density $density a 2dp rule ($rule px) was not thicker than a 1dp hairline ($hairline px)",
                rule > hairline
            )
        }
    }

    @Test fun `the stroked rectangle is inset by half the stroke width`() {
        val rect = foliumBorderGeometry(Size(width = 100f, height = 40f), strokePx = 2f)

        assertEquals(1f, rect.left)
        assertEquals(1f, rect.top)
        assertEquals(98f, rect.width)
        assertEquals(38f, rect.height)
    }

    @Test fun `an odd stroke width insets by a half pixel`() {
        val rect = foliumBorderGeometry(Size(width = 20f, height = 20f), strokePx = 1f)

        assertEquals(0.5f, rect.left)
        assertEquals(0.5f, rect.top)
        assertEquals(19f, rect.width)
        assertEquals(19f, rect.height)
    }

    @Test fun `an element smaller than twice the stroke centres a degenerate rectangle instead of going negative`() {
        val rect = foliumBorderGeometry(Size(width = 2f, height = 2f), strokePx = 4f)

        assertEquals(0f, rect.width)
        assertEquals(0f, rect.height)
        assertEquals(1f, rect.left)
        assertEquals(1f, rect.top)
    }

    @Test fun `a stroke exactly half the element's size touches but does not overshoot`() {
        val rect = foliumBorderGeometry(Size(width = 4f, height = 4f), strokePx = 2f)

        assertEquals(1f, rect.left)
        assertEquals(1f, rect.top)
        assertEquals(2f, rect.width)
        assertEquals(2f, rect.height)
    }
}
