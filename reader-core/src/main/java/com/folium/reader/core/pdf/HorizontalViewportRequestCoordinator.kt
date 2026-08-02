package com.folium.reader.core.pdf

import java.util.concurrent.atomic.AtomicLong

/**
 * Bounds how many times [HorizontalViewportRequestCoordinator] resubmits a single page after a
 * retryable [RejectionReason.FAILED] rejection before giving up and forwarding a terminal
 * [PageRenderOutcome.Failed]. Deliberately small: [ViewportScheduler.submit] can settle a
 * resubmission's own outcome synchronously, on the calling thread, before returning (see
 * [HorizontalViewportRequestCoordinator.onSchedulerOutcome]'s doc), so every resubmission below
 * this bound is a further, nested, synchronous stack frame rather than a later, independent
 * attempt — a large bound here would reproduce the original unbounded-recursion defect at a
 * longer, still-finite depth. This layer is required to stay deterministic and free of any
 * clock/thread-timing dependence (see [HorizontalViewportRequestCoordinator]'s own doc), so it
 * cannot wait between attempts. A real backoff before any *further* attempt, once this bound is
 * exhausted, is the responsibility of whoever consumes [PageRenderOutcome.Failed] — see that
 * type's own doc for the exact contract, and what happens if a consumer never honors it.
 */
private const val MAX_RESUBMIT_ATTEMPTS = 3

/** A page-scoped, terminal result handed to a [HorizontalViewportRequestCoordinator]'s own consumer. */
sealed class PageRenderOutcome<out T> {
    data class Rendered<T>(val pageIndex: Int, val value: T) : PageRenderOutcome<T>()

    /**
     * A page that will not render without further action.
     *
     * When [failure] is a [PdfFailure.Resource] with `retryable = true`,
     * [HorizontalViewportRequestCoordinator] has already resubmitted this page, on its own, up to
     * [MAX_RESUBMIT_ATTEMPTS] times, and is giving up only because this layer cannot wait between
     * attempts (see [MAX_RESUBMIT_ATTEMPTS]'s doc) — the page is genuinely still worth retrying,
     * but a further attempt, spaced out with an actual delay, is the consumer's responsibility.
     * The mechanism is: re-invoke [HorizontalViewportRequestCoordinator.applyState] later (e.g.
     * from a backoff timer) with a state whose wanted window still includes this page; since this
     * page was removed from the coordinator's outstanding bookkeeping before this outcome was
     * delivered, that call treats it as a brand-new request with a fresh attempt budget, not a
     * continuation of the exhausted one. If the consumer never re-drives it, the page simply stays
     * unrendered until something else changes the wanted window (navigation, zoom, resize) and
     * re-requests it as a side effect — there is no internal timer that will retry it on its own.
     *
     * Any other [failure] (or `null`) is terminal: retrying it without a state change that could
     * plausibly alter the outcome (e.g. a corrupt or password-protected document) is pointless.
     */
    data class Failed(val pageIndex: Int, val failure: PdfFailure?) : PageRenderOutcome<Nothing>()
}

/**
 * Drives a [ViewportScheduler] from a stream of [HorizontalViewportState] updates: this is the
 * "requested-page ownership" layer between the horizontal viewport model and the scheduler.
 *
 * On every [applyState], computes the wanted page window via [HorizontalViewportPageSelector],
 * submits a request for every newly-wanted page and cancels the handle for every page no longer
 * wanted. When [HorizontalViewportState.generation] has changed since the previous call, it first
 * calls [ViewportScheduler.advanceGeneration] and drops all local bookkeeping, so every previously
 * outstanding request — whether still queued or already in flight — is superseded and every page
 * in the new window is requested fresh (this also covers a viewport resize, where the wanted page
 * indices may be identical but every render target has changed size).
 *
 * [onSchedulerOutcome] must be registered as the driven [ViewportScheduler]'s own `onOutcome`
 * consumer. Per the scheduler's documented backlog-rejection contract — under thread-creation
 * pressure it can settle its entire remaining backlog as [PdfFailure.Resource] with
 * `retryable = true` — a retryable rejection is not a real per-page failure: this coordinator
 * resubmits it automatically, up to [MAX_RESUBMIT_ATTEMPTS] times, instead of forwarding it
 * straight to [onPageOutcome], so a single transient failure never paints every outstanding page
 * as an error. Once a page's resubmissions are exhausted it IS forwarded, as a terminal
 * [PageRenderOutcome.Failed] — see that type's own doc for what that specifically means and what a
 * consumer is expected to do about it. A [RejectionReason.CANCELLED] or
 * [RejectionReason.STALE_GENERATION] rejection means this coordinator, or a newer [applyState]
 * call, already stopped wanting that page, so it is dropped silently rather than forwarded. Only a
 * genuine render, or a terminal (non-retryable, or retries-exhausted) failure reaches
 * [onPageOutcome].
 *
 * This class never reads a cached page itself: it only owns [ViewportRenderRequest] lifecycle
 * (submit/cancel/resubmit). Reading a rendered value out of a [ByteBoundedPageCache] — pairing an
 * [ByteBoundedPageCache.acquire] with the matching [CachedPage.release] — is the job of whatever
 * consumes [onPageOutcome], not this layer.
 *
 * [applyState] is not safe to call concurrently from multiple threads: callers must serialize
 * their own state updates, e.g. by driving gestures through a single reducer loop, exactly as
 * [HorizontalViewportReducer] itself assumes no concurrent mutation of a single state value.
 * [onSchedulerOutcome], by contrast, is safe to call from any thread — including concurrently with
 * [applyState] and with itself — since it only ever touches this coordinator's bookkeeping under
 * its own lock.
 */
class HorizontalViewportRequestCoordinator<T>(
    private val scheduler: ViewportScheduler<T>,
    private val releaseValue: (T) -> Unit,
    private val onPageOutcome: (PageRenderOutcome<T>) -> Unit
) {
    private val lock = Any()
    private val outstanding = mutableMapOf<Int, RenderHandle>()
    private val resubmitAttempts = mutableMapOf<Int, Int>()

    /**
     * Mints every [ViewportRenderRequest.token] this coordinator ever submits, starting at `1`
     * (`incrementAndGet` on a `0`-initialized counter). This is what makes
     * [ViewportScheduler.submit]'s defaulted `token: Long = 0L` safe for a caller with no
     * correlation need of its own: since this coordinator never mints `0L`, an un-tokened direct
     * submission can never collide with, or be mistaken for, one of this coordinator's own
     * requests — see [ViewportScheduler.submit]'s own doc for the scheduler-side half of this
     * invariant.
     *
     * This counter is per-coordinator instance. Driving a single [ViewportScheduler] from more
     * than one [HorizontalViewportRequestCoordinator] is not supported: each would mint its own
     * token `1`, `2`, … independently, so the two sequences could collide, and a scheduler is in
     * any case designed around exactly one `onOutcome` consumer.
     */
    private val nextToken = AtomicLong(0)

    /**
     * The [ViewportRenderRequest.token] this coordinator currently considers live for a page, if
     * any. This is what ownership is decided against in [onSchedulerOutcome], instead of anything
     * derived from [outstanding] or from the calling thread — see that method's doc for why a
     * per-page, thread-visible flag or a [RenderHandle] comparison are both the wrong granularity.
     *
     * The entry for a page is written in [submitForPage] *before* [ViewportScheduler.submit] is
     * called, which is the property that makes this correct: [ViewportScheduler.submit] can
     * publish this exact request's outcome synchronously, on the calling thread, before it returns
     * — see [ViewportRenderRequest.token]'s doc — so by the time any outcome for this token can
     * possibly arrive, this map already has the entry to match it against. There is no window in
     * which a legitimate outcome could be missed for want of bookkeeping the caller hasn't caught
     * up on yet.
     */
    private val liveTokenForPage = mutableMapOf<Int, Long>()

    private var lastAppliedGeneration: Long? = null

    fun applyState(state: HorizontalViewportState, specForPage: (Int) -> RenderSpec) {
        val generationChanged = lastAppliedGeneration != null && lastAppliedGeneration != state.generation
        if (generationChanged) {
            scheduler.advanceGeneration()
            synchronized(lock) {
                outstanding.clear()
                resubmitAttempts.clear()
                liveTokenForPage.clear()
            }
        }
        lastAppliedGeneration = state.generation

        val wanted = HorizontalViewportPageSelector.select(state)
        val wantedPages = wanted.map { it.pageIndex }.toSet()

        val toCancel: List<RenderHandle>
        val toSubmit: List<ViewportPageRequest>
        synchronized(lock) {
            toCancel = outstanding.keys.filter { it !in wantedPages }.mapNotNull { pageIndex ->
                resubmitAttempts.remove(pageIndex)
                liveTokenForPage.remove(pageIndex)
                outstanding.remove(pageIndex)
            }
            toSubmit = wanted.filter { it.pageIndex !in outstanding }
        }

        toCancel.forEach { scheduler.cancel(it) }
        toSubmit.forEach { request -> submitForPage(request.pageIndex, request.priority, specForPage(request.pageIndex)) }
    }

    /**
     * An outcome is this coordinator's own if and only if [SchedulerOutcome]'s request carries the
     * token this coordinator currently considers live for that page — see [liveTokenForPage]'s
     * doc. Every other outcome — a [RejectionReason.STALE_GENERATION] or [RejectionReason.CANCELLED]
     * published by a superseded generation's worker for the same page, or any outcome belonging to
     * a request this coordinator has already superseded with a fresher submission or already
     * terminally resolved — carries a token that does not match, by construction, and is dropped
     * here regardless of which thread or how many nested calls delivered it.
     *
     * A dropped [SchedulerOutcome.Rejected] never needs releasing: [ViewportScheduler] already
     * released any candidate behind a rejection itself, or never created one (a still-queued
     * request rejected without ever reaching the renderer). A dropped [SchedulerOutcome.Rendered]
     * is different: the scheduler's ownership-transfer contract (see [ViewportRenderer]'s own doc)
     * hands the candidate's value to the outcome consumer on publication and expects that consumer
     * to release it once done — but a not-still-owned [SchedulerOutcome.Rendered] is dropped right
     * here, before it ever reaches [onPageOutcome], so nothing downstream will ever get the chance.
     * [releaseValue] is called on it here instead: this is the only place in this class that
     * declines a [SchedulerOutcome.Rendered] without either forwarding or releasing its value.
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

        when (outcome) {
            is SchedulerOutcome.Rendered -> {
                clearBookkeeping(request.pageIndex)
                onPageOutcome(PageRenderOutcome.Rendered(request.pageIndex, outcome.value))
            }

            is SchedulerOutcome.Rejected -> {
                val resourceFailure = outcome.failure as? PdfFailure.Resource
                when {
                    outcome.reason == RejectionReason.FAILED && resourceFailure?.retryable == true -> {
                        val attempt = synchronized(lock) {
                            val next = (resubmitAttempts[request.pageIndex] ?: 0) + 1
                            resubmitAttempts[request.pageIndex] = next
                            next
                        }
                        if (attempt > MAX_RESUBMIT_ATTEMPTS) {
                            clearBookkeeping(request.pageIndex)
                            onPageOutcome(PageRenderOutcome.Failed(request.pageIndex, outcome.failure))
                        } else {
                            submitForPage(request.pageIndex, request.priority, request.spec)
                        }
                    }

                    outcome.reason == RejectionReason.CANCELLED || outcome.reason == RejectionReason.STALE_GENERATION -> {
                        clearBookkeeping(request.pageIndex)
                    }

                    else -> {
                        clearBookkeeping(request.pageIndex)
                        onPageOutcome(PageRenderOutcome.Failed(request.pageIndex, outcome.failure))
                    }
                }
            }
        }
    }

    /**
     * Submits [pageIndex] against the scheduler under a freshly minted, globally unique token,
     * recorded as [pageIndex]'s live token *before* calling [ViewportScheduler.submit] — see
     * [liveTokenForPage]'s doc for why that ordering is what makes ownership recognition correct.
     * Used for both a page's first submission (from [applyState]) and every retry (from
     * [onSchedulerOutcome] itself), so both are covered identically.
     *
     * A nested, synchronous resubmission for the same page (see [ViewportRenderRequest.token]'s
     * doc on synchronous delivery) overwrites [liveTokenForPage]'s entry for [pageIndex] with its
     * own newer token before this call's own `submit` returns. The check after `submit` returns
     * only records [handle] into [outstanding] if this call's token is *still* the live one for
     * [pageIndex] — if a nested resubmission has since superseded it, or if the page's retries were
     * exhausted and terminally resolved while this call was still on the stack (clearing the entry
     * entirely, see [clearBookkeeping]), this call's own handle is silently discarded instead of
     * resurrecting a request nobody wants anymore. This is what stops [outstanding] from being
     * repopulated with a handle for a request that has already been resolved, one stack frame at a
     * time, as the retry chain unwinds.
     *
     * If [ViewportScheduler.submit] itself throws, this call's bookkeeping for [pageIndex] is
     * cleaned up again, unless it has already been superseded — see [clearBookkeepingForToken]. Two
     * cases are distinguished:
     * - [SchedulerClosedException]: [scheduler] has already been [ViewportScheduler.close]d. The
     *   session this request belonged to is gone, so there is nobody left to resubmit to and nobody
     *   waiting for this page's outcome — this is expected on the shutdown path (a worker can still
     *   be resubmitting an owned retryable rejection, from [onSchedulerOutcome]'s `:208` branch, at
     *   the exact moment [close] flips its closed flag) and is swallowed here rather than being
     *   allowed to propagate as an uncaught exception on the calling — often a `viewport-render-N` —
     *   thread. [SchedulerClosedException] is deliberately a distinct, unrelated invariant from a
     *   caller's own request lifecycle: it is not something this coordinator degrades into a
     *   [PageRenderOutcome.Failed], since by construction there is no live [onPageOutcome] consumer
     *   left interested in this page once the scheduler underneath it is gone.
     * - Any other [Throwable]: not this coordinator's concern to interpret, so it is rethrown after
     *   the same cleanup, exactly as before.
     */
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

    private fun clearBookkeeping(pageIndex: Int) {
        synchronized(lock) {
            outstanding.remove(pageIndex)
            resubmitAttempts.remove(pageIndex)
            liveTokenForPage.remove(pageIndex)
        }
    }

    /**
     * Clears every bookkeeping entry for [pageIndex] — [outstanding], [resubmitAttempts] and
     * [liveTokenForPage] alike — but only if [liveTokenForPage] still holds [token]: a nested,
     * synchronous resubmission (see [ViewportRenderRequest.token]'s doc on synchronous delivery)
     * cannot have happened while this call's own `submit` was still on the stack, but a concurrent
     * [onSchedulerOutcome] resolving a *different*, already-live request for the same page is not
     * excluded by that alone, so the clear stays conditional rather than unconditional.
     *
     * Clearing [outstanding] and [resubmitAttempts] here, not only [liveTokenForPage], is what keeps
     * this page from being left half-cleared after a throwing `submit`: [outstanding] would
     * otherwise still name a handle for a request that has already been rejected and superseded,
     * which would make a later [applyState] wrongly treat the page as still requested and skip
     * resubmitting it, rather than a brand-new request with a fresh attempt budget.
     */
    private fun clearBookkeepingForToken(pageIndex: Int, token: Long) {
        synchronized(lock) {
            if (liveTokenForPage[pageIndex] == token) {
                outstanding.remove(pageIndex)
                resubmitAttempts.remove(pageIndex)
                liveTokenForPage.remove(pageIndex)
            }
        }
    }
}
