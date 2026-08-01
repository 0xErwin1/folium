package com.folium.reader.library

import com.folium.reader.core.library.LibraryLoadResult
import com.folium.reader.core.library.LibraryRepository
import com.folium.reader.core.library.LibraryState
import com.folium.reader.core.library.LibraryStateReducer
import com.folium.reader.saf.LibraryRootBinder
import com.folium.reader.saf.SafRootResult

/**
 * Resolves the library the reader should see: recover the persisted root, and only if that
 * succeeds enumerate its documents.
 *
 * Enumeration opens a file descriptor per candidate, so [load] blocks and must be called off the
 * main thread.
 */
class LibrarySession(
    private val root: LibraryRootBinder,
    private val library: LibraryRepository
) {
    fun load(): LibraryState {
        val rootResult = root.recover()

        if (rootResult is SafRootResult.Unavailable) {
            return LibraryStateReducer.reduce(LibraryLoadResult.Unavailable(rootResult.failure.recovery))
        }

        return LibraryStateReducer.reduce(library.loadLibrary())
    }
}
