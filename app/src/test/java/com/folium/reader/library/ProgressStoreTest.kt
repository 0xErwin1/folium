package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.PROGRESS_VERSION_MARKER
import com.folium.reader.core.library.PROGRESS_VERSION_MARKER_V1
import com.folium.reader.core.library.ProgressRecord
import com.folium.reader.core.pdf.ReadingPosition
import com.folium.reader.core.pdf.ReadingPositionTokens
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

    /**
     * A real file written before pagination and a position token were tracked at all -- the shelf
     * nine already-imported books actually have on disk -- has to keep reading back exactly as it
     * always did.
     */
    @Test
    fun `a v1 progress file survives a read`() {
        val libraryDir = File(tempFolder.root, "library")
        libraryDir.mkdirs()
        val progressFile = File(libraryDir, "progress")
        progressFile.writeText(
            listOf(PROGRESS_VERSION_MARKER_V1, "a4", "b91").joinToString("\n")
        )

        val store = store()

        assertEquals(
            listOf(ProgressRecord(BookId("a"), 4), ProgressRecord(BookId("b"), 91)),
            store.read()
        )
    }

    @Test
    fun `writing over a v1 file upgrades it to v2`() {
        val libraryDir = File(tempFolder.root, "library")
        libraryDir.mkdirs()
        val progressFile = File(libraryDir, "progress")
        progressFile.writeText(listOf(PROGRESS_VERSION_MARKER_V1, "a4").joinToString("\n"))

        val store = store()
        store.put(BookId("b"), pageIndex = 1)

        assertEquals(PROGRESS_VERSION_MARKER, progressFile.readLines().first())
        assertEquals(
            listOf(ProgressRecord(BookId("a"), 4), ProgressRecord(BookId("b"), 1)),
            store.read()
        )
    }

    @Test
    fun `put stores the pagination and position token alongside the page`() {
        val store = store()
        val token = ReadingPositionTokens.mintPosition(ReadingPosition(bookmark = 1L, chapterIndex = 0, characterOffset = 12))

        store.put(BookId("a"), pageIndex = 4, pageCount = 300, token = token)

        assertEquals(listOf(ProgressRecord(BookId("a"), 4, 300, token)), store.read())
    }
}
