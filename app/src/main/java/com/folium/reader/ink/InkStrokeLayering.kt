package com.folium.reader.ink

import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.InkTool
import com.folium.reader.core.ink.SheetItem

/**
 * [strokes] ordered so every [InkTool.HIGHLIGHTER] stroke draws before every other stroke,
 * regardless of [InkStroke.sequence], and each of those two groups draws in its own sequence order —
 * so a highlight always sits under the writing it marks, never over it, whatever order the two were
 * actually drawn in. Shared by [InkCommittedStrokesView] and [SheetThumbnailGeometry], the two places
 * that draw a sheet's dry strokes.
 */
internal fun layeredForDraw(strokes: List<InkStroke>): List<InkStroke> {
    val (highlighters, rest) = strokes.partition { it.tool == InkTool.HIGHLIGHTER }
    return highlighters.sortedBy { it.sequence } + rest.sortedBy { it.sequence }
}

/**
 * [layeredForDraw]'s own rule, generalised to a mix of strokes and text boxes through [SheetItem]: a
 * highlighter stroke stays underneath every other item, text boxes included, and every other item —
 * a non-highlighter stroke or a text box — draws in one shared [SheetItem.sequence] order regardless
 * of kind.
 */
internal fun layeredItemsForDraw(items: List<SheetItem>): List<SheetItem> {
    val (highlighters, rest) = items.partition { it is SheetItem.Stroke && it.stroke.tool == InkTool.HIGHLIGHTER }
    return highlighters.sortedBy { it.sequence } + rest.sortedBy { it.sequence }
}
