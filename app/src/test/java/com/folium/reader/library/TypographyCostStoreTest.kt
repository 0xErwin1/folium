package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.TYPOGRAPHY_COST_VERSION_MARKER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TypographyCostStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val paths get() = LibraryPaths(tempFolder.root)
    private fun store(): TypographyCostStore = TypographyCostStore(paths)

    @Test
    fun `a book with no recorded cost reads as zero`() {
        assertEquals(0L, store().read(BookId("a")))
    }

    @Test
    fun `writing then reading the cost round trips`() {
        val store = store()
        val id = BookId("a")

        assertTrue(store.write(id, 842L))

        assertEquals(842L, store.read(id))
    }

    @Test
    fun `a malformed cost file reads as zero`() {
        val id = BookId("a")
        val file = paths.typographyCostFile(id)
        file.parentFile?.mkdirs()
        file.writeText(listOf(TYPOGRAPHY_COST_VERSION_MARKER, "not-a-number").joinToString("\n"))

        assertEquals(0L, store().read(id))
    }

    @Test
    fun `a wrong version marker reads as zero`() {
        val id = BookId("a")
        val file = paths.typographyCostFile(id)
        file.parentFile?.mkdirs()
        file.writeText(listOf("folium-typography-cost 0", "842").joinToString("\n"))

        assertEquals(0L, store().read(id))
    }
}
