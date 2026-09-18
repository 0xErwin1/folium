package com.folium.reader.library

import com.folium.reader.core.library.TWO_PAGE_SPREAD_VERSION_MARKER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TwoPageSpreadPreferenceStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val paths get() = LibraryPaths(tempFolder.root)

    private fun store(): TwoPageSpreadPreferenceStore = TwoPageSpreadPreferenceStore(paths)

    @Test
    fun `every stored value survives a new store over the same files`() {
        listOf(true, false).forEach { enabled ->
            assertTrue(store().write(enabled))
            assertEquals(enabled, store().read())
        }
    }

    @Test
    fun `a library that was never given the preference offers the spread`() {
        assertEquals(true, store().read())
    }

    @Test
    fun `a corrupt value reads as enabled`() {
        writeTwoPageSpreadFile(TWO_PAGE_SPREAD_VERSION_MARKER, "sometimes")

        assertEquals(true, store().read())
    }

    @Test
    fun `an unknown version reads as enabled`() {
        writeTwoPageSpreadFile("folium-two-page-spread 2", "off")

        assertEquals(true, store().read())
    }

    private fun writeTwoPageSpreadFile(vararg lines: String) {
        val file: File = paths.twoPageSpreadFile
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }
}
