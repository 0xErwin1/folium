package com.folium.reader.reader

import com.folium.reader.core.pdf.HorizontalViewportPageSelector

/**
 * How much of the reading window this device can afford to hold at the size it is drawn.
 *
 * The reader keeps three tiers of raster around a page: the page itself, the pages a single turn
 * lands on, and a whole-page fallback for flipping faster than either can follow. Rendering all of
 * them at full size is what makes a turn open on the page rather than on a stand-in, and it is also
 * the most memory the reader can possibly ask for. Fixing that trade as a constant means picking it
 * for one device: generous enough for a phone is ruinous on a reader with a small heap, and frugal
 * enough for the small heap wastes a phone that had the room all along.
 *
 * So it is priced instead. Given what the cache may hold and what a page costs on this screen, the
 * best window that fits is chosen, giving things up in the order they are worth: a page two turns
 * away first, then the resolution of the fallback, and the page the next turn lands on last.
 */
data class ReaderTierPolicy(
    val nearDownscale: Int,
    val prefetchDownscale: Int,
    val baseLongestEdgePx: Int
) {
    init {
        require(nearDownscale >= 1 && prefetchDownscale >= 1 && baseLongestEdgePx >= 1)
    }

    /**
     * What a full window costs under this policy, as an upper bound: every page priced at the whole
     * viewport, and every fallback at a square page, since neither can cost more than that.
     */
    fun windowBytes(viewport: ReaderViewport): Long {
        val page = viewport.widthPx.toLong() * viewport.heightPx * BYTES_PER_PIXEL
        val fallback = baseLongestEdgePx.toLong() * baseLongestEdgePx * BYTES_PER_PIXEL

        return page +
            HorizontalViewportPageSelector.NEAR_PAGES * page / (nearDownscale.toLong() * nearDownscale) +
            HorizontalViewportPageSelector.PREFETCH_PAGES * page / (prefetchDownscale.toLong() * prefetchDownscale) +
            HorizontalViewportPageSelector.WINDOW_PAGES * fallback
    }

    companion object {
        const val LARGEST_BASE_EDGE_PX = 1024

        private const val BYTES_PER_PIXEL = 4L

        /**
         * Ladders, richest first. The reader walks them in the order the values matter, so the
         * cheapest thing it can give up is the first thing it does.
         */
        private val NEAR_LADDER = listOf(1, 2, 3, 4)
        private val BASE_LADDER = listOf(LARGEST_BASE_EDGE_PX, 768, 512, 384, 256, 192)
        private val PREFETCH_LADDER = listOf(2, 3, 4, 6, 8)

        /**
         * What is asked for when nothing fits: the page being read, and as little as possible around
         * it. A device this tight will evict whatever it cannot hold, and asking for less than this
         * would mean asking for a window that cannot be read at all.
         */
        val FRUGAL = ReaderTierPolicy(
            nearDownscale = NEAR_LADDER.last(),
            prefetchDownscale = PREFETCH_LADDER.last(),
            baseLongestEdgePx = BASE_LADDER.last()
        )

        /**
         * The best window that fits [budgetBytes] on a screen of [viewport].
         *
         * Not all of the budget: the cache holds transient rasters too — a tile from a zoom, a page
         * on its way out of the window — and a window sized to the last byte evicts one of its own
         * pages every time one of those arrives, which costs a render to save nothing.
         */
        fun forBudget(budgetBytes: Long, viewport: ReaderViewport): ReaderTierPolicy {
            val affordable = budgetBytes * WINDOW_SHARE_NUMERATOR / WINDOW_SHARE_DENOMINATOR

            NEAR_LADDER.forEach { near ->
                BASE_LADDER.forEach { base ->
                    PREFETCH_LADDER.forEach { prefetch ->
                        val candidate = ReaderTierPolicy(near, prefetch, base)
                        if (candidate.windowBytes(viewport) <= affordable) return candidate
                    }
                }
            }

            return FRUGAL
        }

        private const val WINDOW_SHARE_NUMERATOR = 3L
        private const val WINDOW_SHARE_DENOMINATOR = 4L
    }
}
