package com.folium.reader.reader

import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.HorizontalViewportPageSelector
import com.folium.reader.core.pdf.HorizontalViewportReducer
import com.folium.reader.core.pdf.HorizontalViewportRequestCoordinator
import com.folium.reader.core.pdf.HorizontalViewportState
import com.folium.reader.core.pdf.PageRenderOutcome
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.pdf.SchedulerOutcome
import com.folium.reader.core.pdf.ViewportScheduler

/**
 * How many times a page whose render was refused for a retryable reason is re-driven before the
 * reader gives up on it and says so. The scheduler settles its whole remaining backlog as a
 * retryable resource failure when it cannot create a worker thread, so under memory pressure a
 * single transient refusal arrives as one refusal per outstanding page; re-driving is what keeps
 * that from painting the whole window as broken.
 */
internal const val MAX_PAGE_RETRY_ATTEMPTS = 4

private const val FIRST_RETRY_DELAY_MILLIS = 120L

/** Stands in where a spec is structurally required but no page can be requested — see [ReaderPresenter.close]. */
private val NO_REQUEST = RenderSpec(1, 1)

/** What the reader has to show right now. */
data class ReaderUiState<T>(
    val state: HorizontalViewportState,
    val pages: Map<Int, T> = emptyMap(),
    val failedPages: Set<Int> = emptySet()
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
 */
class ReaderPresenter<T>(
    val pageCount: Int,
    private val releaseValue: (T) -> Unit,
    private val pageAspect: (Int) -> Float,
    private val scheduleRetry: (Long, () -> Unit) -> Unit,
    private val deliverToPresenter: (() -> Unit) -> Unit,
    private val onChanged: (ReaderUiState<T>) -> Unit,
    schedulerFactory: ((SchedulerOutcome<T>) -> Unit) -> ViewportScheduler<T>
) {
    private val scheduler = schedulerFactory { outcome -> coordinator.onSchedulerOutcome(outcome) }

    private val coordinator: HorizontalViewportRequestCoordinator<T> =
        HorizontalViewportRequestCoordinator(scheduler, releaseValue) { outcome ->
            deliverToPresenter { deliver(outcome) }
        }

    private val pages = mutableMapOf<Int, T>()
    private val failedPages = mutableSetOf<Int>()
    private val retryAttempts = mutableMapOf<Int, Int>()

    private var viewport: ReaderViewport? = null
    private var closed = false

    var uiState: ReaderUiState<T> = ReaderUiState(HorizontalViewportState.initial(pageCount))
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
    fun close() {
        if (closed) return
        closed = true

        coordinator.applyState(uiState.state.copy(pageCount = 0, currentPage = 0)) { NO_REQUEST }

        pages.values.forEach(releaseValue)
        pages.clear()
        failedPages.clear()
        retryAttempts.clear()
        publish()
    }

    /**
     * Cancels and drains the scheduler. This blocks until every worker has published its outcome
     * and released its candidate, so it must never run on the presenter thread — see
     * [ViewportScheduler.close].
     */
    fun shutdown() = scheduler.close()

    private fun requestWindow() {
        val viewport = this.viewport ?: return
        val state = uiState.state

        val wanted = HorizontalViewportPageSelector.select(state).map { it.pageIndex }.toSet()
        releasePagesOutside(wanted)

        coordinator.applyState(state, ReaderGeometry.specForPage(viewport, state.zoom, pageAspect))
        publish()
    }

    private fun releasePagesOutside(wanted: Set<Int>) {
        val leaving = pages.keys.filterNot { it in wanted }
        leaving.forEach { pageIndex ->
            pages.remove(pageIndex)?.let(releaseValue)
            failedPages.remove(pageIndex)
            retryAttempts.remove(pageIndex)
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
     * A retryable refusal is a statement about the machine, not about the page, so it is re-driven
     * on a delay rather than reported. The coordinator has already exhausted the resubmissions it
     * can make without waiting, so the delay here is the point: it is the only thing that gives
     * whatever ran out of resources a chance to recover before the next attempt.
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
        publish()
    }

    private fun redrive(pageIndex: Int) {
        if (closed || pageIndex !in retryAttempts) return
        requestWindow()
    }

    private fun publish() {
        uiState = uiState.copy(pages = pages.toMap(), failedPages = failedPages.toSet())
        onChanged(uiState)
    }
}
