package com.folium.reader.reader

import com.folium.reader.core.ink.OpenSheet
import com.folium.reader.core.ink.SheetAlreadyOpenException
import com.folium.reader.core.ink.SheetId
import java.util.concurrent.Executor

/** Where the reader's one live sheet stands: what [SheetWriterLease] last published. */
sealed interface SheetLeaseState {

    /** No sheet is wanted. */
    data object Idle : SheetLeaseState

    /** [id] is wanted and its writer is not open yet. */
    data class Opening(val id: SheetId) : SheetLeaseState

    /** [id] is wanted and [sheet] is its open writer, ready for a pane to draw on. */
    data class Open(val id: SheetId, val sheet: OpenSheet) : SheetLeaseState

    /**
     * [id] is wanted and could not be opened: [openElsewhere] when another writer already holds it,
     * otherwise any other failure. Asking for it again tries again.
     */
    data class Unavailable(val id: SheetId, val openElsewhere: Boolean) : SheetLeaseState
}

/**
 * Owns the one [OpenSheet] the reader draws on live, so a book's sheets can be written on in place
 * without ever holding two writers at once.
 *
 * Every method runs on the main thread. Opening and closing are blocking I/O and run on [work], which
 * must be serial — the activity's `documentWork` — so a close queued before an open always lands
 * first; their outcomes come back through [main], and every state change is handed to [onState].
 *
 * Invariants:
 * - At most one writer is held or being opened at a time. Wanting another sheet while one is held
 *   waits for that one to be let go before the next open starts, and an open still in flight when
 *   the reader moves on is closed and discarded as soon as it completes, with no thumbnail written.
 * - A held sheet a pane has [attach]ed is let go only by [release], which the pane calls once it has
 *   left composition and flushed its own drawing surface; closing it any earlier would race that
 *   flush over the same stroke log. A held sheet no pane ever attached to is let go by the lease
 *   itself as soon as it is no longer wanted.
 * - Letting go always writes the sheet's thumbnail first and closes it second, and neither failing
 *   stops the other from running.
 */
class SheetWriterLease(
    private val open: (SheetId) -> OpenSheet,
    private val writeThumbnail: (OpenSheet) -> Unit,
    private val work: Executor,
    private val main: Executor,
    private val onState: (SheetLeaseState) -> Unit,
    private val close: (OpenSheet) -> Unit = OpenSheet::close
) {
    var state: SheetLeaseState = SheetLeaseState.Idle
        private set

    private var wanted: SheetId? = null
    private var held: OpenSheet? = null
    private var attached = false
    private var opening: SheetId? = null
    private var disposed = false

    /**
     * Wants [id] on screen, or no sheet at all for `null`. Wanting the sheet already held republishes
     * it rather than reopening it, and wanting one that was [SheetLeaseState.Unavailable] tries it
     * again.
     */
    fun acquire(id: SheetId?) {
        if (disposed) return

        wanted = id

        if (id == null) {
            letGoUnattached()
            publish(SheetLeaseState.Idle)
            return
        }

        val current = held
        if (current != null && current.sheet.id == id) {
            publish(SheetLeaseState.Open(id, current))
            return
        }

        publish(SheetLeaseState.Opening(id))
        letGoUnattached()
        openNextIfFree()
    }

    /** A pane now draws on [sheet]: from here on only [release] lets it go. */
    fun attach(sheet: OpenSheet) {
        if (held === sheet) attached = true
    }

    /**
     * The pane drawing on [sheet] has left composition and flushed its surface: [sheet]'s thumbnail
     * is written and it is closed, then whatever sheet is wanted next is opened. A sheet this lease
     * no longer holds is ignored, so a second release closes nothing twice.
     */
    fun release(sheet: OpenSheet) {
        if (held !== sheet) return

        held = null
        attached = false
        letGo(sheet)

        if (disposed) return

        if (wanted == sheet.sheet.id) publish(SheetLeaseState.Opening(sheet.sheet.id))
        openNextIfFree()
    }

    /**
     * The reader is going away. A held sheet no pane is drawing on is let go now; one a pane still
     * draws on is left for that pane's own [release], and an open still in flight is closed when it
     * completes.
     */
    fun dispose() {
        disposed = true
        wanted = null
        letGoUnattached()
    }

    private fun letGoUnattached() {
        val current = held ?: return
        if (attached) return

        held = null
        letGo(current)
    }

    private fun letGo(sheet: OpenSheet) {
        work.execute {
            runCatching { writeThumbnail(sheet) }
            runCatching { close(sheet) }
        }
    }

    private fun openNextIfFree() {
        val next = wanted ?: return
        if (held != null || opening != null) return

        opening = next
        work.execute {
            val result = runCatching { open(next) }
            main.execute { onOpened(next, result) }
        }
    }

    private fun onOpened(id: SheetId, result: Result<OpenSheet>) {
        opening = null

        if (disposed || wanted != id) {
            result.onSuccess { late -> work.execute { runCatching { close(late) } } }
            if (!disposed) openNextIfFree()
            return
        }

        result
            .onSuccess { sheet ->
                held = sheet
                attached = false
                publish(SheetLeaseState.Open(id, sheet))
            }
            .onFailure { error ->
                publish(SheetLeaseState.Unavailable(id, openElsewhere = error is SheetAlreadyOpenException))
            }
    }

    private fun publish(next: SheetLeaseState) {
        state = next
        onState(next)
    }
}
