package com.folium.reader.core.ink

import kotlin.math.floor

/** One cell of [SheetTiles]'s grid, identified by its column and row rather than a coordinate. */
data class TileKey(val col: Int, val row: Int)

/** Strokes among [strokes] whose [InkStroke.bounds] overlap [rect] at all. */
fun strokesIntersecting(strokes: List<InkStroke>, rect: SheetRect): List<InkStroke> =
    strokes.filter { it.bounds.intersects(rect) }

/** The smallest [SheetRect] containing every stroke's [InkStroke.bounds], or null for an empty sheet. */
fun sheetContentBounds(strokes: List<InkStroke>): SheetRect? =
    strokes.map { it.bounds }.reduceOrNull(SheetRect::union)

/**
 * Splits sheet space into a fixed-size grid for storage and lazy loading, so a caller can page in
 * only the strokes near where a reader is currently looking rather than a whole sheet — or a whole
 * infinite whiteboard — at once.
 *
 * A tile is one sheet unit on each side, the same unit [SheetPoint] already uses, which keeps a
 * tile the same physical footprint on either axis and needs no extra configuration to pick a size.
 * Tile `(col, row)` covers the half-open square `[col, col + 1) x [row, row + 1)`; a coordinate
 * that lands exactly on a boundary belongs to the tile above or to the right of it along that axis,
 * not the one below or to the left — the same floor-based convention on both positive and negative
 * coordinates, so a tile grid centered on the origin behaves the same on every side of it.
 */
object SheetTiles {
    const val TILE_SIZE: Float = 1f

    fun colFor(x: Float): Int = floor(x / TILE_SIZE).toInt()
    fun rowFor(y: Float): Int = floor(y / TILE_SIZE).toInt()

    /** Every [TileKey] any part of [rect] falls into. */
    fun tilesCovering(rect: SheetRect): List<TileKey> {
        val cols = inclusiveIndexRange(rect.left, rect.right, ::colFor)
        val rows = inclusiveIndexRange(rect.top, rect.bottom, ::rowFor)
        return rows.flatMap { row -> cols.map { col -> TileKey(col, row) } }
    }

    /**
     * The inclusive range of tile indexes the half-open span `[start, end)` touches along one axis,
     * or exactly [start]'s own tile when [start] equals [end]. [end] sitting exactly on a tile
     * boundary belongs to the next tile, so it does not pull the range one tile further than the
     * span actually reaches.
     */
    private fun inclusiveIndexRange(start: Float, end: Float, indexFor: (Float) -> Int): IntRange {
        val first = indexFor(start)
        if (end == start) return first..first

        val endIndex = indexFor(end)
        val last = if (end == endIndex * TILE_SIZE) endIndex - 1 else endIndex
        return first..maxOf(first, last)
    }
}
