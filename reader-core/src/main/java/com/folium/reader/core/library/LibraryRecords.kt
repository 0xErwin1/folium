package com.folium.reader.core.library

import com.folium.reader.core.pdf.ReadingPositionToken

/** First line of an app-managed catalog file; a file whose first line differs is treated as empty. */
const val CATALOG_VERSION_MARKER = "folium-catalog 1"

/**
 * First line of a progress file written before pagination and a position token were recorded
 * alongside the page. [PROGRESS_VERSION_MARKER] is what a fresh write now stamps a file with; a
 * file carrying either marker is read.
 */
const val PROGRESS_VERSION_MARKER_V1 = "folium-progress 1"
const val PROGRESS_VERSION_MARKER = "folium-progress 2"

/**
 * A single stored reading position, decoupled from the [LibraryBook] it may or may not still
 * match. [pageCount] is the pagination the position was written under: `0` means "not recorded"
 * rather than a real count, which is what every position written before pagination was tracked
 * decodes to, and what keeps a v1 row behaving exactly as it always did. [token] locates a
 * reflowable position more precisely than [pageIndex] alone once a re-pagination has changed what
 * that index means; absent for a fixed-layout document or a position stored before it existed.
 */
data class ProgressRecord(
    val bookId: BookId,
    val pageIndex: Int,
    val pageCount: Int = 0,
    val token: ReadingPositionToken? = null
) {
    init {
        require(pageIndex >= 0) { "pageIndex must be non-negative, was $pageIndex" }
        require(pageCount >= 0) { "pageCount must be non-negative, was $pageCount" }
    }
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
    /**
     * A PDF book is written with exactly the six fields the catalog has always written, so a
     * rewrite made after formats other than PDF existed leaves every PDF row byte-identical. The
     * seventh field, the format's extension, is appended only for a non-PDF book.
     */
    fun encodeBook(book: LibraryBook): String {
        val fields = mutableListOf(
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
        )
        if (book.format != BookFormat.PDF) fields += book.format.extension
        return fields.joinToString(FIELD_SEPARATOR.toString())
    }

    /**
     * `null` means the line is malformed; the caller drops it rather than treating it as fatal.
     *
     * A four-field line is a catalog written before authors were stored, and a five-field one
     * before a title's origin was. Each decodes to a book missing only that field rather than being
     * dropped: the shelf a reader already has is not worth losing over a field that did not exist
     * when it was written.
     *
     * A six-field line is a catalog written before formats other than PDF existed, and decodes as
     * PDF because that is what it was. A seventh field present but unrecognized drops the line
     * exactly as an unparseable page count already does: falling back to PDF would point the app at
     * a document that is not really there, producing a permanently unreadable row with a plausible
     * title.
     */
    fun decodeBook(line: String): LibraryBook? {
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size !in 4..7) return null
        val pageCount = fields[2].toIntOrNull() ?: return null
        val addedAtMillis = fields[3].toLongOrNull() ?: return null
        val author = fields.getOrNull(4)?.takeIf { it.isNotBlank() }
        val titleDeclared = when (fields.getOrNull(5)) {
            DECLARED_TITLE -> true
            DERIVED_TITLE -> false
            else -> null
        }
        val format = fields.getOrNull(6)?.takeIf { it.isNotBlank() }
            ?.let { extension -> BookFormat.forExtension(extension) ?: return null }
            ?: BookFormat.PDF
        return runCatching {
            LibraryBook(BookId(fields[0]), fields[1], pageCount, addedAtMillis, author, titleDeclared, format)
        }.getOrNull()
    }

    fun encodeProgress(record: ProgressRecord): String = listOf(
        record.bookId.value,
        record.pageIndex.toString(),
        record.pageCount.toString(),
        record.token?.value.orEmpty()
    ).joinToString(FIELD_SEPARATOR.toString())

    /**
     * A two-field line is a position written before pagination and a token were tracked, and
     * decodes with [ProgressRecord.pageCount] `0` and [ProgressRecord.token] `null` — exactly what
     * every position stored that way already behaves as. A three-field line is one written before
     * the token, and decodes with the count it carries and no token.
     */
    fun decodeProgress(line: String): ProgressRecord? {
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size !in 2..4) return null
        val pageIndex = fields[1].toIntOrNull() ?: return null
        val pageCount = if (fields.size >= 3) fields[2].toIntOrNull() ?: return null else 0
        val token = if (fields.size == 4 && fields[3].isNotEmpty()) {
            runCatching { ReadingPositionToken(fields[3]) }.getOrNull() ?: return null
        } else {
            null
        }
        return runCatching { ProgressRecord(BookId(fields[0]), pageIndex, pageCount, token) }.getOrNull()
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
        val recordByBookId = progress.associateBy { it.bookId }
        return books
            .map { book ->
                val record = recordByBookId[book.id]
                ShelfEntry(book, record?.pageIndex ?: 0, record?.pageCount ?: 0)
            }
            .sortedWith(compareByDescending<ShelfEntry> { it.book.addedAtMillis }.thenBy { it.book.id.value })
    }
}
