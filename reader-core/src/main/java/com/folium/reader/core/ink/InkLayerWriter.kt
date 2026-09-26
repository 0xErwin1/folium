package com.folium.reader.core.ink

import java.io.Closeable

/**
 * One open, writable layer of ink: a sheet ([OpenSheet]) or a book page ([OpenPageInk]). A drawing
 * surface reads the layer's live items once, hands out sequences for what it draws, and appends every
 * change through [apply], without knowing which kind of layer it writes to.
 *
 * Blocking, synchronous I/O with no internal locking: the caller owns whatever single thread drives a
 * given layer, and never runs it on the main thread.
 */
interface InkLayerWriter : Closeable {
    /** Every live stroke and text box, ordered by their shared sequence. */
    fun items(): List<SheetItem>

    /** Every live stroke, ordered by [InkStroke.sequence]. */
    fun strokes(): List<InkStroke>

    /** Every live text box, ordered by [SheetTextBox.sequence]. */
    fun textBoxes(): List<SheetTextBox>

    /** The sequence number to give the next new item; never collides with one ever recorded, live or removed. */
    fun nextSequence(): Long

    /** Appends [edit] to the layer's log. */
    fun apply(edit: SheetEdit)

    /** Forces every appended edit to disk. */
    fun flush()

    /** Rewrites the layer's log to hold only its live items. */
    fun compact()
}
