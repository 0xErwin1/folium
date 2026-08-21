package com.folium.reader.core.library

/** Opaque, generated identity for an imported book — also its storage directory name. */
data class BookId(val value: String) {
    init { requireOpaque(value, "BookId") }
}

/**
 * The format a book's stored copy is encoded in.
 *
 * A closed enum rather than an open string, the same guard [com.folium.reader.core.library.LibraryRecords]
 * relies on elsewhere: a `when` over [BookFormat] is exhaustive, so a format this library does not
 * yet handle cannot compile past unnoticed. [extension] is deliberately three things at once — the
 * suffix the stored copy is named with, the token the catalog writes, and the string the engine
 * matches a file against — so there is no second list to keep in step with the first.
 */
enum class BookFormat(val extension: String, val mimeType: String) {
    PDF("pdf", "application/pdf"),
    EPUB("epub", "application/epub+zip");

    companion object {
        /**
         * Case-insensitive because a file name is a claim made by whoever wrote it, and the gate
         * this replaces already accepted a shouted `.PDF`.
         */
        fun forExtension(extension: String): BookFormat? =
            entries.firstOrNull { it.extension.equals(extension, ignoreCase = true) }

        /**
         * Resolves a `/`-separated path's last segment by its extension. A segment carrying no dot
         * claims no format at all, which is why the missing extension is the empty string rather
         * than the segment itself: a file named `epub` is not an EPUB.
         */
        fun forPath(path: String): BookFormat? =
            forExtension(path.substringAfterLast('/').substringAfterLast('.', ""))
    }
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
    val addedAtMillis: Long,
    /** What the document declares, when it declares one. Absent for books stored before it was read. */
    val author: String? = null,
    /**
     * Whether [title] is what the document declared, rather than a name derived from the file it
     * arrived as. Absent for books stored before the distinction was recorded, which is not the
     * same as either answer: a shelf that guessed would label the wrong rows.
     */
    val titleDeclared: Boolean? = null,
    /**
     * The format the stored copy is encoded in, which is also what its file is named. Defaults to
     * PDF because every book stored before this field existed arrived through a picker that
     * accepted nothing else.
     */
    val format: BookFormat = BookFormat.PDF
) {
    init {
        require(pageCount > 0) { "pageCount must be positive, was $pageCount" }
        require(addedAtMillis >= 0) { "addedAtMillis must be non-negative, was $addedAtMillis" }
        require(author == null || (author.isNotBlank() && author.none { it.isISOControl() })) {
            "author must be non-blank and free of control characters when present"
        }
        require(title.isNotBlank() && title.none { it.isISOControl() }) {
            "title must be non-blank and free of control characters"
        }
    }
}

/**
 * A book paired with its stored reading position. [pageCount] is the pagination [pageIndex] was
 * recorded under, which for a reflowable book can differ from [LibraryBook.pageCount] once the
 * reader's type size has changed the layout; [pageIndex] is clamped into `0 until pageCount` here,
 * which is where a progress row that outlived a re-imported book — or a re-pagination — is
 * neutralised rather than propagated as an out-of-range page.
 */
@ConsistentCopyVisibility
data class ShelfEntry private constructor(val book: LibraryBook, val pageIndex: Int, val pageCount: Int) {
    val displayPage: Int get() = pageIndex + 1
    val fraction: Float get() = displayPage.toFloat() / pageCount.toFloat()

    companion object {
        /**
         * [pageCount] `0` — the default, and what an unopened book's absent progress row means —
         * falls back to [LibraryBook.pageCount], the catalog's own count. A positive [pageCount]
         * wins over it, since it names the pagination the stored [pageIndex] actually belongs to.
         */
        operator fun invoke(book: LibraryBook, pageIndex: Int, pageCount: Int = 0): ShelfEntry {
            val effectivePageCount = pageCount.takeIf { it > 0 } ?: book.pageCount
            return ShelfEntry(book, pageIndex.coerceIn(0, effectivePageCount - 1), effectivePageCount)
        }
    }
}
