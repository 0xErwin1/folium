package com.folium.reader.ui

import androidx.compose.ui.unit.dp

/**
 * Eight steps of four.
 *
 * The screens had grown ten distinct values between 1 and 32 — 6, 10 and 14 among them — which is
 * enough variation that no two gaps mean anything relative to each other. These are the eight the
 * design system keeps; anything a screen needs that is not here is a decision to make deliberately,
 * not a number to reach for.
 */
internal object FoliumSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** The smallest square a finger is expected to hit, whatever is drawn inside it. */
    val touchTarget = 44.dp
}

/**
 * The layout grid.
 *
 * What stays constant across width classes is the COVER, not the column: a fluid grid with a fixed
 * column count necessarily stretches its module as the window widens, so the module alone cannot be
 * the invariant. The span is what holds the cover in a readable band — one column on a phone, two
 * from a small tablet up — and the effect is that covers get larger on larger screens rather than
 * multiplying at a fixed size.
 */
internal object FoliumGrid {
    val compactMargin = 20.dp
    val compactGutter = 12.dp
    const val COMPACT_COLUMNS = 4
    const val COMPACT_COVER_SPAN = 1

    val mediumMargin = 32.dp
    val mediumGutter = 16.dp
    const val MEDIUM_COLUMNS = 8
    const val MEDIUM_COVER_SPAN = 2

    val expandedMargin = 40.dp
    val expandedGutter = 20.dp
    const val EXPANDED_COLUMNS = 12
    const val EXPANDED_COVER_SPAN = 2

    /** Portrait proportion every cover placeholder and frame is drawn at. */
    const val COVER_ASPECT = 0.712f

    /** Below this a cover stops being recognizable; above it, too few fit to browse. */
    val minCover = 80.dp
    val maxCover = 210.dp
}
