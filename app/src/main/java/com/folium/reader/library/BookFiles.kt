package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryBook
import java.io.File

/**
 * File-level access to a book's stored copy and thumbnail, and the recursive delete that removes
 * both along with the book's directory. Called only from the library worker thread.
 */
class BookFiles(private val paths: LibraryPaths) {
    fun document(book: LibraryBook): File = paths.documentFile(book.id, book.format)
    fun thumbnail(id: BookId): File = paths.thumbnailFile(id)

    fun deleteBook(id: BookId) {
        paths.bookDir(id).deleteRecursively()
    }
}
