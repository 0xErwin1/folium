package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.CATALOG_VERSION_MARKER
import com.folium.reader.core.library.LibraryBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BookCatalogStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(): BookCatalogStore = BookCatalogStore(LibraryPaths(tempFolder.root))

    private fun book(id: String, title: String = "Title $id") =
        LibraryBook(BookId(id), title, pageCount = 10, addedAtMillis = 1L)

    @Test
    fun `append then read round trips books`() {
        val store = store()

        store.append(book("a"))
        store.append(book("b"))

        assertEquals(listOf(book("a"), book("b")), store.read())
    }

    @Test
    fun `remove drops only the matching book`() {
        val store = store()
        store.append(book("a"))
        store.append(book("b"))

        store.remove(BookId("a"))

        assertEquals(listOf(book("b")), store.read())
    }

    @Test
    fun `malformed lines are dropped without failing the read`() {
        val libraryDir = File(tempFolder.root, "library")
        libraryDir.mkdirs()
        val catalogFile = File(libraryDir, "catalog")
        catalogFile.writeText(
            listOf(
                CATALOG_VERSION_MARKER,
                "aTitle a101",
                "not-enough-fields",
                "bTitle bnot-a-number1"
            ).joinToString("\n")
        )

        val store = store()

        assertEquals(listOf(book("a", "Title a")), store.read())
    }

    @Test
    fun `a wrong version marker is treated as an empty catalog`() {
        val libraryDir = File(tempFolder.root, "library")
        libraryDir.mkdirs()
        val catalogFile = File(libraryDir, "catalog")
        catalogFile.writeText(
            listOf("folium-catalog 0", "aTitle a101").joinToString("\n")
        )

        val store = store()

        assertTrue(store.read().isEmpty())
    }
}
