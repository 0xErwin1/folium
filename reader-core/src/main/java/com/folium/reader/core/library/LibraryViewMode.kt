package com.folium.reader.core.library

/** How the library home lays its books out: one book per row, or a grid of covers. */
enum class LibraryViewMode { LIST, GRID }

const val VIEW_MODE_VERSION_MARKER = "folium-view 1"

/**
 * The stored form of [LibraryViewMode], with the same drop-malformed discipline the catalog and
 * progress codecs apply: anything that is not a value this version knows reads back as [DEFAULT],
 * so a truncated, hand-edited or future file costs the reader their preference rather than the
 * screen.
 */
object LibraryViewModes {
    val DEFAULT = LibraryViewMode.LIST

    fun encode(mode: LibraryViewMode): String = when (mode) {
        LibraryViewMode.LIST -> LIST_VALUE
        LibraryViewMode.GRID -> GRID_VALUE
    }

    fun decode(value: String?): LibraryViewMode = when (value?.trim()) {
        LIST_VALUE -> LibraryViewMode.LIST
        GRID_VALUE -> LibraryViewMode.GRID
        else -> DEFAULT
    }

    private const val LIST_VALUE = "list"
    private const val GRID_VALUE = "grid"
}
