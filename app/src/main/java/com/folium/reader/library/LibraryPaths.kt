package com.folium.reader.library

import com.folium.reader.core.library.BookId
import java.io.File

/**
 * Resolves every path the app-managed library touches under [filesDir]. The catalog, never a
 * directory listing, defines the library — which is what makes the staging directory and any
 * orphaned book directory invisible rather than corrupting.
 */
class LibraryPaths(filesDir: File) {
    private val libraryDir = File(filesDir, "library")
    private val stagingDir = File(libraryDir, ".staging")

    val catalogFile: File get() = File(libraryDir, "catalog")
    val progressFile: File get() = File(libraryDir, "progress")

    fun bookDir(id: BookId): File = File(libraryDir, id.value)
    fun documentFile(id: BookId): File = File(bookDir(id), DOCUMENT_FILE_NAME)
    fun thumbnailFile(id: BookId): File = File(bookDir(id), THUMBNAIL_FILE_NAME)

    fun stagingRoot(): File = stagingDir
    fun stagingDir(token: String): File = File(stagingDir, token)
    fun stagingDocumentFile(token: String): File = File(stagingDir(token), DOCUMENT_FILE_NAME)
    fun stagingThumbnailFile(token: String): File = File(stagingDir(token), THUMBNAIL_FILE_NAME)

    companion object {
        /**
         * Shared between a book's staging directory and its final directory under `library/`, since
         * a successful import renames one into the other whole — the file names on both sides of
         * that rename must be identical for the destination store to find what a completed import
         * wrote.
         */
        const val DOCUMENT_FILE_NAME = "document.pdf"
        const val THUMBNAIL_FILE_NAME = "thumb.png"
    }
}
