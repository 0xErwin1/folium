package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.TYPOGRAPHY_COST_VERSION_MARKER

/**
 * How long a book's last re-pagination took, in milliseconds — a diagnostic, not a setting, so a
 * missing or malformed file reads as `0` rather than any richer "unknown" state.
 *
 * Called only from the library worker thread.
 */
class TypographyCostStore(private val paths: LibraryPaths) {

    fun read(id: BookId): Long {
        val lines = AtomicTextFile(paths.typographyCostFile(id)).readLines()
        if (lines.firstOrNull() != TYPOGRAPHY_COST_VERSION_MARKER) return 0L
        return lines.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 } ?: 0L
    }

    /** Returns whether the write succeeded. */
    fun write(id: BookId, millis: Long): Boolean {
        require(millis >= 0) { "millis must be non-negative, was $millis" }
        return AtomicTextFile(paths.typographyCostFile(id)).write(
            listOf(TYPOGRAPHY_COST_VERSION_MARKER, millis.toString())
        )
    }
}
