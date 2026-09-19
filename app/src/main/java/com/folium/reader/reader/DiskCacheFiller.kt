package com.folium.reader.reader

import com.folium.reader.core.diskcache.DiskPageCacheKey
import com.folium.reader.core.diskcache.DiskPageCacheStore
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.RenderSpec

/**
 * How many pages out from the current one [nextDiskCacheFillPage] alternates over before ranging
 * over the rest of the document.
 */
internal const val DISK_CACHE_FILL_WINDOW_RADIUS_PAGES = 30

/**
 * How long the engine must have gone unused before a fill render is allowed to start — see
 * [DocumentPriorityGate.awaitIdlePermit]. Long enough that a reader flipping pages, which leaves
 * well under a second between turns, never sees a fill claim the engine in the gap between two of
 * its own requests; short enough that a reader who has stopped touching the document for a couple
 * of seconds still gets a filled disk cache well before they next jump anywhere.
 */
internal const val DISK_CACHE_FILL_IDLE_QUIET_MILLIS = 2_000L

/** How long the fill loop waits before checking again once it finds nothing left to fill. */
internal const val DISK_CACHE_FILL_NO_WORK_POLL_MILLIS = 500L

/** How long the fill loop waits before checking again after finding the disk write queue full. */
internal const val DISK_CACHE_FILL_QUEUE_BACKOFF_MILLIS = 250L

/**
 * How often a page outside [DISK_CACHE_FILL_WINDOW_RADIUS_PAGES] may be filled, once the near window
 * is fully satisfied. The near window is what makes a jump or a page turn instant, so it is always
 * filled back to back; the rest of the document only has to be ready eventually, and filling it at
 * full speed keeps two CPU cores busy for minutes on a long document for no benefit the reader can
 * feel. Pacing it this way trades that time for a fraction of the CPU and battery cost.
 */
internal const val DISK_CACHE_FILL_FAR_PAGE_INTERVAL_MILLIS = 500L

/** How often the far-page pacing wait re-checks the current page and the stop flag. */
internal const val DISK_CACHE_FILL_FAR_WAIT_POLL_MILLIS = 50L

/**
 * The document position and spec function [DiskCacheFiller] drives its ordering from, read fresh on
 * every iteration of its loop so the fill always chases the *current* page under the *current*
 * geometry rather than whatever was true when it last looked.
 *
 * [specForPage] returns the exact base-tier [RenderSpec] the reader would itself request for a page
 * right now — see [ReaderPresenter.currentBaseTierSpec] — and is null whenever that cannot be
 * computed yet (no viewport measured), in which case there is nothing for the fill to do.
 */
internal data class DiskCacheFillTarget(
    val currentPage: Int,
    val pageCount: Int,
    val specForPage: (Int) -> RenderSpec?
)

/**
 * A page [nextDiskCacheFillPage] has chosen next, together with whether it falls inside the near
 * window it was found within — see [DiskCacheFiller] for what that distinction is used for.
 */
internal data class DiskCacheFillCandidate(val pageIndex: Int, val isWithinWindow: Boolean)

/**
 * Chooses the next page [DiskCacheFiller] should try to fill, given [isPresent] as the live truth of
 * what already satisfies the fill.
 *
 * Pages are offered nearest [currentPage] first, alternating one page ahead and one page behind, so
 * a reader who jumps in either direction finds the pages just beyond their current one already
 * filled. Distance from [currentPage] is the only ordering key this function uses: [windowRadius]
 * names how far that alternation is expected to reach in practice before the rest of the document
 * becomes worth filling, but it is not a second phase with a different rule — a page beyond
 * [windowRadius] is still offered strictly before one further away than it, exactly as one within it
 * would be. Once one side of the document is exhausted — [currentPage] close enough to either end —
 * the alternation continues on the surviving side alone.
 *
 * Returns null once every page in the document already satisfies [isPresent].
 */
internal fun nextDiskCacheFillPage(
    currentPage: Int,
    pageCount: Int,
    windowRadius: Int,
    isPresent: (Int) -> Boolean
): DiskCacheFillCandidate? {
    require(windowRadius >= 0) { "windowRadius must not be negative, was $windowRadius" }
    if (pageCount <= 0 || currentPage !in 0 until pageCount) return null

    if (!isPresent(currentPage)) return DiskCacheFillCandidate(currentPage, isWithinWindow = true)

    val maxDistance = maxOf(currentPage, pageCount - 1 - currentPage)
    for (distance in 1..maxDistance) {
        val withinWindow = distance <= windowRadius

        val forward = currentPage + distance
        if (forward < pageCount && !isPresent(forward)) return DiskCacheFillCandidate(forward, withinWindow)

        val backward = currentPage - distance
        if (backward >= 0 && !isPresent(backward)) return DiskCacheFillCandidate(backward, withinWindow)
    }

    return null
}

/**
 * Fills [store] with the base-tier raster of every page of [document] that is not on disk yet, so a
 * jump to any page, or a reopen of the same document, finds it there instead of paying for a cold
 * engine render.
 *
 * Runs entirely on [worker], a single dedicated, lowest-priority thread built by [threadFactory]:
 * [start] launches it, [close] asks it to stop without waiting, and [dispose] blocks until it has —
 * the same two-phase shape [OcrPagePipeline.close]/[OcrPagePipeline.dispose] already use. Nothing
 * here ever touches [com.folium.reader.core.pdf.ByteBoundedPageCache] or builds an
 * [android.graphics.Bitmap]: the bytes handed to [store] are exactly
 * [com.folium.reader.core.pdf.Raster.rgba], the engine's own output.
 *
 * A fill render is gated exactly like [ThumbnailRenderer]'s own render: [gate]'s own
 * [DocumentPriorityGate.awaitOcrPermit] is taken before rendering, and the resulting permit is
 * wrapped into the [CancellationSignal] the engine renders under, so a foreground render begun
 * mid-fill aborts it immediately rather than making it wait — an aborted page is simply attempted
 * again on a later iteration, since nothing here ever forgets which pages are still missing from
 * disk. That permit is only taken once [gate]'s own [DocumentPriorityGate.awaitIdlePermit] has been
 * granted with a quiet period of [DISK_CACHE_FILL_IDLE_QUIET_MILLIS], which is what keeps a fill
 * from ever starting in the gap between two of the reader's own page turns in the first place.
 *
 * A page within [DISK_CACHE_FILL_WINDOW_RADIUS_PAGES] of the current one is always filled the moment
 * it is chosen, since that window is what makes a jump or a page turn instant. A page beyond it is
 * paced to at most one every [DISK_CACHE_FILL_FAR_PAGE_INTERVAL_MILLIS] — see [awaitFarPageInterval] —
 * so the fill of a long document does not keep a CPU core busy for minutes after the part a reader
 * can actually feel is already done.
 */
internal class DiskCacheFiller(
    private val document: ReaderDocument,
    private val pdf: PdfDocument,
    private val gate: DocumentPriorityGate,
    private val store: DiskPageCacheStore,
    private val engineId: String,
    private val contentId: String,
    internal val layoutVersion: String?,
    private val target: () -> DiskCacheFillTarget?,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    threadFactory: (Runnable) -> Thread = { runnable ->
        Thread(runnable, "reader-disk-fill").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
) {
    @Volatile private var stopping = false

    /** Null until the first far page is filled, so pacing only ever delays the second one onward. */
    private var lastFarFillAtMillis: Long? = null

    /**
     * Keys this filler has already seen on disk or handed to [store]. Only the worker touches it.
     *
     * A write lands some milliseconds after it is offered, and until it does [store] still reports
     * the key missing, so a filler that asked again on every pass rendered the same page two or
     * three times and re-checked every earlier page's file each time. A key carries its spec, so a
     * changed viewport or layout misses here on its own. A write the store drops after accepting it
     * is left for a later session rather than retried.
     */
    private val knownKeys = HashSet<DiskPageCacheKey>()
    private val worker = threadFactory(Runnable(::runLoop))

    fun start() {
        worker.start()
    }

    /** Non-blocking: asks the loop to stop at its next chance to notice. */
    fun close() {
        stopping = true
    }

    /** Blocking: must not run on the main thread, and only ever called after [close]. */
    fun dispose() {
        close()
        var interrupted = false
        while (worker.isAlive) {
            try {
                worker.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun isStopping(): Boolean = stopping

    private fun runLoop() {
        while (!isStopping()) {
            val idle = traced({ "folium:fill:wait:idle" }) {
                gate.awaitIdlePermit(DISK_CACHE_FILL_IDLE_QUIET_MILLIS, cancelled = ::isStopping)
            }
            if (!idle) continue

            if (!store.hasWriteCapacity()) {
                sleep(DISK_CACHE_FILL_QUEUE_BACKOFF_MILLIS)
                continue
            }

            val fillTarget = target()
            if (fillTarget == null) {
                sleep(DISK_CACHE_FILL_NO_WORK_POLL_MILLIS)
                continue
            }

            val candidate = nextDiskCacheFillPage(
                fillTarget.currentPage,
                fillTarget.pageCount,
                DISK_CACHE_FILL_WINDOW_RADIUS_PAGES
            ) { page -> isAlreadyFilled(page, fillTarget.specForPage) }

            if (candidate == null) {
                sleep(DISK_CACHE_FILL_NO_WORK_POLL_MILLIS)
                continue
            }

            if (!candidate.isWithinWindow && !awaitFarPageInterval(fillTarget.currentPage)) continue

            fillPage(candidate.pageIndex, fillTarget.specForPage)
            if (!candidate.isWithinWindow) lastFarFillAtMillis = nowMillis()
        }
    }

    /**
     * Blocks the worker, without holding any gate permit, until [DISK_CACHE_FILL_FAR_PAGE_INTERVAL_MILLIS]
     * have passed since the last page outside the near window was filled. Returns false — abandoning the
     * wait without filling anything — the moment [close] is called or [currentPageAtStart] no longer
     * matches the live target, so a reader who moves elsewhere is never kept waiting on a far page that
     * stopped being the most useful thing to fill the instant the near window around their new position
     * needs attention instead.
     */
    private fun awaitFarPageInterval(currentPageAtStart: Int): Boolean {
        val lastFarFill = lastFarFillAtMillis ?: return true

        while (true) {
            if (isStopping()) return false

            val elapsedMillis = nowMillis() - lastFarFill
            if (elapsedMillis >= DISK_CACHE_FILL_FAR_PAGE_INTERVAL_MILLIS) return true

            if (target()?.currentPage != currentPageAtStart) return false

            sleep(minOf(DISK_CACHE_FILL_FAR_WAIT_POLL_MILLIS, DISK_CACHE_FILL_FAR_PAGE_INTERVAL_MILLIS - elapsedMillis))
        }
    }

    private fun isAlreadyFilled(pageIndex: Int, specForPage: (Int) -> RenderSpec?): Boolean {
        val key = diskKeyFor(pageIndex, specForPage) ?: return true
        if (key in knownKeys) return true

        val present = store.containsKey(key)
        if (present) {
            knownKeys += key
            traced({ "folium:fill:skip:$pageIndex" }) {}
        }

        return present
    }

    private fun diskKeyFor(pageIndex: Int, specForPage: (Int) -> RenderSpec?): DiskPageCacheKey? {
        val spec = specForPage(pageIndex) ?: return null
        return DiskPageCacheKey.forWholePageSpec(engineId, contentId, layoutVersion, pageIndex, spec)
    }

    /**
     * Measures [pageIndex] if nobody has yet, then renders it once under the spec its real shape
     * implies and writes it through to [store]. A page nobody has measured only has the shape assumed
     * from the first page, and a raster stored under a spec built from that assumption is one the
     * reader never asks for. Measuring first also lets a page stored by an earlier session be
     * recognised before any rendering: until then its key cannot be named.
     *
     * The measurement is not reported to the presenter the way [PdfPageRenderer] reports one: a fill
     * only targets pages nothing has laid out yet, so there is no layout to correct. It lands in the
     * same [ReaderDocument] the presenter reads shapes from, so the reader's own request for the page
     * is priced correctly the first time.
     */
    private fun fillPage(pageIndex: Int, specForPage: (Int) -> RenderSpec?) = traced({ "folium:fill:page:$pageIndex" }) {
        val permit = gate.awaitOcrPermit(::isStopping) ?: return@traced
        val cancellationSignal = CancellationSignal { isStopping() || gate.isPreempted(permit) }
        if (cancellationSignal.isCancelled()) return@traced

        if (!measure(pageIndex)) {
            val assumedSpec = specForPage(pageIndex) ?: return@traced
            return@traced abortOrGiveUp(pageIndex, assumedSpec, cancellationSignal)
        }
        if (isAlreadyFilled(pageIndex, specForPage)) return@traced

        val spec = specForPage(pageIndex) ?: return@traced
        val raster = renderOrNull(pageIndex, spec, cancellationSignal)
            ?: return@traced abortOrGiveUp(pageIndex, spec, cancellationSignal)
        if (cancellationSignal.isCancelled()) return@traced abortTrace(pageIndex)

        val key = DiskPageCacheKey.forWholePageSpec(engineId, contentId, layoutVersion, pageIndex, spec) ?: return@traced
        store.enqueueWrite(key, raster.rgba, document.aspect(pageIndex))
        knownKeys += key
    }

    private fun measure(pageIndex: Int): Boolean = try {
        document.measureIfUnknown(pageIndex) { index -> pdf.pageInfo(index).let { it.width / it.height } }
        true
    } catch (_: RuntimeException) {
        false
    }

    private fun renderOrNull(
        pageIndex: Int,
        spec: RenderSpec,
        cancellationSignal: CancellationSignal
    ) = try {
        pdf.renderPage(pageIndex, spec, cancellationSignal)
    } catch (_: PdfException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun abortTrace(pageIndex: Int) = traced({ "folium:fill:abort:$pageIndex" }) {}

    /**
     * A render that came back empty because it was preempted is tried again on a later pass. One
     * that failed with nothing preempting it would fail the same way every time, and the loop
     * would spin on it, so the page is left alone for the rest of the session.
     */
    private fun abortOrGiveUp(pageIndex: Int, spec: RenderSpec, cancellationSignal: CancellationSignal) {
        if (!cancellationSignal.isCancelled()) {
            DiskPageCacheKey.forWholePageSpec(engineId, contentId, layoutVersion, pageIndex, spec)?.let { knownKeys += it }
        }

        abortTrace(pageIndex)
    }
}
