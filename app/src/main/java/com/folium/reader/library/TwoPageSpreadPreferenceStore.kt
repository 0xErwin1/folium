package com.folium.reader.library

import com.folium.reader.core.library.TWO_PAGE_SPREAD_VERSION_MARKER
import com.folium.reader.core.library.TwoPageSpreadPreferences

/** Persists Folium's app-wide facing-page spread preference. */
class TwoPageSpreadPreferenceStore(paths: LibraryPaths) {
    private val file = AtomicTextFile(paths.twoPageSpreadFile)

    fun read(): Boolean {
        val lines = file.readLines()
        if (lines.firstOrNull() != TWO_PAGE_SPREAD_VERSION_MARKER) return TwoPageSpreadPreferences.DEFAULT

        return TwoPageSpreadPreferences.decode(lines.getOrNull(1))
    }

    /** Returns whether the write succeeded. */
    fun write(enabled: Boolean): Boolean =
        file.write(listOf(TWO_PAGE_SPREAD_VERSION_MARKER, TwoPageSpreadPreferences.encode(enabled)))
}
