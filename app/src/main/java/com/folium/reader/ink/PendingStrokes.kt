package com.folium.reader.ink

import com.folium.reader.core.ink.InkTip
import com.folium.reader.core.ink.InkTool

/** The settings a stroke was started with, which the finished stroke must be recorded under. */
data class PendingStrokeMeta(val tool: InkTool, val tip: InkTip, val colorArgb: Int, val widthSheetUnits: Float)

/**
 * Pen settings of strokes that have started but whose finished geometry has not been delivered yet,
 * keyed by the stroke's own identity.
 *
 * A stroke finishes asynchronously, after the pen has lifted, and nothing stops the next stroke from
 * starting, or the pen settings from changing, before that delivery arrives. Keeping the settings
 * per stroke rather than in one shared slot means a late delivery is still recorded with the
 * settings it was drawn with, and a delivery for a stroke this registry no longer knows is resolved
 * through a fallback instead of being dropped: a finished stroke is the user's writing, and losing
 * it is never an acceptable outcome of a bookkeeping miss.
 *
 * Not thread-safe; owned by the UI thread.
 */
class PendingStrokes<K : Any> {
    private val metaByStroke = HashMap<K, PendingStrokeMeta>()

    val size: Int get() = metaByStroke.size

    fun register(stroke: K, meta: PendingStrokeMeta) {
        metaByStroke[stroke] = meta
    }

    /** Forgets [stroke], for a stroke that was cancelled and so will never be delivered. */
    fun discard(stroke: K) {
        metaByStroke.remove(stroke)
    }

    /** The settings [stroke] was started with, or [fallback]'s answer when it is unknown here; forgets it either way. */
    fun resolve(stroke: K, fallback: () -> PendingStrokeMeta): PendingStrokeMeta =
        metaByStroke.remove(stroke) ?: fallback()
}
