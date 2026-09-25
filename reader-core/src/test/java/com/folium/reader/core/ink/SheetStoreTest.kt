package com.folium.reader.core.ink

import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.ReadingPosition
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SheetStoreTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private fun sheet(id: String = "11111111-1111-1111-1111-111111111111", title: String = "Notes") = Sheet(
        SheetId(id), title = title, createdAtEpochMillis = 1_000L, updatedAtEpochMillis = 1_000L,
        template = SheetTemplate.BLANK, anchor = null
    )

    private fun stroke(id: String, sequence: Long, sampleCount: Int = 4) = InkStroke(
        StrokeId(id), InkTool.PEN, InkTip.BALLPOINT, colorArgb = 0xFF000000.toInt(),
        widthSheetUnits = 0.01f, inputKind = InkInputKind.STYLUS,
        samples = (0 until sampleCount).map { i -> InkSample(0.1f * i, 0.2f * i, elapsedMillis = i * 5) },
        sequence = sequence
    )

    @Test fun createThenOpenRoundTripsTheSheetAndItsStrokes() {
        val store = SheetStore(tempFolder.newFolder())
        val a = stroke("a", sequence = 0)

        store.create(sheet()).use { opened ->
            assertEquals(0L, opened.nextSequence())
            opened.apply(SheetEdit.AddStrokes(listOf(a)))
        }

        store.open(SheetId("11111111-1111-1111-1111-111111111111")).use { opened ->
            val strokes = opened.strokes()
            assertEquals(1, strokes.size)
            assertEquals(a.id, strokes[0].id)
            assertEquals(a.sequence, strokes[0].sequence)
            assertEquals("Notes", opened.sheet.title)
        }
    }

    @Test(expected = SheetAlreadyExistsException::class)
    fun creatingTheSameSheetTwiceIsRejected() {
        val store = SheetStore(tempFolder.newFolder())
        store.create(sheet()).close()
        store.create(sheet())
    }

    @Test(expected = SheetNotFoundException::class)
    fun openingAMissingSheetIsRejected() {
        SheetStore(tempFolder.newFolder()).open(SheetId("99999999-9999-9999-9999-999999999999"))
    }

    @Test fun openingTheSameSheetTwiceFailsFastAndSucceedsAfterClose() {
        val store = SheetStore(tempFolder.newFolder())
        val opened = store.create(sheet())

        try {
            store.open(sheet().id)
            org.junit.Assert.fail("expected SheetAlreadyOpenException")
        } catch (_: SheetAlreadyOpenException) {
            // expected
        }

        opened.close()
        store.open(sheet().id).close()
    }

    @Test fun renamePersistsImmediately() {
        val root = tempFolder.newFolder()
        val store = SheetStore(root)
        store.create(sheet()).use { it.rename("Renamed") }

        assertEquals("Renamed", store.open(sheet().id).use { it.sheet.title })
    }

    @Test fun deleteRemovesTheWholeSheetDirectory() {
        val root = tempFolder.newFolder()
        val store = SheetStore(root)
        store.create(sheet()).close()

        store.delete(sheet().id)

        assertFalse(store.exists(sheet().id))
        assertFalse(File(root, sheet().id.value).exists())
    }

    @Test(expected = IllegalStateException::class)
    fun deletingAnOpenSheetIsRejected() {
        val store = SheetStore(tempFolder.newFolder())
        store.create(sheet())
        store.delete(sheet().id)
    }

    @Test fun existsReflectsWhetherASheetWasEverCreated() {
        val store = SheetStore(tempFolder.newFolder())
        assertFalse(store.exists(sheet().id))
        store.create(sheet()).close()
        assertTrue(store.exists(sheet().id))
    }

    @Test fun listReportsOneGoodAndOneUnreadableSheet() {
        val root = tempFolder.newFolder()
        val store = SheetStore(root)
        store.create(sheet(id = "11111111-1111-1111-1111-111111111111", title = "Good")).close()
        store.create(sheet(id = "22222222-2222-2222-2222-222222222222", title = "Bad")).close()

        val badMeta = File(File(root, "22222222-2222-2222-2222-222222222222"), "sheet.meta")
        RandomAccessFile(badMeta, "rw").use { it.seek(0); it.writeInt(0xDEADBEEF.toInt()) }

        val listing = store.list()

        assertEquals(listOf("Good"), listing.sheets.map { it.title })
        assertEquals(listOf(SheetId("22222222-2222-2222-2222-222222222222")), listing.unreadable)
    }

    @Test fun listAnchoredToABookReturnsOnlyThatBooksSheetsWithTheirAnchors() {
        val root = tempFolder.newFolder()
        val store = SheetStore(root)
        val book = BookId("book-1")
        val pageAnchor = SheetAnchor.Page(book, pageIndex = 18, rank = 1L shl 20)
        val textAnchor = SheetAnchor.Text(book, ReadingPosition(chapterIndex = 2, characterOffset = 40), rank = 0L)

        store.create(sheet(id = "11111111-1111-1111-1111-111111111111", title = "Page").copy(anchor = pageAnchor)).close()
        store.create(sheet(id = "22222222-2222-2222-2222-222222222222", title = "Text").copy(anchor = textAnchor)).close()
        store.create(sheet(id = "33333333-3333-3333-3333-333333333333", title = "Other book").copy(anchor = SheetAnchor.Page(BookId("book-2"), 0, 0L))).close()
        store.create(sheet(id = "44444444-4444-4444-4444-444444444444", title = "Standalone")).close()

        val listing = store.list(anchoredTo = book)

        assertEquals(
            mapOf("Page" to pageAnchor, "Text" to textAnchor),
            listing.sheets.associate { it.title to it.anchor }
        )
    }

    @Test fun listAnchoredToABookStillReportsEveryUnreadableSheet() {
        val root = tempFolder.newFolder()
        val store = SheetStore(root)
        store.create(sheet(id = "22222222-2222-2222-2222-222222222222", title = "Bad")).close()

        val badMeta = File(File(root, "22222222-2222-2222-2222-222222222222"), "sheet.meta")
        RandomAccessFile(badMeta, "rw").use { it.seek(0); it.writeInt(0xDEADBEEF.toInt()) }

        val listing = store.list(anchoredTo = BookId("book-1"))

        assertEquals(emptyList<SheetSummary>(), listing.sheets)
        assertEquals(listOf(SheetId("22222222-2222-2222-2222-222222222222")), listing.unreadable)
    }

    @Test fun leftoverMetaTempFileIsIgnoredByList() {
        val root = tempFolder.newFolder()
        val store = SheetStore(root)
        val opened = store.create(sheet())
        opened.close()

        File(File(root, sheet().id.value), "sheet.meta.tmp").writeBytes(byteArrayOf(1, 2, 3))

        val listing = store.list()
        assertEquals(1, listing.sheets.size)
        assertEquals(0, listing.unreadable.size)
    }

    @Test fun aFiveThousandStrokeSheetReopensQuickly() {
        // ON_CLOSE_AND_FLUSH avoids 5,000 individual fsyncs while writing the fixture; only the
        // reopen below is timed, so the write durability mode used to build it does not matter.
        val store = SheetStore(tempFolder.newFolder(), durability = SheetStrokeLogDurability.ON_CLOSE_AND_FLUSH)
        val id = sheet().id

        store.create(sheet()).use { opened ->
            repeat(5_000) { index ->
                opened.apply(SheetEdit.AddStrokes(listOf(stroke("stroke-$index", sequence = index.toLong()))))
            }
        }

        val start = System.nanoTime()
        val strokeCount = store.open(id).use { it.strokes().size }
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000

        println("Reopening a 5,000-stroke sheet took ${elapsedMillis}ms")
        assertEquals(5_000, strokeCount)
        assertTrue("reopen took ${elapsedMillis}ms, expected under 5000ms", elapsedMillis < 5_000)
    }
}
