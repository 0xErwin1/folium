package com.folium.reader.ui

import androidx.compose.ui.unit.Dp

/**
 * How much room a screen has, in the three bands the design system draws for.
 *
 * Derived from the width the layout is actually given rather than from the device, so a phone in
 * landscape, a tablet in portrait and a window sharing a foldable all get the layout their size
 * earns. That also means the boundaries belong here, next to the grid that uses them, instead of
 * arriving from a library whose bands the design would then have to follow.
 */
enum class FoliumWidthClass {
    /** One column of content. The list and the detail take turns on the whole screen. */
    COMPACT,

    /** Still one column, but wide enough that a cover spans two grid modules instead of one. */
    MEDIUM,

    /** Two panes at once: the shelf keeps the left, a chosen book keeps the right. */
    EXPANDED;

    val columns: Int
        get() = when (this) {
            COMPACT -> FoliumGrid.COMPACT_COLUMNS
            MEDIUM -> FoliumGrid.MEDIUM_COLUMNS
            EXPANDED -> FoliumGrid.EXPANDED_COLUMNS
        }

    val margin: Dp
        get() = when (this) {
            COMPACT -> FoliumGrid.compactMargin
            MEDIUM -> FoliumGrid.mediumMargin
            EXPANDED -> FoliumGrid.expandedMargin
        }

    val gutter: Dp
        get() = when (this) {
            COMPACT -> FoliumGrid.compactGutter
            MEDIUM -> FoliumGrid.mediumGutter
            EXPANDED -> FoliumGrid.expandedGutter
        }

    val coverSpan: Int
        get() = when (this) {
            COMPACT -> FoliumGrid.COMPACT_COVER_SPAN
            MEDIUM -> FoliumGrid.MEDIUM_COVER_SPAN
            EXPANDED -> FoliumGrid.EXPANDED_COVER_SPAN
        }

    /** Whether a chosen book can be shown beside the shelf rather than instead of it. */
    val showsTwoPanes: Boolean get() = this == EXPANDED

    companion object {
        val MEDIUM_FROM: Dp = androidx.compose.ui.unit.Dp(600f)
        val EXPANDED_FROM: Dp = androidx.compose.ui.unit.Dp(840f)

        fun of(width: Dp): FoliumWidthClass = when {
            width >= EXPANDED_FROM -> EXPANDED
            width >= MEDIUM_FROM -> MEDIUM
            else -> COMPACT
        }
    }
}
