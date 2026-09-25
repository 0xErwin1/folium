package com.folium.reader.core.ink

import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.ReadingPosition
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
        template = SheetTemplate.BLANK, anchor = SheetAnchor.Page(BookId("book-1"), pageIndex = 7, rank = 3L shl 20)
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

    @Test fun textAnchoredSheetRoundTripsExactly() {
        val file = File(tempFolder.newFolder(), "sheet.meta")
        val sheet = anchoredSheet().copy(
            anchor = SheetAnchor.Text(BookId("book-2"), ReadingPosition(chapterIndex = 3, characterOffset = 1_204), rank = -(1L shl 20))
        )

        SheetMetaFile.write(file, sheet)

        assertEquals(sheet, SheetMetaFile.read(file))
    }

    @Test fun aVersionOneStandaloneSheetStillReadsWithNoAnchor() {
        val file = File(tempFolder.newFolder(), "sheet.meta")
        file.writeBytes(hex(VERSION_ONE_STANDALONE))

        assertEquals(standaloneSheet(), SheetMetaFile.read(file))
    }

    @Test fun aVersionOneAnchoredSheetReadsAsAPageAnchorOfRankZero() {
        val file = File(tempFolder.newFolder(), "sheet.meta")
        file.writeBytes(hex(VERSION_ONE_ANCHORED))

        val expected = anchoredSheet().copy(anchor = SheetAnchor.Page(BookId("book-1"), pageIndex = 7, rank = 0L))
        assertEquals(expected, SheetMetaFile.read(file))
    }

    @Test(expected = SheetMetaCorruptException::class)
    fun anUnknownAnchorKindIsCorrupt() {
        val file = File(tempFolder.newFolder(), "sheet.meta")
        SheetMetaFile.write(file, standaloneSheet())

        val bytes = file.readBytes()
        bytes[bytes.size - 1] = 0x7F
        file.writeBytes(bytes)

        SheetMetaFile.read(file)
    }

    @Test(expected = SheetMetaCorruptException::class)
    fun aTextAnchorWithANegativeOffsetIsCorrupt() {
        val file = File(tempFolder.newFolder(), "sheet.meta")
        val sheet = anchoredSheet().copy(anchor = SheetAnchor.Text(BookId("b"), ReadingPosition(1, 2), rank = 0L))
        SheetMetaFile.write(file, sheet)

        val bytes = file.readBytes()
        val offsetStart = bytes.size - Long.SIZE_BYTES - Int.SIZE_BYTES
        bytes[offsetStart] = 0x80.toByte()
        file.writeBytes(bytes)

        SheetMetaFile.read(file)
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

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        /** [standaloneSheet] exactly as the version 1 encoder wrote it to disk. */
        const val VERSION_ONE_STANDALONE =
            "464f4c4d01002431313131313131312d313131312d313131312d313131312d313131313131313131313131" +
                "00054e6f74657300000000000003e800000000000007d00100"

        /** [anchoredSheet], anchored to page 7 of `book-1`, exactly as the version 1 encoder wrote it to disk. */
        const val VERSION_ONE_ANCHORED =
            "464f4c4d01002432323232323232322d323232322d323232322d323232322d323232323232323232323232" +
                "000b4d617267696e206e6f746500000000000001f400000000000001f400010006626f6f6b2d3100000007"
    }
}
