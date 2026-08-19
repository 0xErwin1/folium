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

/** The book the reader is furthest into, and everything else the shelf should show. */
internal data class ShelfPartition(val current: ShelfEntry?, val shelf: List<ShelfEntry>)

/**
 * Splits the shelf into a lifted-out current book and the grid below it.
 *
 * The current book is only lifted out of a shelf nobody has narrowed. A filter or a search is a
 * request to see exactly the books that match, and answering one with a list that silently omits a
 * match — because that match is drawn above the list instead — makes the shelf look like it lost a
 * book. Asking for the books in progress and being shown none, while the one in progress sits above
 * the empty list, is the shape that bug takes.
 */
internal fun partitionShelf(
    entries: List<ShelfEntry>,
    filter: ShelfFilter,
    query: String?,
    liftCurrent: Boolean
): ShelfPartition {
    val narrowed = filter != ShelfFilter.ALL || query != null

    val current = entries
        .takeIf { liftCurrent && !narrowed }
        ?.maxWithOrNull(compareBy(ShelfEntry::pageIndex))
        ?.takeIf { it.pageIndex > 0 }

    val shelf = entries
        .filter(filter::accepts)
        .filter { it.book.id != current?.book?.id }
        .filter { entry -> query.isNullOrBlank() || entry.book.title.contains(query, ignoreCase = true) }

    return ShelfPartition(current, shelf)
}
