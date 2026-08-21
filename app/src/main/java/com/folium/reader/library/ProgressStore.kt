package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryRecords
import com.folium.reader.core.library.PROGRESS_VERSION_MARKER
import com.folium.reader.core.library.PROGRESS_VERSION_MARKER_V1
import com.folium.reader.core.library.ProgressRecord
import com.folium.reader.core.pdf.ReadingPositionToken

/**
 * The reading position of every book that has one, stored as a version-marked flat file with the
 * same malformed-is-empty discipline as [BookCatalogStore]. Either version marker is read, so a
 * file written before pagination and a position token existed survives untouched; any write
 * upgrades the whole file to [PROGRESS_VERSION_MARKER]. Called only from the library worker thread.
 */
class ProgressStore(paths: LibraryPaths) {
    private val file = AtomicTextFile(paths.progressFile)

    fun read(): List<ProgressRecord> {
        val lines = file.readLines()
        if (lines.firstOrNull() != PROGRESS_VERSION_MARKER && lines.firstOrNull() != PROGRESS_VERSION_MARKER_V1) {
            return emptyList()
        }
        return lines.drop(1).mapNotNull(LibraryRecords::decodeProgress)
    }

    /** Replaces any existing record for [id] — last write wins per book. Returns whether it succeeded. */
    fun put(id: BookId, pageIndex: Int, pageCount: Int = 0, token: ReadingPositionToken? = null): Boolean =
        writeAll(read().filterNot { it.bookId == id } + ProgressRecord(id, pageIndex, pageCount, token))

    /** Returns whether the removal succeeded. */
    fun remove(id: BookId): Boolean = writeAll(read().filterNot { it.bookId == id })

    private fun writeAll(records: List<ProgressRecord>): Boolean =
        file.write(listOf(PROGRESS_VERSION_MARKER) + records.map(LibraryRecords::encodeProgress))
}
