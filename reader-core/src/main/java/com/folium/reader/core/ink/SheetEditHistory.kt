package com.folium.reader.core.ink

/**
 * A change to a sheet's strokes. Both variants carry the full [InkStroke] list rather than just
 * their ids, so undoing a removal restores every stroke exactly as it was, [InkStroke.sequence]
 * included, with nothing to look up from anywhere else.
 */
sealed class SheetEdit {
    data class AddStrokes(val strokes: List<InkStroke>) : SheetEdit()
    data class RemoveStrokes(val strokes: List<InkStroke>) : SheetEdit()

    /** The opposite edit: undoing [AddStrokes] is removing the same strokes, and vice versa. */
    fun inverse(): SheetEdit = when (this) {
        is AddStrokes -> RemoveStrokes(strokes)
        is RemoveStrokes -> AddStrokes(strokes)
    }
}

/**
 * Tracks a sheet's edit history so a caller can undo and redo changes against its own stroke
 * store; this class only ever hands back the [SheetEdit] to execute against that store, it never
 * touches strokes itself.
 *
 * Not thread-safe: it is meant for a single editing session driven from one thread, the same way
 * the sheet it edits is being drawn on from one thread.
 *
 * [apply] records a new edit and clears the redo stack, since a new edit invalidates whatever was
 * previously available to redo. The undo stack is bounded by [capacity]; once full, the oldest
 * recorded edit is dropped to make room for the newest one, so a very long editing session cannot
 * grow this history without bound.
 */
class SheetEditHistory(private val capacity: Int = DEFAULT_CAPACITY) {
    init { require(capacity > 0) { "capacity must be positive, was $capacity" } }

    private val undoStack = ArrayDeque<SheetEdit>()
    private val redoStack = ArrayDeque<SheetEdit>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun apply(op: SheetEdit) {
        undoStack.addLast(op)
        if (undoStack.size > capacity) undoStack.removeFirst()
        redoStack.clear()
    }

    /** The edit to execute against the store to undo the most recent recorded edit, or null when there is none. */
    fun undo(): SheetEdit? {
        val op = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(op)
        return op.inverse()
    }

    /** The edit to execute against the store to redo the most recently undone edit, or null when there is none. */
    fun redo(): SheetEdit? {
        val op = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(op)
        return op
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 100
    }
}
