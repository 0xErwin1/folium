package com.folium.reader.library

import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.ink.SheetStore
import com.folium.reader.core.ink.SheetSummary
import com.folium.reader.core.library.BookId

/**
 * What removing a book needs from the handwritten sheets anchored to it. Every call is blocking I/O
 * made on the library worker; any of them may throw, and a sheet that cannot be changed is left as
 * it was rather than failing the removal.
 */
interface BookSheets {
    /** The readable sheets anchored to [book]. A sheet whose metadata cannot be read is never included. */
    fun anchoredTo(book: BookId): List<SheetSummary>

    /** Makes [id] a standalone sheet named [title]. See [SheetStore.detach]. */
    fun detach(id: SheetId, title: String)

    /** Deletes [id] and everything written on it. See [SheetStore.delete]. */
    fun delete(id: SheetId)
}

/** [BookSheets] over the app's one [SheetStore], so its one-writer-per-sheet rule covers removal too. */
class SheetStoreBookSheets(private val store: SheetStore) : BookSheets {
    override fun anchoredTo(book: BookId): List<SheetSummary> = store.list(anchoredTo = book).sheets

    override fun detach(id: SheetId, title: String) = store.detach(id, title)

    override fun delete(id: SheetId) = store.delete(id)
}

/**
 * The title a sheet keeps once the book it was anchored to is removed: its own title, led by the
 * book's the way the reader names a new sheet, so the shelf still says where it came from. A title
 * that already names the book — every sheet the reader creates starts as one — is kept as it is.
 */
internal fun detachedSheetTitle(sheetTitle: String, bookTitle: String): String =
    if (sheetTitle.contains(bookTitle)) sheetTitle else "$bookTitle · $sheetTitle"
