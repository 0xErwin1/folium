package com.folium.reader.library

import com.folium.reader.core.library.BookFormat
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
    val viewModeFile: File get() = File(libraryDir, "view-mode")
    val appearanceModeFile: File get() = File(libraryDir, "appearance-mode")
    val typographyFile: File get() = File(libraryDir, "typography")
    val twoPageSpreadFile: File get() = File(libraryDir, "two-page-spread")

    fun bookDir(id: BookId): File = File(libraryDir, id.value)
    fun documentFile(id: BookId, format: BookFormat): File = File(bookDir(id), documentFileName(format))
    fun thumbnailFile(id: BookId): File = File(bookDir(id), THUMBNAIL_FILE_NAME)

    /**
     * A per-book typography override, and how long its last re-pagination took. Both live inside
     * [bookDir], so [BookFiles.deleteBook]'s recursive delete already removes them along with the
     * rest of the book — nothing here has to remove them separately.
     */
    fun typographyFile(id: BookId): File = File(bookDir(id), "typography")
    fun typographyCostFile(id: BookId): File = File(bookDir(id), "typography-cost")

    fun stagingRoot(): File = stagingDir
    fun stagingDir(token: String): File = File(stagingDir, token)
    fun stagingDocumentFile(token: String, format: BookFormat): File = File(stagingDir(token), documentFileName(format))
    fun stagingThumbnailFile(token: String): File = File(stagingDir(token), THUMBNAIL_FILE_NAME)

    companion object {
        const val THUMBNAIL_FILE_NAME = "thumb.png"
        const val DOCUMENT_BASE_NAME = "document"

        /**
         * Shared between a book's staging directory and its final directory under `library/`, since
         * a successful import renames one into the other whole — the file names on both sides of
         * that rename must be identical for the destination store to find what a completed import
         * wrote.
         *
         * `documentFileName(BookFormat.PDF) == "document.pdf"` is the identity that makes this
         * change need no data migration, no catalog rewrite and no on-disk move for a book already
         * stored before formats other than PDF existed: its file is already named exactly what this
         * now computes for it.
         */
        fun documentFileName(format: BookFormat): String = "$DOCUMENT_BASE_NAME.${format.extension}"
    }
}
