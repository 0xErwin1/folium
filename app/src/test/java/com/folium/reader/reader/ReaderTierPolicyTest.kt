package com.folium.reader.reader

import com.folium.reader.core.pdf.HorizontalViewportPageSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTierPolicyTest {
    private val phone = ReaderViewport(1080, 2154)
    private val tablet = ReaderViewport(1600, 2560)

    private val roomToSpare = 96L * 1024 * 1024
    private val theFloor = 16L * 1024 * 1024

    @Test fun `a budget with room holds every neighbour at the size it will be drawn`() {
        val policy = ReaderTierPolicy.forBudget(roomToSpare, phone)

        assertEquals(1, policy.nearDownscale)
        assertEquals(ReaderTierPolicy.LARGEST_BASE_EDGE_PX, policy.baseLongestEdgePx)
    }

    /**
     * The order things are given up in is the order they are worth: a page two turns away is worth
     * less than the fallback a fast flip lands on, which is worth less than the page the next turn
     * lands on.
     */
    @Test fun `a budget under pressure gives up the far pages before the fallback and the fallback before the neighbours`() {
        val policies = budgets().map { ReaderTierPolicy.forBudget(it, phone) }

        policies.zipWithNext { richer, poorer ->
            assertTrue(
                "a smaller budget kept a neighbour a larger one gave up: $richer then $poorer",
                poorer.nearDownscale >= richer.nearDownscale
            )
            assertTrue(poorer.baseLongestEdgePx <= richer.baseLongestEdgePx)
            assertTrue(poorer.prefetchDownscale >= richer.prefetchDownscale)
        }

        val squeezed = policies.last()
        assertTrue("the far pages were still full size under pressure", squeezed.prefetchDownscale > 1)
    }

    /** The whole point: what the policy asks for is what the cache can actually hold. */
    @Test fun `the window a policy implies fits the budget it was derived from`() {
        listOf(phone, tablet).forEach { viewport ->
            budgets().filter { it >= pageBytes(viewport) * 2 }.forEach { budget ->
                val policy = ReaderTierPolicy.forBudget(budget, viewport)
                assertTrue(
                    "budget=$budget viewport=$viewport policy=$policy wanted ${policy.windowBytes(viewport)}",
                    policy.windowBytes(viewport) <= budget
                )
            }
        }
    }

    /**
     * The page being read is never given up: a reader that cannot hold one page is already past the
     * point a policy can help, and asking for less than the page on screen would trade the only
     * thing that has to be right for room that is not there either way.
     */
    @Test fun `a budget too small for even one page still asks for that page`() {
        val policy = ReaderTierPolicy.forBudget(1L, phone)

        assertEquals(ReaderTierPolicy.FRUGAL, policy)
        assertTrue(policy.windowBytes(phone) >= pageBytes(phone))
    }

    @Test fun `a page is never asked for at more than the size it is drawn`() {
        budgets().forEach { budget ->
            assertTrue(ReaderTierPolicy.forBudget(budget, phone).nearDownscale >= 1)
        }
    }

    /**
     * A wider screen costs more per page for the same window, so the same budget must buy no more on
     * it than on a narrow one.
     */
    @Test fun `the same budget never buys more on a screen where a page costs more`() {
        budgets().forEach { budget ->
            val onAPhone = ReaderTierPolicy.forBudget(budget, phone)
            val onATablet = ReaderTierPolicy.forBudget(budget, tablet)

            assertTrue(onATablet.nearDownscale >= onAPhone.nearDownscale)
            assertTrue(onATablet.baseLongestEdgePx <= onAPhone.baseLongestEdgePx)
        }
    }

    @Test fun `the window it prices is the window the selector asks for`() {
        val policy = ReaderTierPolicy(nearDownscale = 1, prefetchDownscale = 2, baseLongestEdgePx = 256)
        val page = phone.widthPx.toLong() * phone.heightPx * 4
        val base = 256L * 256 * 4

        val expected = page +
            HorizontalViewportPageSelector.NEAR_PAGES * page +
            HorizontalViewportPageSelector.PREFETCH_PAGES * page / 4 +
            HorizontalViewportPageSelector.WINDOW_PAGES * base

        assertEquals(expected, policy.windowBytes(phone))
    }

    private fun pageBytes(viewport: ReaderViewport): Long =
        viewport.widthPx.toLong() * viewport.heightPx * 4

    private fun budgets(): List<Long> =
        listOf(roomToSpare, 64L * 1024 * 1024, 32L * 1024 * 1024, theFloor, 8L * 1024 * 1024)

    /**
     * [ReaderPresenter] prices a fitted spread's window against the *slot* viewport
     * [ReaderGeometry.slotViewport] derives, with `pagesPerView = 2` — see [ReaderTierPolicy.windowBytes]'s
     * own doc for why. This pins the real number the presenter's own request pattern implies, rather
     * than leaving the relationship between a spread's doubled page count and its halved per-page
     * cost as an argument: the real bytes a fitted spread's window can reach — one VISIBLE page per
     * slot, twice as many NEAR/PREFETCH pages as a single-page window holds, twice as many base-tier
     * fallbacks, every one of them at the slot's own narrower area — must never exceed what
     * `windowBytes(slot, pagesPerView = 2)` prices, which [forBudget] already keeps within the same
     * 3/4 share of the budget a single-page window is held to.
     */
    @Test fun `a spread's real window cost never exceeds what windowBytes prices it at`() {
        val gutterPx = 64
        val pageArea = tablet
        val slot = ReaderGeometry.slotViewport(pageArea, pagesPerView = 2, gutterPx = gutterPx)

        // Below this, even [ReaderTierPolicy.FRUGAL] priced for two pages can cost more than the
        // 3/4 share of a budget this tight — exactly like the single-page floor case covered by `a
        // budget too small for even one page still asks for that page`: asking for less than the
        // current page would leave nothing worth reading, so the budget is deliberately exceeded
        // rather than honored at the cost of not showing a page at all.
        budgets().filter { it >= pageBytes(slot) * 4 }.forEach { budget ->
            val policy = ReaderTierPolicy.forBudget(budget, slot, pagesPerView = 2)
            val slotPageBytes = pageBytes(slot)

            val onePageOfTheSpreadsWindow = slotPageBytes +
                HorizontalViewportPageSelector.NEAR_PAGES * slotPageBytes / (policy.nearDownscale.toLong() * policy.nearDownscale) +
                HorizontalViewportPageSelector.PREFETCH_PAGES * slotPageBytes / (policy.prefetchDownscale.toLong() * policy.prefetchDownscale) +
                HorizontalViewportPageSelector.WINDOW_PAGES * policy.baseLongestEdgePx.toLong() * policy.baseLongestEdgePx * 4
            val realSpreadCost = onePageOfTheSpreadsWindow * 2

            assertEquals(realSpreadCost, policy.windowBytes(slot, pagesPerView = 2))
            assertTrue(
                "budget=$budget slot=$slot policy=$policy realSpreadCost=$realSpreadCost affordable=${budget * 3 / 4}",
                realSpreadCost <= budget * 3 / 4
            )
        }
    }

    /**
     * The bug this guards against: pricing a spread's window as if it only held one page per tier
     * (`pagesPerView = 1`) at the slot viewport undercounts its real cost by roughly half, since a
     * fitted spread actually holds up to twice as many pages per tier. A policy chosen that way could
     * let a spread's real cache footprint reach up to 1.5x the device's configured budget.
     */
    @Test fun `pricing a spread window as pagesPerView 1 would understate its real cost`() {
        val gutterPx = 64
        val slot = ReaderGeometry.slotViewport(tablet, pagesPerView = 2, gutterPx = gutterPx)

        budgets().forEach { budget ->
            val singlePagePolicy = ReaderTierPolicy.forBudget(budget, slot)
            val correctPolicy = ReaderTierPolicy.forBudget(budget, slot, pagesPerView = 2)

            assertTrue(
                "a policy priced as a single page must never be richer than one priced for the real spread window",
                singlePagePolicy.windowBytes(slot) >= correctPolicy.windowBytes(slot)
            )
        }
    }
}
