package com.folium.reader.reader

import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.PageWindowOutcome
import com.folium.reader.core.pdf.PageWindowRequestCoordinator
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.RenderPriority
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.pdf.SchedulerOutcome
import com.folium.reader.core.pdf.ViewportScheduler

private const val THUMBNAIL_WORKERS = 1

/** What the page grid has to show right now. */
data class ThumbnailGridState<T>(
    val thumbnails: Map<Int, T> = emptyMap(),
    val failed: Set<Int> = emptySet()
)

/**
 * Owns one document's page-thumbnail request pipeline: a dedicated single-worker scheduler over a
 * [ThumbnailRenderer], driven through [PageWindowRequestCoordinator] from an explicit wanted-page
 * list — the grid's own visible range — rather than from a reading position.
 *
 * Generic over [T], exactly like [ReaderPresenter] and [PageWindowRequestCoordinator] themselves,
 * so a JVM unit test can drive this class against a fake renderer and a fake borrowed value rather
 * than a real [ThumbnailRenderer] and [android.graphics.Bitmap] — see [buildThumbnailPipeline] for
 * the production wiring this stands in for.
 *
 * Mutating calls ([setWanted], [close]) are confined to the presenter thread, exactly like
 * [ReaderPresenter]: outcomes arrive on the scheduler's own worker thread and are brought back onto
 * it through [deliverToPresenter] before ever touching [shown] or [failed].
 */
internal class ThumbnailPipeline<T>(
    private val releaseValue: (T) -> Unit,
    private val deliverToPresenter: (() -> Unit) -> Unit,
    private val onChanged: (ThumbnailGridState<T>) -> Unit,
    schedulerFactory: ((SchedulerOutcome<T>) -> Unit) -> ViewportScheduler<T>
) {
    private lateinit var coordinator: PageWindowRequestCoordinator<T>
    private val scheduler = schedulerFactory { outcome -> deliverToPresenter { coordinator.onSchedulerOutcome(outcome) } }

    private val shown = mutableMapOf<Int, T>()
    private val failed = mutableSetOf<Int>()
    private var closed = false

    init {
        coordinator = PageWindowRequestCoordinator(scheduler, releaseValue) { outcome ->
            when (outcome) {
                is PageWindowOutcome.Rendered -> {
                    shown.put(outcome.pageIndex, outcome.value)?.let(releaseValue)
                    failed -= outcome.pageIndex
                }
                is PageWindowOutcome.Failed -> failed += outcome.pageIndex
            }
            publish()
        }
    }

    private fun publish() = onChanged(ThumbnailGridState(shown.toMap(), failed.toSet()))

    /**
     * Declares [pages] the only ones worth a thumbnail right now, sized by [specForPage]: everything
     * already shown outside [pages] is released immediately, and
     * [PageWindowRequestCoordinator.setWanted] takes care of cancelling any outstanding render for a
     * page that just left the window.
     */
    fun setWanted(pages: List<Int>, specForPage: (Int) -> RenderSpec) {
        if (closed) return

        val wanted = pages.toSet()
        shown.keys.filterNot { it in wanted }.forEach { pageIndex -> shown.remove(pageIndex)?.let(releaseValue) }
        failed.retainAll(wanted)

        coordinator.setWanted(pages, { RenderPriority.VISIBLE }, specForPage)
        publish()
    }

    /**
     * Releases every borrow currently shown and stops accepting new work. Cheap and non-blocking —
     * called when the whole reading session this pipeline belongs to is closing or being rebuilt at
     * a fresh generation, never merely when the page grid sheet itself is dismissed: a reader
     * reopening the sheet later should find its thumbnails already there, which is why [setWanted]
     * with an empty list, not this, is what a dismissed sheet calls.
     *
     * The emptied state is published, because everything it held has just been released: a grid
     * still drawing the previous state would be drawing rasters that are no longer its to draw.
     */
    fun close() {
        if (closed) return
        closed = true

        coordinator.cancelAll()
        shown.values.forEach(releaseValue)
        shown.clear()
        failed.clear()

        publish()
    }

    /** Blocking: drains the scheduler. Must not run on the main thread, and only after [close]. */
    fun shutdown() = scheduler.close()
}

/**
 * Production wiring for [ThumbnailPipeline]: a real [ThumbnailRenderer] over [document], sharing
 * [priorityGate] with the reader's own page renders and everything else already yielding to them.
 *
 * [cache] is a dedicated, small [ByteBoundedPageCache] the caller owns — never the same instance as
 * the page-raster cache [ReaderSession] already holds. Keeping them apart, rather than sharing one
 * cache with keys distinguished only by [RenderSpec], means a thumbnail can never crowd a page
 * raster's borrow out of budget, or the reverse, whichever cache happens to be under memory
 * pressure first.
 */
internal fun buildThumbnailPipeline(
    document: PdfDocument,
    documentId: String,
    generation: Long,
    priorityGate: DocumentPriorityGate,
    cache: ByteBoundedPageCache<ThumbnailRaster>,
    deliverToPresenter: (() -> Unit) -> Unit,
    onChanged: (ThumbnailGridState<BorrowedThumbnail>) -> Unit
): ThumbnailPipeline<BorrowedThumbnail> {
    val renderer = ThumbnailRenderer(document, documentId, generation, cache, priorityGate)
    return ThumbnailPipeline(BorrowedThumbnail::release, deliverToPresenter, onChanged) { onOutcome ->
        ViewportScheduler(THUMBNAIL_WORKERS, renderer, workerPoolName = "render-thumb", onOutcome = onOutcome)
    }
}
