package com.folium.reader.core.pdf

import java.util.concurrent.atomic.AtomicLong

/** A page-scoped result handed to a [PageWindowRequestCoordinator]'s own consumer. */
sealed class PageWindowOutcome<out T> {
    data class Rendered<T>(val pageIndex: Int, val value: T) : PageWindowOutcome<T>()
    data class Failed(val pageIndex: Int) : PageWindowOutcome<Nothing>()
}

/**
 * Drives a [ViewportScheduler] from an explicit, caller-supplied list of wanted page indices,
 * rather than from a [HorizontalViewportState] the way [HorizontalViewportRequestCoordinator]
 * does: a page grid's wanted window is whatever the caller decides is visible right now, not
 * something this layer can derive from a single reading position.
 *
 * [setWanted] submits a request for every page newly wanted and cancels the handle for every page
 * no longer wanted, deduping a page that is already outstanding. Unlike
 * [HorizontalViewportRequestCoordinator], this coordinator never resubmits a retryable failure on
 * its own: a caller whose wanted window changes on its own schedule (a scrolling grid, rather than
 * a single reading position revisited by every gesture) naturally re-requests an unrendered page
 * the next time it scrolls back into view, so there is no need for an internal retry budget — a
 * [RejectionReason.FAILED] rejection is forwarded to the consumer as a terminal
 * [PageWindowOutcome.Failed] immediately, on its first occurrence.
 *
 * [setWanted] is not safe to call concurrently from multiple threads: callers must serialize their
 * own updates. [onSchedulerOutcome], by contrast, is safe to call from any thread — including
 * concurrently with [setWanted] and with itself — since it only touches this coordinator's own
 * bookkeeping under its own lock.
 */
class PageWindowRequestCoordinator<T>(
    private val scheduler: ViewportScheduler<T>,
    private val releaseValue: (T) -> Unit,
    private val onOutcome: (PageWindowOutcome<T>) -> Unit
) {
    private val lock = Any()
    private val outstanding = mutableMapOf<Int, RenderHandle>()

    /** Mints every token this coordinator submits — see [HorizontalViewportRequestCoordinator.nextToken]'s doc for why a distinct, monotonically increasing token is what makes ownership recognition race-free. */
    private val nextToken = AtomicLong(0)
    private val liveTokenForPage = mutableMapOf<Int, Long>()

    /**
     * Submits [pages] not already outstanding, at the priority and spec [priority]/[spec] resolve
     * for each, and cancels the handle for every page outstanding but no longer present in [pages].
     */
    fun setWanted(pages: List<Int>, priority: (Int) -> RenderPriority, spec: (Int) -> RenderSpec) {
        val wanted = pages.toSet()
        val toCancel: List<RenderHandle>
        val toSubmit: List<Int>
        synchronized(lock) {
            toCancel = outstanding.keys.filter { it !in wanted }.mapNotNull { pageIndex ->
                liveTokenForPage.remove(pageIndex)
                outstanding.remove(pageIndex)
            }
            toSubmit = pages.filter { it !in outstanding }
        }

        toCancel.forEach(scheduler::cancel)
        toSubmit.forEach { pageIndex -> submitForPage(pageIndex, priority(pageIndex), spec(pageIndex)) }
    }

    /** Cancels every outstanding request and clears all bookkeeping, without releasing anything already delivered to the consumer. */
    fun cancelAll() {
        val handles: List<RenderHandle>
        synchronized(lock) {
            handles = outstanding.values.toList()
            outstanding.clear()
            liveTokenForPage.clear()
        }
        handles.forEach(scheduler::cancel)
    }

    /**
     * Must be registered as the driven [ViewportScheduler]'s own `onOutcome` consumer. See
     * [HorizontalViewportRequestCoordinator.onSchedulerOutcome]'s doc for the ownership check this
     * mirrors: an outcome is this coordinator's own only if its request carries the token this
     * coordinator currently considers live for that page.
     */
    fun onSchedulerOutcome(outcome: SchedulerOutcome<T>) {
        val request = when (outcome) {
            is SchedulerOutcome.Rendered -> outcome.request
            is SchedulerOutcome.Rejected -> outcome.request
        }

        val stillOwned = synchronized(lock) { liveTokenForPage[request.pageIndex] == request.token }
        if (!stillOwned) {
            if (outcome is SchedulerOutcome.Rendered) releaseValue(outcome.value)
            return
        }

        clearBookkeepingForToken(request.pageIndex, request.token)

        when (outcome) {
            is SchedulerOutcome.Rendered -> onOutcome(PageWindowOutcome.Rendered(request.pageIndex, outcome.value))
            is SchedulerOutcome.Rejected -> when (outcome.reason) {
                RejectionReason.CANCELLED, RejectionReason.STALE_GENERATION -> Unit
                RejectionReason.FAILED -> onOutcome(PageWindowOutcome.Failed(request.pageIndex))
            }
        }
    }

    private fun submitForPage(pageIndex: Int, priority: RenderPriority, spec: RenderSpec) {
        val token = nextToken.incrementAndGet()
        synchronized(lock) { liveTokenForPage[pageIndex] = token }
        val handle = try {
            scheduler.submit(pageIndex, priority, spec, token)
        } catch (closed: SchedulerClosedException) {
            clearBookkeepingForToken(pageIndex, token)
            return
        } catch (failure: Throwable) {
            clearBookkeepingForToken(pageIndex, token)
            throw failure
        }
        synchronized(lock) {
            if (liveTokenForPage[pageIndex] == token) outstanding[pageIndex] = handle
        }
    }

    private fun clearBookkeepingForToken(pageIndex: Int, token: Long) {
        synchronized(lock) {
            if (liveTokenForPage[pageIndex] == token) {
                outstanding.remove(pageIndex)
                liveTokenForPage.remove(pageIndex)
            }
        }
    }
}
