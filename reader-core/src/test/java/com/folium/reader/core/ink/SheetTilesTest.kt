package com.folium.reader.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetTilesTest {

    private fun strokeAt(bounds: SheetRect, id: String = "11111111-1111-1111-1111-111111111111"): InkStroke {
        val width = 0.01f
        val half = width / 2f
        return InkStroke(
            StrokeId(id), InkTool.PEN, InkTip.BALLPOINT, colorArgb = 0,
            widthSheetUnits = width, inputKind = InkInputKind.STYLUS,
            samples = listOf(
                InkSample(bounds.left + half, bounds.top + half, 0),
                InkSample(bounds.right - half, bounds.bottom - half, 10)
            ),
            sequence = 0
        )
    }

    @Test
    fun colAndRowForUseFloorSemanticsOnNegativeCoordinates() {
        assertEquals(-1, SheetTiles.colFor(-0.5f))
        assertEquals(-1, SheetTiles.rowFor(-0.001f))
        assertEquals(0, SheetTiles.colFor(0f))
        assertEquals(2, SheetTiles.colFor(2.5f))
    }

    @Test
    fun tilesCoveringASingleTileReturnsExactlyThatTile() {
        val tiles = SheetTiles.tilesCovering(SheetRect(0f, 0f, 1f, 1f))
        assertEquals(listOf(TileKey(0, 0)), tiles)
    }

    @Test
    fun tilesCoveringExcludesTilesStartingExactlyAtTheRectEdge() {
        val tiles = SheetTiles.tilesCovering(SheetRect(0f, 0f, 2f, 1f)).toSet()
        assertEquals(setOf(TileKey(0, 0), TileKey(1, 0)), tiles)
    }

    @Test
    fun tilesCoveringADegenerateRectIsASingleTile() {
        val tiles = SheetTiles.tilesCovering(SheetRect(2.5f, -3.5f, 2.5f, -3.5f))
        assertEquals(listOf(TileKey(2, -4)), tiles)
    }

    @Test
    fun tilesCoveringNegativeSpaceUsesFloorSemantics() {
        val tiles = SheetTiles.tilesCovering(SheetRect(-1.5f, -1.5f, -0.5f, -0.5f)).toSet()
        assertEquals(setOf(TileKey(-2, -2), TileKey(-1, -2), TileKey(-2, -1), TileKey(-1, -1)), tiles)
    }

    @Test
    fun tilesCoveringAWideRectSpansManyTiles() {
        val tiles = SheetTiles.tilesCovering(SheetRect(-2f, -1f, 3f, 1f)).toSet()
        assertEquals(5 * 2, tiles.size)
        assertTrue(tiles.contains(TileKey(-2, -1)))
        assertTrue(tiles.contains(TileKey(2, 0)))
    }

    @Test
    fun strokesIntersectingReturnsOnlyOverlappingStrokes() {
        val inside = strokeAt(SheetRect(-1f, -1f, 0f, 0f))
        val outside = strokeAt(SheetRect(10f, 10f, 11f, 11f))
        val result = strokesIntersecting(listOf(inside, outside), SheetRect(-2f, -2f, 0.5f, 0.5f))
        assertEquals(listOf(inside), result)
    }

    @Test
    fun sheetContentBoundsUnionsEveryStroke() {
        val a = strokeAt(SheetRect(-5f, -5f, -4f, -4f))
        val b = strokeAt(SheetRect(1f, 1f, 2f, 2f))
        assertEquals(SheetRect(-5f, -5f, 2f, 2f), sheetContentBounds(listOf(a, b)))
    }

    @Test
    fun sheetContentBoundsOfNoStrokesIsNull() {
        assertEquals(null, sheetContentBounds(emptyList()))
    }
}
