package com.folium.reader.library

import com.folium.reader.core.library.APPEARANCE_MODE_VERSION_MARKER
import com.folium.reader.core.library.AppearanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppearanceModeStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val paths get() = LibraryPaths(tempFolder.root)

    private fun store(): AppearanceModeStore = AppearanceModeStore(paths)

    @Test
    fun `every stored appearance survives a new store over the same files`() {
        AppearanceMode.values().forEach { mode ->
            assertTrue(store().write(mode))
            assertEquals(mode, store().read())
        }
    }

    @Test
    fun `the build 74 e-ink value is retained as e-ink light`() {
        writeAppearanceFile(APPEARANCE_MODE_VERSION_MARKER, "e-ink")

        assertEquals(AppearanceMode.E_INK_LIGHT, store().read())
    }

    @Test
    fun `a library that was never given an appearance follows the system`() {
        assertEquals(AppearanceMode.SYSTEM, store().read())
    }

    @Test
    fun `a corrupt appearance reads as system`() {
        writeAppearanceFile(APPEARANCE_MODE_VERSION_MARKER, "sepia")

        assertEquals(AppearanceMode.SYSTEM, store().read())
    }

    @Test
    fun `an unknown version reads as system`() {
        writeAppearanceFile("folium-appearance 2", "dark")

        assertEquals(AppearanceMode.SYSTEM, store().read())
    }

    private fun writeAppearanceFile(vararg lines: String) {
        val file: File = paths.appearanceModeFile
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }
}
