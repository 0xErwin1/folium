package com.folium.reader.core.library

/** How far a running import batch has gotten, for the "Adding k of n…" indicator. */
data class ImportProgress(val completed: Int, val total: Int)

/**
 * What the library home screen has to show. There is deliberately no empty state and no error
 * state distinct from [Shelf]: an empty library is [Shelf] with an empty [Shelf.entries], and a
 * missing or corrupt catalog is indistinguishable from an empty one by the drop-malformed rule
 * the catalog codecs already apply.
 */
sealed class LibraryHomeState {
    data object Loading : LibraryHomeState()

    data class Shelf(
        val entries: List<ShelfEntry>,
        val importing: ImportProgress? = null,
        val report: ImportReport? = null
    ) : LibraryHomeState()
}
