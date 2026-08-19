package com.folium.reader.library

import androidx.annotation.StringRes
import com.folium.reader.R
import com.folium.reader.core.library.ShelfEntry

/**
 * Which slice of the shelf the grid is showing.
 *
 * A library grows monotonically and never shrinks, so the useful question is not "which book" but
 * "which of the ones I have actually started". The filter answers it without a search, and it is
 * screen state rather than a stored preference: it resets with the screen, because a filter left
 * on from last week reads as a shelf that lost books.
 */
internal enum class ShelfFilter(@StringRes val label: Int, val tag: String) {
    ALL(R.string.library_filter_all, LibraryTestTags.FILTER_ALL),
    STARTED(R.string.library_filter_started, LibraryTestTags.FILTER_STARTED),
    UNOPENED(R.string.library_filter_unopened, LibraryTestTags.FILTER_UNOPENED);

    fun accepts(entry: ShelfEntry): Boolean = when (this) {
        ALL -> true
        STARTED -> entry.pageIndex > 0
        UNOPENED -> entry.pageIndex == 0
    }
}
