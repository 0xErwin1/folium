package com.folium.reader.core.ink

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PageInkStoreTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private fun stroke(id: String, sequence: Long) = InkStroke(
        StrokeId(id), InkTool.PEN, InkTip.BALLPOINT, colorArgb = 0xFF000000.toInt(),
        widthSheetUnits = 0.002f, inputKind = InkInputKind.STYLUS,
        samples = (0 until 4).map { i -> InkSample(0.1f * i, 0.2f * i, elapsedMillis = i * 5) },
        sequence = sequence
    )

    private fun textBox(id: String, sequence: Long) = SheetTextBox(
        StrokeId(id), topLeft = SheetPoint(0.1f, 0.2f), widthSheetUnits = 0.5f, heightSheetUnits = 0.1f,
        text = "margin note", font = SheetTextFont.SERIF, sizePt = 12f, style = SheetTextStyle.NORMAL,
        colorArgb = 0xFF112233.toInt(), sequence = sequence
    )

    @Test fun strokesAndTextBoxesRoundTripThroughAPage() {
        val store = PageInkStore(tempFolder.newFolder())
        val a = stroke("a", sequence = 0)
        val box = textBox("t", sequence = 1)

        store.open(3).use { page ->
            assertEquals(0L, page.nextSequence())
            page.apply(SheetEdit.AddStrokes(listOf(a)))
            page.apply(SheetEdit.ReplaceItems(removed = emptyList(), added = listOf(SheetItem.Text(box))))
        }

        store.open(3).use { page ->
            assertEquals(listOf(a.id), page.strokes().map { it.id })
            assertEquals(listOf(box), page.textBoxes())
            assertEquals(listOf(a.id, box.id), page.items().map { it.id })
            assertEquals(2L, page.nextSequence())
        }

        val read = store.read(3)
        assertEquals(listOf(a.id, box.id), read.items.map { it.id })
    }

    @Test fun aPageIsWrittenToItsOwnLogFile() {
        val root = tempFolder.newFolder()
        val store = PageInkStore(root)

        store.open(7).use { it.apply(SheetEdit.AddStrokes(listOf(stroke("a", 0)))) }

        assertTrue(File(root, "p7.log").isFile)
    }

    @Test fun aSecondWriterOfTheSamePageIsRefusedUntilTheFirstCloses() {
        val store = PageInkStore(tempFolder.newFolder())
        val first = store.open(2)

        try {
            store.open(2)
            fail("expected PageInkAlreadyOpenException")
        } catch (e: PageInkAlreadyOpenException) {
            assertEquals(2, e.pageIndex)
        }

        first.close()
        store.open(2).close()
    }

    @Test fun differentPagesMayBeOpenAtTheSameTime() {
        val store = PageInkStore(tempFolder.newFolder())

        store.open(0).use { left ->
            store.open(1).use { right ->
                left.apply(SheetEdit.AddStrokes(listOf(stroke("l", 0))))
                right.apply(SheetEdit.AddStrokes(listOf(stroke("r", 0))))
            }
        }

        assertEquals(listOf(StrokeId("l")), store.read(0).items.map { it.id })
        assertEquals(listOf(StrokeId("r")), store.read(1).items.map { it.id })
    }

    @Test fun readOfAPageWithNoFileIsEmpty() {
        val root = tempFolder.newFolder()

        val read = PageInkStore(root).read(5)

        assertTrue(read.items.isEmpty())
        assertFalse(File(root, "p5.log").exists())
    }

    @Test fun readOfATornPageReturnsTheValidPrefixAndLeavesTheFileBytesIdentical() {
        val root = tempFolder.newFolder()
        val store = PageInkStore(root)
        store.open(0).use { it.apply(SheetEdit.AddStrokes(listOf(stroke("a", 0), stroke("b", 1)))) }

        val file = File(root, "p0.log")
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 3L) }
        val before = file.readBytes()

        val read = store.read(0)

        assertEquals(listOf(StrokeId("a")), read.items.map { it.id })
        assertTrue(read.replayReport.tornTailBytes > 0)
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test fun closingAPageWithNoItemsRemovesItsFile() {
        val root = tempFolder.newFolder()
        val store = PageInkStore(root)
        val a = stroke("a", 0)

        store.open(4).close()
        assertFalse(File(root, "p4.log").exists())

        store.open(4).use { page ->
            page.apply(SheetEdit.AddStrokes(listOf(a)))
            page.apply(SheetEdit.RemoveStrokes(listOf(a)))
        }
        assertFalse(File(root, "p4.log").exists())
        assertEquals(emptySet<Int>(), store.pagesWithInk())
    }

    @Test fun closingAPageWithItemsKeepsItsFile() {
        val root = tempFolder.newFolder()
        val store = PageInkStore(root)

        store.open(4).use { it.apply(SheetEdit.AddStrokes(listOf(stroke("a", 0)))) }

        assertTrue(File(root, "p4.log").isFile)
    }

    @Test fun pagesWithInkListsEveryPageLogAndIgnoresOtherNames() {
        val root = tempFolder.newFolder()
        val store = PageInkStore(root)
        store.open(0).use { it.apply(SheetEdit.AddStrokes(listOf(stroke("a", 0)))) }
        store.open(12).use { it.apply(SheetEdit.AddStrokes(listOf(stroke("b", 0)))) }
        store.bind("book-v1")

        for (junk in listOf("p.log", "p-1.log", "px.log", "p3.log.tmp", "q4.log", "p5.txt", "p007.log", "p99999999999.log")) {
            File(root, junk).writeBytes(byteArrayOf(1))
        }
        File(root, "p8.log").mkdirs()

        assertEquals(setOf(0, 12), store.pagesWithInk())
    }

    @Test fun pagesWithInkOfAMissingRootIsEmpty() {
        assertEquals(emptySet<Int>(), PageInkStore(File(tempFolder.root, "never-created")).pagesWithInk())
    }

    @Test fun deleteAllRemovesEveryPageAndTheBinding() {
        val root = tempFolder.newFolder()
        val store = PageInkStore(root)
        store.open(0).use { it.apply(SheetEdit.AddStrokes(listOf(stroke("a", 0)))) }
        store.open(1).use { it.apply(SheetEdit.AddStrokes(listOf(stroke("b", 0)))) }
        store.bind("book-v1")

        store.deleteAll()

        assertEquals(emptySet<Int>(), store.pagesWithInk())
        assertNull(store.boundIdentity())
        assertFalse(root.exists())
    }

    @Test(expected = IllegalStateException::class)
    fun deleteAllIsRefusedWhileAPageIsOpen() {
        val store = PageInkStore(tempFolder.newFolder())
        store.open(0)

        store.deleteAll()
    }

    @Test fun bindPersistsTheIdentityAndReplacesAnEarlierOne() {
        val root = tempFolder.newFolder()
        assertNull(PageInkStore(root).boundIdentity())

        PageInkStore(root).bind("sha256:abc")
        assertEquals("sha256:abc", PageInkStore(root).boundIdentity())

        PageInkStore(root).bind("sha256:def")
        assertEquals("sha256:def", PageInkStore(root).boundIdentity())
        assertFalse(File(root, "page-ink.meta.tmp").exists())
    }

    @Test fun bindCreatesAMissingRoot() {
        val root = File(tempFolder.root, "book/page-ink")

        PageInkStore(root).bind("id")

        assertEquals("id", PageInkStore(root).boundIdentity())
    }

    @Test(expected = PageInkMetaCorruptException::class)
    fun anUnreadableMetaFileIsReportedRatherThanTreatedAsUnbound() {
        val root = tempFolder.newFolder()
        File(root, "page-ink.meta").writeBytes(byteArrayOf(9, 9, 9))

        PageInkStore(root).boundIdentity()
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNegativePageIndexIsRejected() {
        PageInkStore(tempFolder.newFolder()).open(-1)
    }
}
