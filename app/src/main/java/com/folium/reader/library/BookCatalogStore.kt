package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.CATALOG_VERSION_MARKER
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.library.LibraryRecords

/**
 * The catalog of imported books, stored as a version-marked flat file. Reads drop malformed lines
 * rather than failing, and treat a file whose first line is not the exact version marker as
 * empty; writes rewrite the whole file, which is proportional to a personal library of tens of
 * rows. Called only from the library worker thread.
 */
class BookCatalogStore(paths: LibraryPaths) {
    private val file = AtomicTextFile(paths.catalogFile)

    fun read(): List<LibraryBook> {
        val lines = file.readLines()
        if (lines.firstOrNull() != CATALOG_VERSION_MARKER) return emptyList()
        return lines.drop(1).mapNotNull(LibraryRecords::decodeBook)
    }

    /** Returns whether the append succeeded. */
    fun append(book: LibraryBook): Boolean = writeAll(read() + book)

    /** Returns whether the removal succeeded. */
    fun remove(id: BookId): Boolean = writeAll(read().filterNot { it.id == id })

    private fun writeAll(books: List<LibraryBook>): Boolean =
        file.write(listOf(CATALOG_VERSION_MARKER) + books.map(LibraryRecords::encodeBook))
}
