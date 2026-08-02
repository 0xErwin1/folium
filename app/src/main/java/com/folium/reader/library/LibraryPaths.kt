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
    fun documentFile(id: BookId): File = File(bookDir(id), "document.pdf")
    fun thumbnailFile(id: BookId): File = File(bookDir(id), "thumb.png")

    fun stagingRoot(): File = stagingDir
    fun stagingDir(token: String): File = File(stagingDir, token)
}
