package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.PROGRESS_VERSION_MARKER
import com.folium.reader.core.library.ProgressRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProgressStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(): ProgressStore = ProgressStore(LibraryPaths(tempFolder.root))

    @Test
    fun `put then read round trips a progress record`() {
        val store = store()

        store.put(BookId("a"), pageIndex = 4)

        assertEquals(listOf(ProgressRecord(BookId("a"), 4)), store.read())
    }

    @Test
    fun `remove drops only the matching record`() {
        val store = store()
        store.put(BookId("a"), pageIndex = 4)
        store.put(BookId("b"), pageIndex = 7)

        store.remove(BookId("a"))

        assertEquals(listOf(ProgressRecord(BookId("b"), 7)), store.read())
    }

    @Test
    fun `last write wins per book`() {
        val store = store()

        store.put(BookId("a"), pageIndex = 1)
        store.put(BookId("a"), pageIndex = 2)
        store.put(BookId("a"), pageIndex = 3)

        assertEquals(listOf(ProgressRecord(BookId("a"), 3)), store.read())
    }

    @Test
    fun `malformed lines are dropped without failing the read`() {
        val libraryDir = File(tempFolder.root, "library")
        libraryDir.mkdirs()
        val progressFile = File(libraryDir, "progress")
        progressFile.writeText(
            listOf(
                PROGRESS_VERSION_MARKER,
                "a4",
                "not-enough-fields",
                "bnot-a-number"
            ).joinToString("\n")
        )

        val store = store()

        assertEquals(listOf(ProgressRecord(BookId("a"), 4)), store.read())
    }

    @Test
    fun `a wrong version marker is treated as empty progress`() {
        val libraryDir = File(tempFolder.root, "library")
        libraryDir.mkdirs()
        val progressFile = File(libraryDir, "progress")
        progressFile.writeText(listOf("folium-progress 0", "a4").joinToString("\n"))

        val store = store()

        assertTrue(store.read().isEmpty())
    }
}
