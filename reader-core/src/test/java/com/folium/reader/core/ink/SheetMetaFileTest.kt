package com.folium.reader.core.ink

import com.folium.reader.core.library.BookId
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SheetMetaFileTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private fun standaloneSheet(title: String = "Notes") = Sheet(
        SheetId("11111111-1111-1111-1111-111111111111"),
        title = title, createdAtEpochMillis = 1_000L, updatedAtEpochMillis = 2_000L,
        template = SheetTemplate.RULED, anchor = null
    )

    private fun anchoredSheet() = Sheet(
        SheetId("22222222-2222-2222-2222-222222222222"),
        title = "Margin note", createdAtEpochMillis = 500L, updatedAtEpochMillis = 500L,
        template = SheetTemplate.BLANK, anchor = SheetAnchor(BookId("book-1"), pageIndex = 7)
    )

    @Test fun standaloneSheetRoundTripsExactly() {
        val file = File(tempFolder.newFolder(), "sheet.meta")
        val sheet = standaloneSheet()

        SheetMetaFile.write(file, sheet)

        assertEquals(sheet, SheetMetaFile.read(file))
    }

    @Test fun anchoredSheetRoundTripsExactly() {
        val file = File(tempFolder.newFolder(), "sheet.meta")
        val sheet = anchoredSheet()

        SheetMetaFile.write(file, sheet)

        assertEquals(sheet, SheetMetaFile.read(file))
    }

    @Test fun rewritingOverwritesThePreviousContentAtomically() {
        val file = File(tempFolder.newFolder(), "sheet.meta")
        SheetMetaFile.write(file, standaloneSheet(title = "First"))
        SheetMetaFile.write(file, standaloneSheet(title = "Second"))

        assertEquals("Second", SheetMetaFile.read(file).title)
    }

    @Test fun writeLeavesNoTempFileBehind() {
        val dir = tempFolder.newFolder()
        val file = File(dir, "sheet.meta")
        SheetMetaFile.write(file, standaloneSheet())

        assertNull(dir.listFiles { candidate -> candidate.name.endsWith(".tmp") }?.firstOrNull())
    }

    @Test(expected = SheetMetaCorruptException::class)
    fun corruptMagicFailsRatherThanBeingSilentlyReplaced() {
        val file = File(tempFolder.newFolder(), "sheet.meta")
        SheetMetaFile.write(file, standaloneSheet())

        RandomAccessFile(file, "rw").use { it.seek(0); it.writeInt(0xDEADBEEF.toInt()) }
        val bytesBefore = file.readBytes()

        try {
            SheetMetaFile.read(file)
        } finally {
            assertTrue(bytesBefore.contentEquals(file.readBytes()))
        }
    }
}
