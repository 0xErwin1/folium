package com.folium.reader.reader

import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.HorizontalViewportPageSelector
import com.folium.reader.core.pdf.HorizontalViewportReducer
import com.folium.reader.core.pdf.HorizontalViewportRequestCoordinator
import com.folium.reader.core.pdf.HorizontalViewportState
import com.folium.reader.core.pdf.HorizontalViewportZoom
import com.folium.reader.core.pdf.MIN_ZOOM_SCALE
import com.folium.reader.core.pdf.PageRenderOutcome
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.RenderPriority
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.pdf.SchedulerOutcome
import com.folium.reader.core.pdf.ViewportScheduler
import com.folium.reader.core.pdf.WHOLE_PAGE_VISIBLE

/**
 * How many times a page whose render was refused for a retryable reason is re-driven before the
 * reader gives up on it and says so. The scheduler settles its whole remaining backlog as a
 * retryable resource failure when it cannot create a worker thread, so under memory pressure a
 * single transient refusal arrives as one refusal per outstanding page; re-driving is what keeps
 * that from painting the whole window as broken.
 */
internal const val MAX_PAGE_RETRY_ATTEMPTS = 4

private const val FIRST_RETRY_DELAY_MILLIS = 120L

/**
 * How long a page whose retryable failure exhausted [MAX_PAGE_RETRY_ATTEMPTS] waits before this
 * presenter, on its own, gives it one more chance — see [ReaderPresenter.fail]'s own doc for why a
 * retryable failure must never be allowed to stay in [ReaderUiState.failedPages] forever. Deliberately
 * much longer than the bounded retry backoff above: those retries are for a resource wall that might
 * clear within milliseconds, this one is for a reader who has stopped gesturing entirely, so there is
 * no wanted-window change to revive the page on its own.
 */
internal const val RECOVERY_REDRIVE_DELAY_MILLIS = 5_000L

/** Stands in where a spec is structurally required but no page can be requested — see [ReaderPresenter.close]. */
private val NO_REQUEST = RenderSpec(1, 1)

/**
 * The last whole-page preview the reading window left behind, and the page it belongs to.
 *
 * Drawn where the page being read has nothing of its own yet. A drag that outruns the renderer used
 * to leave a blank sheet between one page and the next; this is a page the reader was looking at a
 * moment ago, which says more than a blank sheet does and costs nothing to keep, since it has
 * already been rendered.
 *
 * [pageIndex] travels with it because it decides the shape it is drawn at: a document whose pages
 * differ would otherwise have this stretched into the proportions of the page it stands in for.
 */
data class CarriedPreview<T>(val pageIndex: Int, val value: T)

/** What the reader has to show right now. */
data class ReaderUiState<T>(
    val state: HorizontalViewportState,
    val pages: Map<Int, T> = emptyMap(),
    val basePages: Map<Int, T> = emptyMap(),
    val failedPages: Set<Int> = emptySet(),
    val carriedPreview: CarriedPreview<T>? = null
)

/**
 * Owns a reading session's request pipeline and the values it currently has on screen.
 *
 * Everything here — [dispatch], [setViewport], [close] and every delivered outcome — runs on a
 * single presenter thread (the Android main thread in production), which is what the request
 * coordinator requires of its [HorizontalViewportRequestCoordinator.applyState] caller. Outcomes
 * are produced on scheduler worker threads and handed to [deliverToPresenter] to be brought back
 * onto that thread; nothing else crosses it.
 *
 * **Borrow discipline.** Every value this presenter holds in [ReaderUiState.pages] is a live borrow
 * whose [releaseValue] this class is responsible for calling exactly once: when the page it belongs
 * to leaves the requested window, when a newer render replaces it, or when the session is [close]d.
 * Values that arrive after [close], or that the coordinator declines on this presenter's behalf,
 * are released rather than shown.
 *
 * **Refinement, not replacement.** A page keeps whatever it was last rendered with until a newer
 * render for that same page index arrives. A zoom or a resize therefore leaves the previous, now
 * lower-resolution raster on screen — correctly placed by [ReaderGeometry], merely soft — instead
 * of blanking the page, and it is only ever replaced by a render of the same page, never of
 * another one.
 *
 * **The base tier.** [ReaderUiState.basePages] holds a low-resolution, whole-page raster for every
 * page this presenter also wants a detail raster for, requested through [baseCoordinator] against a
 * second, dedicated [baseScheduler] rather than the detail tier's own [scheduler]. A pan or a
 * zoom-out can move the viewport onto page area the detail raster never covered — the detail raster
 * only ever covers the region it was requested for, see [ReaderGeometry] — and without a base
 * raster underneath, that area has nothing to draw and paints as empty reader background. Two
 * things make a dedicated scheduler necessary rather than sharing [scheduler]:
 * - [HorizontalViewportState.generation] rolls on essentially every gesture — pan, zoom, page turn,
 *   fit change — and [ViewportScheduler.advanceGeneration] cancels every in-flight request from a
 *   superseded generation uniformly, regardless of whether its content actually changed. A base
 *   tier raster's content depends only on the page's own aspect ratio, never on the viewport or the
 *   zoom, so tying it to the same generation counter as the detail tier would cancel it on almost
 *   every gesture before it could ever finish rendering.
 * - [HorizontalViewportRequestCoordinator] tracks at most one outstanding request per page index;
 *   two tiers wanting the same page index at once need two coordinator instances, which in turn
 *   need two schedulers, since a single [ViewportScheduler] is designed around exactly one
 *   `onOutcome` consumer.
 *
 * [baseScheduler] is built with a single worker (see the `baseSchedulerFactory` this class is
 * constructed with in production) rather than sharing [scheduler]'s worker count: a base tier
 * raster is requested once per page for the life of the session and is cheap to produce, so a
 * single dedicated worker never meaningfully contends with the detail tier's own workers for CPU,
 * and can never take one of their dispatch slots — the two schedulers' [ViewportScheduler] bounds
 * are entirely separate. This is a deliberate trade against literally interleaving both tiers'
 * requests through one shared [com.folium.reader.core.pdf.RenderPriority] queue, which the
 * generation coupling above rules out.
 *
 * A base tier raster is requested for exactly the same page window as the detail tier — see
 * [requestWindow] — and is held, and released, under the same borrow discipline: leaving the window
 * or the session closing releases it through [releaseValue], exactly like [ReaderUiState.pages].
 */
class ReaderPresenter<T>(
    val pageCount: Int,
    private val cacheBudgetBytes: Long,
    private val releaseValue: (T) -> Unit,
    private val pageAspect: (Int) -> Float,
    private val scheduleRetry: (Long, () -> Unit) -> Unit,
    private val deliverToPresenter: (() -> Unit) -> Unit,
    private val onChanged: (ReaderUiState<T>) -> Unit,
    initialPage: Int = 0,
    baseSchedulerFactory: ((SchedulerOutcome<T>) -> Unit) -> ViewportScheduler<T>,
    schedulerFactory: ((SchedulerOutcome<T>) -> Unit) -> ViewportScheduler<T>
) {
    private val scheduler = schedulerFactory { outcome -> coordinator.onSchedulerOutcome(outcome) }

    private val coordinator: HorizontalViewportRequestCoordinator<T> =
        HorizontalViewportRequestCoordinator(scheduler, releaseValue) { outcome ->
            deliverToPresenter { deliver(outcome) }
        }

    private val baseScheduler = baseSchedulerFactory { outcome -> baseCoordinator.onSchedulerOutcome(outcome) }

    private val baseCoordinator: HorizontalViewportRequestCoordinator<T> =
        HorizontalViewportRequestCoordinator(baseScheduler, releaseValue) { outcome ->
            deliverToPresenter { deliverBase(outcome) }
        }

    private var pricedPolicy: Pair<ReaderViewport, ReaderTierPolicy>? = null

    private val pages = mutableMapOf<Int, T>()
    private val basePages = mutableMapOf<Int, T>()

    /** See [CarriedPreview]. Held outside [basePages] because it outlives the window that asked for it. */
    private var carried: CarriedPreview<T>? = null
    private val failedPages = mutableSetOf<Int>()
    private val retryAttempts = mutableMapOf<Int, Int>()

    /**
     * The subset of [failedPages] whose last failure was a retryable resource failure rather than a
     * terminal one — see [fail]'s own doc. Tracked separately because [failedPages] alone cannot
     * distinguish the two: a page that is genuinely never going to render (a corrupt or
     * password-protected document) must stay in [failedPages] forever, while a page that only ran out
     * of a bounded, clock-free retry budget must not.
     */
    private val recoverableFailedPages = mutableSetOf<Int>()

    private var viewport: ReaderViewport? = null
    private var closed = false

    var uiState: ReaderUiState<T> = ReaderUiState(HorizontalViewportState.initial(pageCount, initialPage))
        private set

    fun setViewport(viewport: ReaderViewport?) {
        if (closed || viewport == this.viewport) return

        val hadViewport = this.viewport != null
        this.viewport = viewport

        if (viewport == null) return
        if (hadViewport) dispatch(GestureIntent.ViewportResized) else requestWindow()
    }

    fun dispatch(intent: GestureIntent) {
        if (closed) return

        val next = HorizontalViewportReducer.reduce(uiState.state, intent)
        if (next == uiState.state) return

        val generationRolled = next.generation != uiState.state.generation
        uiState = uiState.copy(state = next)

        if (generationRolled) requestWindow() else publish()
    }

    /**
     * Releases every value still on screen and stops accepting new ones. The scheduler is left
     * running: draining it blocks, so it is torn down separately by [shutdown], and anything it
     * publishes in the meantime is released here rather than shown.
     *
     * Giving up the outstanding requests first is what makes that safe. Cancelling an in-flight
     * render surfaces as a retryable refusal — that is how being asked to stop is reported — and a
     * request the coordinator still owns is answered by resubmitting it. [shutdown] cancels
     * everything at once, so without this the drain would answer its own cancellations with
     * submissions into a scheduler that has already closed, on worker threads where the resulting
     * failure has nothing left to catch it.
     */
    /**
     * Hands over the carried preview, and the obligation to release it, to the caller. A second call
     * — or one after [close], which has nothing left to give — returns null rather than the same
     * value twice, so a caller cannot double-release it and [close] never sees it again to release a
     * second time itself.
     */
    fun detachCarriedPreview(): CarriedPreview<T>? = carried.also { carried = null }

    /**
     * Hands over one raster to outlive this presenter, along with the obligation to release it.
     *
     * Prefers whatever is already carried, and otherwise promotes the current page's own raster.
     * A preview is only ever carried when one is leaving the window, which is to say during a page
     * turn — so a reader sitting still on a rendered page has nothing carried, and that is exactly
     * the moment a re-pagination is asked for. Taking the raster out of the page maps rather than
     * copying it is what keeps [close] from releasing the very thing the caller is about to draw.
     */
    fun detachPreviewForHandover(): CarriedPreview<T>? {
        detachCarriedPreview()?.let { return it }

        val current = uiState.state.currentPage
        val value = pages.remove(current) ?: basePages.remove(current) ?: return null

        return CarriedPreview(current, value)
    }

    fun close() {
        if (closed) return
        closed = true

        val closingState = uiState.state.copy(pageCount = 0, currentPage = 0)
        coordinator.applyState(closingState) { NO_REQUEST }
        baseCoordinator.applyState(baseWindowState(closingState)) { NO_REQUEST }

        pages.values.forEach(releaseValue)
        pages.clear()
        basePages.values.forEach(releaseValue)
        basePages.clear()
        carried?.let { releaseValue(it.value) }
        carried = null
        failedPages.clear()
        recoverableFailedPages.clear()
        retryAttempts.clear()
        publish()
    }

    /**
     * Cancels and drains both schedulers. This blocks until every worker of either has published
     * its outcome and released its candidate, so it must never run on the presenter thread — see
     * [ViewportScheduler.close]. Both are closed even if the first throws, so a stuck detail-tier
     * worker never leaves the base tier's own scheduler undrained, or the reverse; the first failure
     * is rethrown, with any second one attached as suppressed.
     */
    fun shutdown() {
        var firstError: Throwable? = null
        try {
            scheduler.close()
        } catch (error: Throwable) {
            firstError = error
        }
        try {
            baseScheduler.close()
        } catch (error: Throwable) {
            if (firstError == null) firstError = error else firstError.addSuppressed(error)
        }
        firstError?.let { throw it }
    }

    /**
     * What this device can afford for the window it is about to ask for. Cached against the viewport
     * it was priced for, since the price only changes when the screen does — a rotation, a resize —
     * and never between two gestures at the same size.
     */
    private fun tierPolicy(viewport: ReaderViewport): ReaderTierPolicy {
        pricedPolicy?.takeIf { it.first == viewport }?.let { return it.second }

        return ReaderTierPolicy.forBudget(cacheBudgetBytes, viewport)
            .also { pricedPolicy = viewport to it }
    }

    private fun requestWindow() {
        val viewport = this.viewport ?: return
        reconcilePageFrame(viewport)

        val state = uiState.state
        val wantedRequests = HorizontalViewportPageSelector.select(state)
        val wanted = wantedRequests.map { it.pageIndex }.toSet()
        releasePagesOutside(wanted)
        reviveRecoverableFailures(wanted)

        val priorityByPage = wantedRequests.associate { it.pageIndex to it.priority }
        val policy = tierPolicy(viewport)
        val specForPage = ReaderGeometry.specForPage(
            viewport,
            state.zoom,
            state.fitMode,
            { priorityByPage[it] ?: RenderPriority.PREFETCH },
            policy,
            pageAspect
        )
        coordinator.applyState(state, specForPage)
        baseCoordinator.applyState(baseWindowState(state)) { pageIndex ->
            ReaderGeometry.baseTierSpec(pageAspect(pageIndex), policy.baseLongestEdgePx)
        }
        publish()
    }

    /**
     * The half of [fail]'s recovery path driven by the wanted window itself changing: a page that
     * failed under a previous window and is still wanted under this one is worth a fresh attempt
     * right away, since whatever this call is a reaction to — a pan, a zoom, a page turn, a resize —
     * is itself evidence something about the reader's demand on [scheduler]/[cache] just changed.
     * The coordinator has already forgotten this page (see [PageRenderOutcome.Failed]'s own doc), so
     * clearing it here is enough to make [HorizontalViewportRequestCoordinator.applyState] treat it
     * as a brand-new request below, with a fresh [MAX_PAGE_RETRY_ATTEMPTS] budget of its own.
     */
    private fun reviveRecoverableFailures(wanted: Set<Int>) {
        val reviving = recoverableFailedPages.filter { it in wanted }
        if (reviving.isEmpty()) return
        recoverableFailedPages -= reviving.toSet()
        failedPages -= reviving.toSet()
    }

    /**
     * The state [baseCoordinator] is driven from: the same page count, current page and fit mode as
     * [state] — so it requests exactly the same page window — but with [HorizontalViewportZoom]
     * fixed at [MIN_ZOOM_SCALE] and [HorizontalViewportState.generation] pinned at `0`. The base
     * tier's own [RenderSpec] never depends on zoom, so the fixed zoom is inert; the pinned
     * generation is what keeps [baseCoordinator] from ever calling
     * [ViewportScheduler.advanceGeneration] on [baseScheduler] — see this class's own doc for why
     * that matters.
     */
    private fun baseWindowState(state: HorizontalViewportState): HorizontalViewportState = HorizontalViewportState(
        pageCount = state.pageCount,
        currentPage = state.currentPage,
        zoom = HorizontalViewportZoom(MIN_ZOOM_SCALE, PageSpacePoint(0.5f, 0.5f)),
        chromeVisible = state.chromeVisible,
        generation = 0L,
        fitMode = state.fitMode,
        visibleHeightFraction = WHOLE_PAGE_VISIBLE
    )

    /**
     * Brings the state's idea of how much of the page a fitted viewport reaches back in line with
     * the viewport and page actually in front of it, which changes with the viewport's own size,
     * with the fit mode, and with each page's shape. It is folded in here rather than dispatched so
     * that the requests made immediately afterwards are the ones the reconciled state implies,
     * rather than a generation behind it.
     */
    private fun reconcilePageFrame(viewport: ReaderViewport) {
        val state = uiState.state
        val measured = GestureIntent.PageFrameMeasured(
            ReaderGeometry.visibleHeightFraction(viewport, pageAspect(state.currentPage), state.fitMode)
        )

        val next = HorizontalViewportReducer.reduce(state, measured)
        if (next != state) uiState = uiState.copy(state = next)
    }

    /** Replaces whatever was being carried, releasing it: exactly one preview is ever held here. */
    private fun carry(pageIndex: Int, value: T) {
        carried?.takeIf { it.value !== value }?.let { releaseValue(it.value) }
        carried = CarriedPreview(pageIndex, value)
    }

    private fun releasePagesOutside(wanted: Set<Int>) {
        val leaving = pages.keys.filterNot { it in wanted }
        leaving.forEach { pageIndex ->
            pages.remove(pageIndex)?.let(releaseValue)
            failedPages.remove(pageIndex)
            recoverableFailedPages.remove(pageIndex)
            retryAttempts.remove(pageIndex)
        }

        // basePages is insertion-ordered, so the last of the leaving pages is the most recently
        // rendered one — the closest thing to what the reader was actually looking at.
        val baseLeaving = basePages.keys.filterNot { it in wanted }
        val freshest = baseLeaving.lastOrNull()
        baseLeaving.forEach { pageIndex ->
            val value = basePages.remove(pageIndex) ?: return@forEach
            if (pageIndex == freshest) carry(pageIndex, value) else releaseValue(value)
        }
    }

    private fun deliver(outcome: PageRenderOutcome<T>) {
        if (closed) {
            if (outcome is PageRenderOutcome.Rendered) releaseValue(outcome.value)
            return
        }

        when (outcome) {
            is PageRenderOutcome.Rendered -> show(outcome.pageIndex, outcome.value)
            is PageRenderOutcome.Failed -> fail(outcome.pageIndex, outcome.failure)
        }
    }

    private fun show(pageIndex: Int, value: T) {
        val stillWanted = HorizontalViewportPageSelector.select(uiState.state).any { it.pageIndex == pageIndex }
        if (!stillWanted) {
            releaseValue(value)
            return
        }

        pages.put(pageIndex, value)?.let(releaseValue)
        failedPages.remove(pageIndex)
        retryAttempts.remove(pageIndex)
        publish()
    }

    /**
     * A base tier failure is never reported: [HorizontalViewportRequestCoordinator] has already
     * resubmitted a retryable one on its own, and a terminal one leaves this page simply without a
     * base raster — the reader falls back to whatever [ReaderUiState.failedPages] or the loading
     * placeholder already shows for it, exactly as if this tier did not exist. There is no separate
     * retry timer for this tier: the next [requestWindow] call — driven by the very pan, zoom or
     * page turn that needs this raster — asks for it again, since a page dropped from
     * [baseCoordinator]'s own bookkeeping by a rejection is, from its perspective, simply not
     * outstanding yet.
     */
    private fun deliverBase(outcome: PageRenderOutcome<T>) {
        if (closed) {
            if (outcome is PageRenderOutcome.Rendered) releaseValue(outcome.value)
            return
        }

        if (outcome is PageRenderOutcome.Rendered) showBase(outcome.pageIndex, outcome.value)
    }

    private fun showBase(pageIndex: Int, value: T) {
        val stillWanted = HorizontalViewportPageSelector.select(uiState.state).any { it.pageIndex == pageIndex }
        if (!stillWanted) {
            // It arrived for a page the window has already left, which is exactly the page a drag
            // that outran the renderer wants to show. Keeping it costs a render that is already paid.
            carry(pageIndex, value)
            publish()
            return
        }

        basePages.put(pageIndex, value)?.let(releaseValue)
        publish()
    }

    /**
     * A retryable refusal is a statement about the machine, not about the page, so it is re-driven
     * on a delay rather than reported. The coordinator has already exhausted the resubmissions it
     * can make without waiting, so the delay here is the point: it is the only thing that gives
     * whatever ran out of resources a chance to recover before the next attempt.
     *
     * Once [MAX_PAGE_RETRY_ATTEMPTS] is exhausted, [pageIndex] is shown as failed — but, for a
     * [retryable] failure, never *permanently*: [recoverableFailedPages] tracks it as still worth
     * another try, and this presenter gives it two independent ways back, so a resource wall that
     * is transient in fact is never allowed to become permanent in the reader. [reviveRecoverableFailures]
     * revives it the moment the wanted window changes for any reason — a pan, a zoom, a page turn, a
     * resize — since that is itself evidence the demand on [scheduler]/[cache] just shifted. The
     * [scheduleRetry] call just below covers the case that leaves open: a reader who has stopped
     * gesturing entirely, on a page whose window has not changed, where nothing else will ever call
     * [requestWindow] again on its own. Any other failure — [failure] `null`, or a [PdfFailure] that
     * is not a retryable [PdfFailure.Resource] — is treated as genuinely terminal and left in
     * [ReaderUiState.failedPages] with no path back, since retrying it without some actual change to
     * the document or the request cannot plausibly produce a different outcome.
     */
    private fun fail(pageIndex: Int, failure: PdfFailure?) {
        val retryable = (failure as? PdfFailure.Resource)?.retryable == true
        val attempt = (retryAttempts[pageIndex] ?: 0) + 1

        if (retryable && attempt <= MAX_PAGE_RETRY_ATTEMPTS) {
            retryAttempts[pageIndex] = attempt
            scheduleRetry(FIRST_RETRY_DELAY_MILLIS shl (attempt - 1)) { redrive(pageIndex) }
            return
        }

        retryAttempts.remove(pageIndex)
        failedPages += pageIndex

        if (retryable) {
            recoverableFailedPages += pageIndex
            scheduleRetry(RECOVERY_REDRIVE_DELAY_MILLIS) { recover(pageIndex) }
        } else {
            recoverableFailedPages -= pageIndex
        }

        publish()
    }

    private fun redrive(pageIndex: Int) {
        if (closed || pageIndex !in retryAttempts) return
        requestWindow()
    }

    /**
     * The explicit-re-drive half of [fail]'s recovery path, for a page that exhausted its retry
     * budget while the wanted window never changed again to revive it through [reviveRecoverableFailures].
     * Only acts if [pageIndex] is still marked recoverable — a page that has since left the window
     * (cleaned up by [releasePagesOutside]) or already been revived some other way has nothing left
     * here to do.
     */
    private fun recover(pageIndex: Int) {
        if (closed || pageIndex !in recoverableFailedPages) return
        recoverableFailedPages -= pageIndex
        failedPages -= pageIndex
        publish()
        requestWindow()
    }

    private fun publish() {
        uiState = uiState.copy(
            pages = pages.toMap(),
            basePages = basePages.toMap(),
            failedPages = failedPages.toSet(),
            carriedPreview = carried
        )
        onChanged(uiState)
    }
}
