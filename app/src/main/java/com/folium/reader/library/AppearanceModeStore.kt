package com.folium.reader.library

import com.folium.reader.core.library.APPEARANCE_MODE_VERSION_MARKER
import com.folium.reader.core.library.AppearanceMode
import com.folium.reader.core.library.AppearanceModes

/** Persists Folium's global appearance choice on the library worker thread. */
class AppearanceModeStore(paths: LibraryPaths) {
    private val file = AtomicTextFile(paths.appearanceModeFile)

    fun read(): AppearanceMode {
        val lines = file.readLines()
        if (lines.firstOrNull() != APPEARANCE_MODE_VERSION_MARKER) return AppearanceModes.DEFAULT

        return AppearanceModes.decode(lines.getOrNull(1))
    }

    /** Returns whether the write succeeded. */
    fun write(mode: AppearanceMode): Boolean =
        file.write(listOf(APPEARANCE_MODE_VERSION_MARKER, AppearanceModes.encode(mode)))
}
