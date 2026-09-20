package com.folium.reader.library

import com.folium.reader.core.ink.InkTip
import com.folium.reader.ink.PenColorChoice
import com.folium.reader.ink.PenSettings
import com.folium.reader.ink.PenSettingsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PenPreferenceStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val paths get() = LibraryPaths(tempFolder.root)

    private fun store(): PenPreferenceStore = PenPreferenceStore(paths)

    @Test
    fun `a stored setting survives a new store over the same file`() {
        val settings = PenSettings(InkTip.PENCIL, 22, PenColorChoice.GREEN, 12)

        assertTrue(store().write(settings))
        assertEquals(settings, store().read())
    }

    @Test
    fun `a library that was never given the preference offers the default settings`() {
        assertEquals(PenSettings.DEFAULT, store().read())
    }

    @Test
    fun `a corrupt file reads as the default settings`() {
        writePenFile(PenSettingsCodec.VERSION_MARKER, "NOT_A_TIP", "sometimes", "NOT_A_COLOUR")

        assertEquals(PenSettings.DEFAULT, store().read())
    }

    @Test
    fun `an unknown version reads as the default settings`() {
        writePenFile("folium-pen 0", "FOUNTAIN", "12", "BLUE")

        assertEquals(PenSettings.DEFAULT, store().read())
    }

    private fun writePenFile(vararg lines: String) {
        val file: File = paths.penFile
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }
}
