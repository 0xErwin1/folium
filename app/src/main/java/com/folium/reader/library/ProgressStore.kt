package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryRecords
import com.folium.reader.core.library.PROGRESS_VERSION_MARKER
import com.folium.reader.core.library.ProgressRecord

/**
 * The reading position of every book that has one, stored as a version-marked flat file with the
 * same malformed-is-empty discipline as [BookCatalogStore]. Called only from the library worker
 * thread.
 */
class ProgressStore(paths: LibraryPaths) {
    private val file = AtomicTextFile(paths.progressFile)

    fun read(): List<ProgressRecord> {
        val lines = file.readLines()
        if (lines.firstOrNull() != PROGRESS_VERSION_MARKER) return emptyList()
        return lines.drop(1).mapNotNull(LibraryRecords::decodeProgress)
    }

    /** Replaces any existing record for [id] — last write wins per book. Returns whether it succeeded. */
    fun put(id: BookId, pageIndex: Int): Boolean =
        writeAll(read().filterNot { it.bookId == id } + ProgressRecord(id, pageIndex))

    /** Returns whether the removal succeeded. */
    fun remove(id: BookId): Boolean = writeAll(read().filterNot { it.bookId == id })

    private fun writeAll(records: List<ProgressRecord>): Boolean =
        file.write(listOf(PROGRESS_VERSION_MARKER) + records.map(LibraryRecords::encodeProgress))
}
