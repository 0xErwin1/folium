package com.folium.reader.ink

import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.SheetEdit
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.StrokeId
import com.folium.reader.core.ink.erasePartially

/**
 * One erase-part-of-a-stroke gesture, from the strokes live when the pointer went down to whatever
 * remains once it lifts. Pure and Android-free, so [InkDrawingSurface] can drive it on the UI thread
 * without any of it touching a view.
 *
 * A single gesture can pass over the same ink more than once — the eraser doubling back, or a stroke
 * already split once by an earlier pass in the same gesture getting cut again — so this tracks its own
 * live set of strokes rather than delegating straight to [erasePartially] per call. [apply] erases
 * against that live set and reports what changed on screen for this one call; [result] reports the
 * gesture's net effect once it ends.
 */
class PartialEraseSession(initialStrokes: List<InkStroke>) {

    /** The gesture's own starting strokes, keyed by id, kept aside so [result] can tell them apart from fragments. */
    private val originalsById: Map<StrokeId, InkStroke> = initialStrokes.associateBy { it.id }

    /** Every stroke still standing as of the last [apply] call: originals untouched so far, and any fragment born from one that was cut. */
    private val live = LinkedHashMap<StrokeId, InkStroke>(originalsById)

    /** What one [apply] call changed on screen: strokes to stop showing, and fragments to start showing, both in no particular order. */
    data class PartialEraseStep(val removedNow: List<InkStroke>, val addedNow: List<InkStroke>)

    /**
     * Runs [erasePartially] with [eraserRadius] against every currently live stroke [eraserSegment]
     * touches, replacing each one it cuts with its surviving fragments in this session's own live set.
     * [newId] and [newSequence] mint identity for every fragment created, exactly as a fresh stroke
     * would get one.
     */
    fun apply(eraserSegment: List<SheetPoint>, eraserRadius: Float, newId: () -> StrokeId, newSequence: () -> Long): PartialEraseStep {
        val removedNow = mutableListOf<InkStroke>()
        val addedNow = mutableListOf<InkStroke>()

        for (stroke in live.values.toList()) {
            val fragments = erasePartially(stroke, eraserSegment, eraserRadius, newId, newSequence) ?: continue

            live.remove(stroke.id)
            removedNow += stroke

            for (fragment in fragments) {
                live[fragment.id] = fragment
                addedNow += fragment
            }
        }

        return PartialEraseStep(removedNow, addedNow)
    }

    /**
     * The gesture's net effect as one [SheetEdit.ReplaceStrokes], or `null` when nothing this session
     * ever touched is still different from how the gesture started. [SheetEdit.ReplaceStrokes.removed]
     * is every original stroke no longer live, regardless of how many passes it took; [added] is every
     * fragment still live at the end — a fragment cut again inside the same gesture never enters either
     * list, since it is gone before this is ever asked for.
     */
    fun result(): SheetEdit.ReplaceStrokes? {
        val removed = originalsById.values.filter { original -> original.id !in live }
        val added = live.values.filter { stroke -> stroke.id !in originalsById }

        if (removed.isEmpty() && added.isEmpty()) return null
        return SheetEdit.ReplaceStrokes(removed = removed, added = added)
    }

    /** The strokes this gesture started with, for a caller to restore if the gesture is cancelled or its [result] is refused. */
    fun originals(): List<InkStroke> = originalsById.values.toList()
}
