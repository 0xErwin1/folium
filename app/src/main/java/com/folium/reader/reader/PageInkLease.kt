package com.folium.reader.reader

import com.folium.reader.core.ink.OpenPageInk
import com.folium.reader.core.ink.PageInkAlreadyOpenException
import java.util.concurrent.Executor

/** The most pages [PageInkLease] holds open at once: the two pages of a spread. */
const val MAX_LIVE_PAGE_INK_PAGES = 2

/** Where one wanted page's ink writer stands: what [PageInkLease] last published for [page]. */
sealed interface PageInkState {
    val page: Int

    /** [page] is wanted and its writer is not open yet. */
    data class Opening(override val page: Int) : PageInkState

    /** [page] is wanted and [ink] is its open writer, ready for a surface to draw on. */
    data class Live(override val page: Int, val ink: OpenPageInk) : PageInkState

    /**
     * [page] is wanted and could not be opened: [openElsewhere] when another writer already holds it,
     * [boundElsewhere] when the book's ink belongs to another version of its file (see
     * [PageInkBoundElsewhereException]), otherwise any other failure. Wanting it again tries again.
     */
    data class Unavailable(
        override val page: Int,
        val openElsewhere: Boolean,
        val boundElsewhere: Boolean = false
    ) : PageInkState
}

/**
 * Owns the [OpenPageInk] writers the reader draws on live, at most [MAX_LIVE_PAGE_INK_PAGES] at a
 * time, all from one book's [com.folium.reader.core.ink.PageInkStore].
 *
 * Every method runs on the main thread. Opening and closing are blocking I/O and run on [work], which
 * must be serial (the activity's `documentWork`), so a close queued before an open of the same page
 * always lands first; their outcomes come back through [main], and every state change is handed to
 * [onState] as the full map of wanted pages.
 *
 * Invariants:
 * - A page is held by at most one writer. Different pages open and close independently.
 * - A held page a surface has [attach]ed is let go only by [release], which the surface calls once it
 *   has left composition and flushed its own drawing; closing it any earlier would race that flush
 *   over the same log. A held page no surface attached to is let go as soon as it is no longer wanted.
 * - An open still in flight when its page stops being wanted is closed and discarded when it
 *   completes.
 * - Once a released page's writer is closed, [onPageInkChanged] reports that page on [main], so a
 *   read-only view of the page can reload what the surface wrote.
 */
class PageInkLease(
    private val open: (Int) -> OpenPageInk,
    private val work: Executor,
    private val main: Executor,
    private val onState: (Map<Int, PageInkState>) -> Unit,
    private val onPageInkChanged: (Int) -> Unit,
    private val close: (OpenPageInk) -> Unit = OpenPageInk::close
) {
    var states: Map<Int, PageInkState> = emptyMap()
        private set

    private var wanted: Set<Int> = emptySet()
    private val held = HashMap<Int, OpenPageInk>()
    private val attached = HashSet<Int>()
    private val opening = HashSet<Int>()
    private val unavailable = HashMap<Int, PageInkState.Unavailable>()
    private var disposed = false

    /**
     * Wants exactly [pages] live, at most [MAX_LIVE_PAGE_INK_PAGES] of them. A page already held is
     * republished rather than reopened, a held page no longer wanted is let go unless a surface still
     * draws on it, and a wanted page that was [PageInkState.Unavailable] is tried again.
     */
    fun want(pages: Set<Int>) {
        require(pages.size <= MAX_LIVE_PAGE_INK_PAGES) { "at most $MAX_LIVE_PAGE_INK_PAGES pages may be live, wanted $pages" }
        if (disposed) return

        wanted = pages.toSet()
        unavailable.clear()

        letGoUnattachedUnwanted()
        openMissing()
        publish()
    }

    /** A surface now draws on [ink]: from here on only [release] lets it go. */
    fun attach(ink: OpenPageInk) {
        if (held[ink.pageIndex] === ink) attached += ink.pageIndex
    }

    /**
     * The surface drawing on [ink] has left composition and flushed its drawing: [ink] is closed, its
     * page reported through [onPageInkChanged], and the page reopened if it is still wanted. An [ink]
     * this lease no longer holds is ignored, so a second release closes nothing twice.
     *
     * The reopen is started from a later [main] turn rather than right away, for the same reason
     * [SheetWriterLease.release] defers its own: when the whole reader leaves composition the surface
     * is released before the reader's [dispose], and deferring lets that [dispose] cancel the reopen
     * whichever order the two arrive in.
     */
    fun release(ink: OpenPageInk) {
        val page = ink.pageIndex
        if (held[page] !== ink) return

        held.remove(page)
        attached -= page
        letGo(ink, reportChanged = true)

        if (disposed) return

        publish()
        main.execute {
            if (disposed) return@execute

            openMissing()
            publish()
        }
    }

    /**
     * The reader is going away. Held pages no surface draws on are let go now; a page a surface still
     * draws on is left for that surface's own [release], and an open still in flight is closed when it
     * completes.
     */
    fun dispose() {
        disposed = true
        wanted = emptySet()
        letGoUnattachedUnwanted()
    }

    private fun letGoUnattachedUnwanted() {
        val released = held.filterKeys { page -> page !in wanted && page !in attached }

        for ((page, ink) in released) {
            held.remove(page)
            letGo(ink, reportChanged = false)
        }
    }

    private fun letGo(ink: OpenPageInk, reportChanged: Boolean) {
        work.execute {
            runCatching { close(ink) }
            if (reportChanged) main.execute { if (!disposed) onPageInkChanged(ink.pageIndex) }
        }
    }

    private fun openMissing() {
        for (page in wanted.sorted()) {
            if (page in held || page in opening || page in unavailable) continue

            opening += page
            work.execute {
                val result = runCatching { open(page) }
                main.execute { onOpened(page, result) }
            }
        }
    }

    private fun onOpened(page: Int, result: Result<OpenPageInk>) {
        opening -= page

        if (disposed || page !in wanted) {
            result.onSuccess { late -> work.execute { runCatching { close(late) } } }
            return
        }

        result
            .onSuccess { ink -> held[page] = ink }
            .onFailure { error ->
                unavailable[page] = PageInkState.Unavailable(
                    page = page,
                    openElsewhere = error is PageInkAlreadyOpenException,
                    boundElsewhere = error is PageInkBoundElsewhereException
                )
            }

        publish()
    }

    private fun publish() {
        val next = wanted.sorted().associateWith { page ->
            held[page]?.let { ink -> PageInkState.Live(page, ink) }
                ?: unavailable[page]
                ?: PageInkState.Opening(page)
        }

        states = next
        onState(next)
    }
}
