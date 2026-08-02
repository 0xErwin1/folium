package com.folium.reader.core.library

/** Opaque, generated identity for an imported book — also its storage directory name. */
data class BookId(val value: String) {
    init { requireOpaque(value, "BookId") }
}

/**
 * A book the app has imported and stored a copy of. [title] is presentation-only and carries no
 * opacity precondition beyond being non-blank after control-character stripping, which happens at
 * import time; this constructor only rejects.
 */
data class LibraryBook(
    val id: BookId,
    val title: String,
    val pageCount: Int,
    val addedAtMillis: Long
) {
    init {
        require(pageCount > 0) { "pageCount must be positive, was $pageCount" }
        require(addedAtMillis >= 0) { "addedAtMillis must be non-negative, was $addedAtMillis" }
        require(title.isNotBlank() && title.none { it.isISOControl() }) {
            "title must be non-blank and free of control characters"
        }
    }
}

/**
 * A book paired with its stored reading position. [pageIndex] is clamped into `0 until
 * book.pageCount` here, which is where a progress row that outlived a re-imported book is
 * neutralised rather than propagated as an out-of-range page.
 */
@ConsistentCopyVisibility
data class ShelfEntry private constructor(val book: LibraryBook, val pageIndex: Int) {
    val displayPage: Int get() = pageIndex + 1
    val fraction: Float get() = displayPage.toFloat() / book.pageCount.toFloat()

    companion object {
        operator fun invoke(book: LibraryBook, pageIndex: Int): ShelfEntry =
            ShelfEntry(book, pageIndex.coerceIn(0, book.pageCount - 1))
    }
}
