package com.folium.reader.reader

import android.os.Handler
import android.os.Looper
import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.TypographyPreset
import com.folium.reader.library.TypographyCostStore
import com.folium.reader.library.TypographyPresetStore
import java.util.concurrent.Executor

private fun scheduleTypographyRequest(delayMillis: Long, action: () -> Unit): () -> Unit {
    val handler = Handler(Looper.getMainLooper())
    val runnable = Runnable(action)
    handler.postDelayed(runnable, delayMillis)
    return { handler.removeCallbacks(runnable) }
}

/** What the typography sheet has to show. */
internal sealed class TypographySheetPhase {
    data object Loading : TypographySheetPhase()
    data class Ready(
        val preset: TypographyPreset,
        val indicatorVisible: Boolean = false,
        val appliesLive: Boolean = true
    ) : TypographySheetPhase()
}

/**
 * Owns one open sheet's typography editing: the resolved starting preset, the debounce/indicator/
 * cutoff timing (delegated to [TypographyEditScheduler]), the two scope actions, and the flush a
 * dismissal always runs.
 *
 * Every control change updates [TypographySheetPhase.Ready.preset] immediately through [edit]; only
 * the re-pagination that follows is subject to the scheduler's timing. Store reads and writes run on
 * [worker] — the same discipline [TypographyPresetStore] and [TypographyCostStore] already document
 * for themselves — and results reach [onState] on the main thread.
 */
internal class TypographySheetController(
    private val bookId: BookId,
    private val presetStore: TypographyPresetStore,
    private val costStore: TypographyCostStore,
    private val worker: Executor,
    private val mainPost: (() -> Unit) -> Unit,
    /**
     * Re-lays out the open document under [TypographyPreset], carrying whatever appearance colours
     * [ReaderHostController.setAppearanceColors] last resolved — see [ReaderHostController.applyPreset].
     */
    private val applyPreset: (TypographyPreset, (RepaginationResult) -> Unit) -> Unit,
    private val onState: (TypographySheetPhase) -> Unit,
    private val onAbandoned: () -> Unit,
    private val postDelayed: (Long, () -> Unit) -> (() -> Unit) = ::scheduleTypographyRequest
) {
    private var preset: TypographyPreset = TypographyPreset.DEFAULT
    private var indicatorVisible = false
    private var disposed = false
    private var scheduler: TypographyEditScheduler? = null

    fun start() {
        onState(TypographySheetPhase.Loading)
        worker.execute {
            val loadedPreset = presetStore.readOverride(bookId) ?: presetStore.readGlobal()
            val measuredCost = costStore.read(bookId)
            mainPost {
                if (disposed) return@mainPost
                preset = loadedPreset
                scheduler = TypographyEditScheduler(
                    measuredCostMillis = measuredCost,
                    postDelayed = postDelayed,
                    onRequest = ::dispatchRepagination,
                    onIndicatorChanged = { visible -> indicatorVisible = visible; publish() }
                )
                publish()
            }
        }
    }

    fun dispose() {
        disposed = true
    }

    /** The preset changes on screen immediately; [scheduler] alone decides when to re-paginate. */
    fun edit(next: TypographyPreset) {
        preset = next
        publish()
        scheduler?.edit()
    }

    /** Writes the resolved preset to the global default. The book's own override is left untouched. */
    fun useForAllBooks() {
        val resolved = preset
        worker.execute { presetStore.writeGlobal(resolved) }
    }

    /** Clears this book's override and re-resolves against the global default. */
    fun resetToGlobal() {
        worker.execute {
            presetStore.clearOverride(bookId)
            val global = presetStore.readGlobal()
            mainPost {
                if (disposed) return@mainPost
                preset = global
                publish()
                scheduler?.edit()
            }
        }
    }

    /**
     * Fires any pending debounced re-pagination immediately and writes whatever the controls last
     * settled on to the per-book override, before the sheet leaves.
     */
    fun dismiss() {
        scheduler?.flush()
        val resolved = preset
        worker.execute { presetStore.writeOverride(bookId, resolved) }
    }

    private fun dispatchRepagination() {
        val requested = preset
        applyPreset(requested) { result ->
            when (result) {
                is RepaginationResult.Repaginated -> {
                    worker.execute { costStore.write(bookId, result.elapsedMillis) }
                    scheduler?.completed(result.elapsedMillis)
                    publish()
                }
                RepaginationResult.Abandoned -> onAbandoned()
                RepaginationResult.Superseded -> Unit
            }
        }
    }

    private fun publish() {
        val current = scheduler ?: return
        onState(TypographySheetPhase.Ready(preset, indicatorVisible, current.appliesLive))
    }
}
