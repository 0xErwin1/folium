package com.folium.reader.index

/**
 * Given every layout a book has ever used, ordered most recently used first, returns the layouts
 * beyond the [retain] most recent ones — the ones eviction should delete.
 *
 * The layout currently being read is always recorded as the most recent entry before this is
 * called, so it is never among the results even under a race between two sessions of the same
 * book: both record their own layout's use, and [TextPageIndex.retainRecentLayouts] serializes both
 * calls through the same lock a single [RoomTextPageIndex] instance already holds for every write.
 * Recency is a strictly increasing sequence number rather than a wall-clock timestamp — see
 * [RoomTextPageIndex]'s own usage — so two layouts can never tie for the same rank.
 */
internal fun layoutsBeyondRetention(
    mostRecentFirst: List<String>,
    retain: Int = RETAINED_LAYOUTS_PER_BOOK
): List<String> {
    require(retain >= 0)
    return mostRecentFirst.drop(retain)
}
