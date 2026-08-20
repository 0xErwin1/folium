package com.folium.reader.core.library

/** First line of an app-managed catalog file; a file whose first line differs is treated as empty. */
const val CATALOG_VERSION_MARKER = "folium-catalog 1"

/** First line of an app-managed progress file; a file whose first line differs is treated as empty. */
const val PROGRESS_VERSION_MARKER = "folium-progress 1"

/** A single stored reading position, decoupled from the [LibraryBook] it may or may not still match. */
data class ProgressRecord(val bookId: BookId, val pageIndex: Int) {
    init { require(pageIndex >= 0) { "pageIndex must be non-negative, was $pageIndex" } }
}

private const val FIELD_SEPARATOR = ''

/** How a title's origin is written: declared by the document, or derived from the file name. */
private const val DECLARED_TITLE = "1"
private const val DERIVED_TITLE = "0"

/**
 * Pure line codecs for the catalog and progress files — no file handling, that stays with the
 * caller. Fields are joined with a unit separator rather than escaped: every field value is
 * either an integer or a string already guaranteed free of control characters ([BookId]'s opacity
 * rule, or control-stripping applied before a title reaches [LibraryBook]), so the separator can
 * never occur inside a field and there is no escape sequence to get wrong.
 */
object LibraryRecords {
    fun encodeBook(book: LibraryBook): String = listOf(
        book.id.value,
        book.title,
        book.pageCount.toString(),
        book.addedAtMillis.toString(),
        book.author.orEmpty(),
        when (book.titleDeclared) {
            true -> DECLARED_TITLE
            false -> DERIVED_TITLE
            null -> ""
        }
    ).joinToString(FIELD_SEPARATOR.toString())

    /**
     * `null` means the line is malformed; the caller drops it rather than treating it as fatal.
     *
     * A four-field line is a catalog written before authors were stored, and a five-field one
     * before a title's origin was. Each decodes to a book missing only that field rather than being
     * dropped: the shelf a reader already has is not worth losing over a field that did not exist
     * when it was written.
     */
    fun decodeBook(line: String): LibraryBook? {
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size !in 4..6) return null
        val pageCount = fields[2].toIntOrNull() ?: return null
        val addedAtMillis = fields[3].toLongOrNull() ?: return null
        val author = fields.getOrNull(4)?.takeIf { it.isNotBlank() }
        val titleDeclared = when (fields.getOrNull(5)) {
            DECLARED_TITLE -> true
            DERIVED_TITLE -> false
            else -> null
        }
        return runCatching {
            LibraryBook(BookId(fields[0]), fields[1], pageCount, addedAtMillis, author, titleDeclared)
        }.getOrNull()
    }

    fun encodeProgress(record: ProgressRecord): String = listOf(
        record.bookId.value,
        record.pageIndex.toString()
    ).joinToString(FIELD_SEPARATOR.toString())

    fun decodeProgress(line: String): ProgressRecord? {
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size != 2) return null
        val (id, pageIndexField) = fields
        val pageIndex = pageIndexField.toIntOrNull() ?: return null
        return runCatching { ProgressRecord(BookId(id), pageIndex) }.getOrNull()
    }
}

/**
 * Joins a catalog with its progress records into the ordered rows a library home screen shows.
 */
object LibraryShelf {
    /**
     * Left join on [BookId]: a book with no matching progress starts at page 0. A progress record
     * with no matching book is dropped — the only reconciliation this system performs, and it is a
     * filter rather than a job. Rows are ordered by [LibraryBook.addedAtMillis] descending, with
     * [BookId.value] as a tie-break, so the order is total and deterministic.
     */
    fun entries(books: List<LibraryBook>, progress: List<ProgressRecord>): List<ShelfEntry> {
        val pageIndexByBookId = progress.associate { it.bookId to it.pageIndex }
        return books
            .map { book -> ShelfEntry(book, pageIndexByBookId[book.id] ?: 0) }
            .sortedWith(compareByDescending<ShelfEntry> { it.book.addedAtMillis }.thenBy { it.book.id.value })
    }
}
