package com.folium.reader.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetGeometryTest {

    @Test
    fun aSheetPointAllowsNegativeCoordinatesOnEitherAxis() {
        val point = SheetPoint(-3000.5f, -1f)
        assertEquals(-3000.5f, point.x)
        assertEquals(-1f, point.y)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSheetRectWithRightBeforeLeftIsRejected() {
        SheetRect(left = 0.5f, top = 0f, right = 0.4f, bottom = 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSheetRectWithBottomBeforeTopIsRejected() {
        SheetRect(left = 0f, top = 0.5f, right = 1f, bottom = 0.4f)
    }

    @Test
    fun overlappingRectsIntersect() {
        val a = SheetRect(0f, 0f, 1f, 1f)
        val b = SheetRect(0.5f, 0.5f, 1.5f, 1.5f)
        assertTrue(a.intersects(b))
        assertTrue(b.intersects(a))
    }

    @Test
    fun touchingEdgesDoNotIntersect() {
        val a = SheetRect(0f, 0f, 1f, 1f)
        val b = SheetRect(1f, 0f, 2f, 1f)
        assertFalse(a.intersects(b))
    }

    @Test
    fun disjointRectsDoNotIntersect() {
        val a = SheetRect(0f, 0f, 1f, 1f)
        val b = SheetRect(2f, 2f, 3f, 3f)
        assertFalse(a.intersects(b))
    }

    @Test
    fun containsIsInclusiveOfEdges() {
        val rect = SheetRect(0f, 0f, 1f, 1f)
        assertTrue(rect.contains(SheetPoint(0f, 0f)))
        assertTrue(rect.contains(SheetPoint(1f, 1f)))
        assertFalse(rect.contains(SheetPoint(1.001f, 0.5f)))
    }

    @Test
    fun unionCoversBothRects() {
        val a = SheetRect(0f, 0f, 1f, 1f)
        val b = SheetRect(2f, 3f, 4f, 5f)
        val union = a.union(b)
        assertEquals(SheetRect(0f, 0f, 4f, 5f), union)
    }

    @Test
    fun inflateGrowsEverySideByTheGivenAmount() {
        val rect = SheetRect(1f, 1f, 2f, 2f)
        val inflated = rect.inflate(0.5f)
        assertEquals(SheetRect(0.5f, 0.5f, 2.5f, 2.5f), inflated)
    }

    @Test
    fun inflateByANegativeAmountNeverInvertsTheRect() {
        val rect = SheetRect(1f, 1f, 2f, 2f)
        val inflated = rect.inflate(-10f)
        assertTrue(inflated.width >= 0f)
        assertTrue(inflated.height >= 0f)
    }
}
