package com.folium.reader.library

import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.library.BookId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SheetCursorStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(): SheetCursorStore = SheetCursorStore(LibraryPaths(tempFolder.root))

    private fun cursorFile(): File = LibraryPaths(tempFolder.root).sheetCursorFile

    @Test
    fun `put then get round trips each book's sheet`() {
        val store = store()

        store.put(BookId("a"), SheetId("sheet-a"))
        store.put(BookId("b"), SheetId("sheet-b"))

        assertEquals(SheetId("sheet-a"), store().get(BookId("a")))
        assertEquals(SheetId("sheet-b"), store().get(BookId("b")))
        assertEquals(mapOf(BookId("a") to SheetId("sheet-a"), BookId("b") to SheetId("sheet-b")), store().read())
    }

    @Test
    fun `the last sheet put for a book replaces the one before it`() {
        val store = store()

        store.put(BookId("a"), SheetId("first"))
        store.put(BookId("a"), SheetId("second"))

        assertEquals(mapOf(BookId("a") to SheetId("second")), store.read())
    }

    @Test
    fun `remove clears only that book's sheet`() {
        val store = store()
        store.put(BookId("a"), SheetId("sheet-a"))
        store.put(BookId("b"), SheetId("sheet-b"))

        store.remove(BookId("a"))

        assertNull(store.get(BookId("a")))
        assertEquals(SheetId("sheet-b"), store.get(BookId("b")))
    }

    @Test
    fun `a missing file reads as no sheet for any book`() {
        assertEquals(emptyMap<BookId, SheetId>(), store().read())
        assertNull(store().get(BookId("a")))
    }

    @Test
    fun `a file with an unknown version marker reads as empty`() {
        cursorFile().parentFile!!.mkdirs()
        cursorFile().writeText("folium-sheet-cursor 99\na\u001Fsheet-a\n")

        assertEquals(emptyMap<BookId, SheetId>(), store().read())
    }

    @Test
    fun `malformed lines are dropped without failing the read`() {
        cursorFile().parentFile!!.mkdirs()
        cursorFile().writeText(
            listOf(
                "folium-sheet-cursor 1",
                "a\u001Fsheet-a",
                "no separator at all",
                "b\u001F",
                "\u001Fsheet-c",
                "d\u001Fsheet-d\u001Fextra",
                "e\u001Fsheet-e"
            ).joinToString("\n")
        )

        assertEquals(mapOf(BookId("a") to SheetId("sheet-a"), BookId("e") to SheetId("sheet-e")), store().read())
    }

    @Test
    fun `a directory where the file should be reads as empty`() {
        cursorFile().mkdirs()

        assertEquals(emptyMap<BookId, SheetId>(), store().read())
    }
}
