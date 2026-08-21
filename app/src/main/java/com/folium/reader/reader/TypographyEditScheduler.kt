package com.folium.reader.reader

/**
 * Turns a burst of typography edits into at most one re-pagination request, and decides when that
 * request has earned a working indicator.
 *
 * A plain class rather than composable state so it is host-testable: [postDelayed] is the same
 * cancellable-timer shape [scheduleReaderSearch] already uses for the reader's search debounce.
 *
 * [measuredCostMillis] is this book's last recorded re-pagination cost, read once when the sheet
 * opens. Above [MEASURED_COST_CUTOFF_MILLIS] the scheduler starts in [appliesLive] `false`: further
 * edits are tracked but request nothing, until [flush] fires the one re-pagination a dismissal always
 * runs. [completed] re-evaluates [appliesLive] from the re-pagination that just finished, so a book
 * that measures cheaper under a new preset returns to live re-pagination on its own.
 */
internal class TypographyEditScheduler(
    measuredCostMillis: Long,
    private val postDelayed: (Long, () -> Unit) -> (() -> Unit),
    private val onRequest: () -> Unit,
    private val onIndicatorChanged: (Boolean) -> Unit
) {
    private var cancelDebounce: (() -> Unit)? = null
    private var cancelIndicator: (() -> Unit)? = null
    private var dirty = false
    private var indicatorVisible = false

    var appliesLive: Boolean = measuredCostMillis <= MEASURED_COST_CUTOFF_MILLIS
        private set

    /** The preset already changed in memory; this only decides whether, and when, to re-paginate. */
    fun edit() {
        dirty = true
        if (!appliesLive) return

        cancelDebounce?.invoke()
        cancelDebounce = postDelayed(DEBOUNCE_MILLIS) {
            cancelDebounce = null
            dirty = false
            request()
        }
    }

    /** Called once a dispatched re-pagination finishes, whatever its outcome. */
    fun completed(elapsedMillis: Long) {
        cancelIndicator?.invoke()
        cancelIndicator = null
        if (indicatorVisible) {
            indicatorVisible = false
            onIndicatorChanged(false)
        }
        appliesLive = elapsedMillis <= MEASURED_COST_CUTOFF_MILLIS
    }

    /**
     * Fires a pending debounced edit immediately, and also fires one that [appliesLive] was
     * withholding — a dismissal always applies whatever the controls last settled on.
     */
    fun flush() {
        cancelDebounce?.invoke()
        cancelDebounce = null
        if (!dirty) return
        dirty = false
        request()
    }

    private fun request() {
        cancelIndicator?.invoke()
        cancelIndicator = postDelayed(INDICATOR_MILLIS) {
            cancelIndicator = null
            indicatorVisible = true
            onIndicatorChanged(true)
        }
        onRequest()
    }

    companion object {
        const val DEBOUNCE_MILLIS = 400L
        const val INDICATOR_MILLIS = 250L
        const val MEASURED_COST_CUTOFF_MILLIS = 1500L
    }
}
