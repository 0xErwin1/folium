package com.folium.reader.ink

import com.folium.reader.core.ink.SheetEdit
import com.folium.reader.core.ink.SheetEditHistory

/** An edit that could not be handed to the writer, because persistence had already failed or closed. */
class InkEditRefusedException(val edit: SheetEdit) : Exception("the sheet no longer accepts edits; this one was not written")

/**
 * Keeps the edit history and the persistence queue in step: an edit enters the history only once
 * the writer has accepted it, and an undo or redo the writer refuses is rolled back, so the history
 * never describes a sheet that differs from what will be on disk.
 *
 * [enqueue] answers whether the writer accepted the edit. Not thread-safe; owned by the UI thread.
 */
class InkEditCommitter(
    private val history: SheetEditHistory,
    private val enqueue: (SheetEdit) -> Boolean
) {
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    /** Records [edit] when the writer accepts it; returns whether it did. */
    fun commit(edit: SheetEdit): Boolean {
        if (!enqueue(edit)) return false

        history.apply(edit)
        return true
    }

    /** The edit that undoes the last one, already handed to the writer, or `null` when there is none or it was refused. */
    fun undo(): SheetEdit? {
        val inverse = history.undo() ?: return null
        if (enqueue(inverse)) return inverse

        history.redo()
        return null
    }

    /** The edit that redoes the last undone one, already handed to the writer, or `null` when there is none or it was refused. */
    fun redo(): SheetEdit? {
        val forward = history.redo() ?: return null
        if (enqueue(forward)) return forward

        history.undo()
        return null
    }
}

/** The part of a freshly built [batch] whose strokes are still on the sheet, by [isLive]. */
fun <M, B> stillLive(batch: List<Pair<M, B>>, isLive: (M) -> Boolean): List<Pair<M, B>> = batch.filter { (model, _) -> isLive(model) }
