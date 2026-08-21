package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.ReflowFontFamily
import com.folium.reader.core.pdf.ReflowTextAlign
import com.folium.reader.core.pdf.TYPOGRAPHY_VERSION_MARKER
import com.folium.reader.core.pdf.TypographyPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TypographyPresetStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val paths get() = LibraryPaths(tempFolder.root)
    private fun store(): TypographyPresetStore = TypographyPresetStore(paths)

    private val custom = TypographyPreset(
        fontFamily = ReflowFontFamily.SERIF,
        fontSizePoints = 22f,
        lineHeight = 1.5f,
        marginEm = 1f,
        textAlign = ReflowTextAlign.JUSTIFY,
        paragraphIndentEm = 1.2f,
        pageColors = true
    )

    @Test
    fun `a library that was never given a global preset follows the default`() {
        assertEquals(TypographyPreset.DEFAULT, store().readGlobal())
    }

    @Test
    fun `writing then reading the global preset round trips`() {
        val store = store()

        assertTrue(store.writeGlobal(custom))

        assertEquals(custom, store.readGlobal())
    }

    @Test
    fun `a corrupt global file falls back to the default`() {
        writeRawGlobal(TYPOGRAPHY_VERSION_MARKER, "garbage")

        assertEquals(TypographyPreset.DEFAULT, store().readGlobal())
    }

    @Test
    fun `a book with no override reads as no override, not as the default`() {
        assertNull(store().readOverride(BookId("a")))
    }

    @Test
    fun `writing then reading a per-book override round trips`() {
        val store = store()
        val id = BookId("a")

        assertTrue(store.writeOverride(id, custom))

        assertEquals(custom, store.readOverride(id))
    }

    @Test
    fun `a malformed per-book override reads as no override, not as the default`() {
        val id = BookId("a")
        writeRawOverride(id, TYPOGRAPHY_VERSION_MARKER, "garbage")

        assertNull(store().readOverride(id))
    }

    @Test
    fun `a global preset and no override resolve to the global preset`() {
        val store = store()
        val id = BookId("a")
        store.writeGlobal(custom)

        val resolved = store.readOverride(id) ?: store.readGlobal()

        assertEquals(custom, resolved)
    }

    @Test
    fun `an override wins over the global preset`() {
        val store = store()
        val id = BookId("a")
        store.writeGlobal(TypographyPreset.DEFAULT)
        store.writeOverride(id, custom)

        val resolved = store.readOverride(id) ?: store.readGlobal()

        assertEquals(custom, resolved)
    }

    @Test
    fun `clearing an override deletes its file rather than writing an empty one`() {
        val store = store()
        val id = BookId("a")
        store.writeOverride(id, custom)

        assertTrue(store.clearOverride(id))

        assertNull(store.readOverride(id))
        assertFalseFileExists(paths.typographyFile(id))
    }

    private fun assertFalseFileExists(file: File) {
        org.junit.Assert.assertFalse(file.exists())
    }

    private fun writeRawGlobal(vararg lines: String) {
        val file = paths.typographyFile
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun writeRawOverride(id: BookId, vararg lines: String) {
        val file = paths.typographyFile(id)
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }
}
