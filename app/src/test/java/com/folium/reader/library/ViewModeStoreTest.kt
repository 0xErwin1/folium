package com.folium.reader.library

import com.folium.reader.core.library.LibraryViewMode
import com.folium.reader.core.library.VIEW_MODE_VERSION_MARKER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ViewModeStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val paths get() = LibraryPaths(tempFolder.root)

    private fun store(): ViewModeStore = ViewModeStore(paths)

    @Test
    fun `a stored mode survives a new store over the same files`() {
        assertTrue(store().write(LibraryViewMode.GRID))

        assertEquals(LibraryViewMode.GRID, store().read())
    }

    @Test
    fun `a library that was never given a preference reads as the list`() {
        assertEquals(LibraryViewMode.LIST, store().read())
    }

    @Test
    fun `a file written by a version this one does not know reads as the list`() {
        writeViewModeFile("folium-view 2", "grid")

        assertEquals(LibraryViewMode.LIST, store().read())
    }

    @Test
    fun `a corrupt value under a valid marker reads as the list`() {
        writeViewModeFile(VIEW_MODE_VERSION_MARKER, " mosaic")

        assertEquals(LibraryViewMode.LIST, store().read())
    }

    @Test
    fun `a marker with no value at all reads as the list`() {
        writeViewModeFile(VIEW_MODE_VERSION_MARKER)

        assertEquals(LibraryViewMode.LIST, store().read())
    }

    @Test
    fun `choosing the list again overwrites a stored grid`() {
        val store = store()
        store.write(LibraryViewMode.GRID)

        assertTrue(store.write(LibraryViewMode.LIST))
        assertEquals(LibraryViewMode.LIST, store.read())
    }

    private fun writeViewModeFile(vararg lines: String) {
        val file: File = paths.viewModeFile
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }
}
