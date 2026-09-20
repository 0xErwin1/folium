package com.folium.reader.ink

import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.InkTool

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
