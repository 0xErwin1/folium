package com.folium.reader.library

import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.library.BookId

/** First line of a sheet-cursor file; a file whose first line differs reads as empty. */
internal const val SHEET_CURSOR_VERSION_MARKER = "folium-sheet-cursor 1"

private const val FIELD_SEPARATOR = '\u001F'

/**
 * The sheet each book was last read on, for the books whose reader was left on one: a book with no
 * row was last left on one of its pages, which [ProgressStore] already records. Kept beside the
 * progress file rather than inside it, so the progress format stays exactly what it is.
 *
 * Stored as a version-marked flat file with the same malformed-is-empty discipline as
 * [ProgressStore]: a missing file, a wrong marker or an unreadable line reads as no sheet rather than
 * failing. Both ids are opaque and free of control characters, so the unit separator between them
 * never needs escaping. Called only from the library worker thread.
 */
class SheetCursorStore(paths: LibraryPaths) {
    private val file = AtomicTextFile(paths.sheetCursorFile)

    fun read(): Map<BookId, SheetId> {
        val lines = file.readLines()
        if (lines.firstOrNull() != SHEET_CURSOR_VERSION_MARKER) return emptyMap()

        return lines.drop(1).mapNotNull(::decode).toMap()
    }

    fun get(id: BookId): SheetId? = read()[id]

    /** Replaces any sheet already stored for [id]. Returns whether the write succeeded. */
    fun put(id: BookId, sheet: SheetId): Boolean = writeAll(read() - id + (id to sheet))

    /** Returns whether the removal succeeded; removing a book with no row writes nothing. */
    fun remove(id: BookId): Boolean {
        val cursors = read()
        if (id !in cursors) return true

        return writeAll(cursors - id)
    }

    private fun writeAll(cursors: Map<BookId, SheetId>): Boolean =
        file.write(listOf(SHEET_CURSOR_VERSION_MARKER) + cursors.map { (book, sheet) -> "${book.value}$FIELD_SEPARATOR${sheet.value}" })

    private fun decode(line: String): Pair<BookId, SheetId>? {
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size != 2) return null

        return runCatching { BookId(fields[0]) to SheetId(fields[1]) }.getOrNull()
    }
}
