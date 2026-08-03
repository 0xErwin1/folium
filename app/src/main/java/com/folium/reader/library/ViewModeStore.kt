package com.folium.reader.library

import com.folium.reader.core.library.LibraryViewMode
import com.folium.reader.core.library.LibraryViewModes
import com.folium.reader.core.library.VIEW_MODE_VERSION_MARKER

/**
 * The reader's chosen layout for the library home, stored as a version-marked flat file beside the
 * catalog — the same shape as [BookCatalogStore] and [ProgressStore], so the preference needs no
 * storage mechanism of its own.
 *
 * A missing file, a wrong version marker and an unreadable value all read back as
 * [LibraryViewModes.DEFAULT]: a preference is not worth failing a screen over. Called only from the
 * library worker thread.
 */
class ViewModeStore(paths: LibraryPaths) {
    private val file = AtomicTextFile(paths.viewModeFile)

    fun read(): LibraryViewMode {
        val lines = file.readLines()
        if (lines.firstOrNull() != VIEW_MODE_VERSION_MARKER) return LibraryViewModes.DEFAULT

        return LibraryViewModes.decode(lines.getOrNull(1))
    }

    /** Returns whether the write succeeded. */
    fun write(mode: LibraryViewMode): Boolean =
        file.write(listOf(VIEW_MODE_VERSION_MARKER, LibraryViewModes.encode(mode)))
}
