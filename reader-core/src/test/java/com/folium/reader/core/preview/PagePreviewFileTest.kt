package com.folium.reader.core.preview

import java.io.File
import java.io.RandomAccessFile
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PagePreviewFileTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private fun preview(width: Int = 4, height: Int = 4, seed: Long = 1L): PagePreview {
        val pixels = ByteArray(width * height * 2)
        Random(seed).nextBytes(pixels)
        return PagePreview(width, height, PagePreviewPixelFormat.RGB_565, pixels)
    }

    private fun open(file: File, pageCount: Int = 10, engineId: String = "engine-1", contentId: String = "content-1", layoutVersion: String? = null) =
        PagePreviewFile.open(file, engineId, contentId, layoutVersion, pageCount)

    @Test fun anEmptyFileHasNoPreviews() {
        val file = File(tempFolder.newFolder(), "previews.pgv")
        val previews = open(file)

        for (page in 0 until 10) assertNull(previews.previewFor(page))
    }

    @Test fun addThenReadRoundTripsExactly() {
        val file = File(tempFolder.newFolder(), "previews.pgv")
        val previews = open(file)
        val p = preview()

        assertTrue(previews.addPreview(3, p))
        assertEquals(p, previews.previewFor(3))
        assertNull(previews.previewFor(4))
    }

    @Test fun appendThenReloadSeesTheSamePreview() {
        val dir = tempFolder.newFolder()
        val file = File(dir, "previews.pgv")
        val p = preview(seed = 7L)
        open(file).addPreview(2, p)

        val reopened = open(file)
        assertEquals(p, reopened.previewFor(2))
    }

    @Test fun reopenAndAppendMoreKeepsBothPreviews() {
        val dir = tempFolder.newFolder()
        val file = File(dir, "previews.pgv")
        val first = preview(seed = 1L)
        val second = preview(seed = 2L)

        open(file).addPreview(0, first)
        val reopened = open(file)
        reopened.addPreview(1, second)

        val final = open(file)
        assertEquals(first, final.previewFor(0))
        assertEquals(second, final.previewFor(1))
    }

    @Test fun secondAddForTheSamePageIsRejected() {
        val file = File(tempFolder.newFolder(), "previews.pgv")
        val previews = open(file)
        assertTrue(previews.addPreview(0, preview(seed = 1L)))
        assertFalse(previews.addPreview(0, preview(seed = 2L)))
        assertEquals(preview(seed = 1L), previews.previewFor(0))
    }

    @Test fun outOfRangePageIndexIsRejectedWithoutThrowing() {
        val file = File(tempFolder.newFolder(), "previews.pgv")
        val previews = open(file, pageCount = 5)
        assertFalse(previews.addPreview(5, preview()))
        assertFalse(previews.addPreview(-1, preview()))
        assertNull(previews.previewFor(5))
        assertNull(previews.previewFor(-1))
    }

    @Test fun truncatedHeaderIsTreatedAsNoFile() {
        val dir = tempFolder.newFolder()
        val file = File(dir, "previews.pgv")
        file.writeBytes(byteArrayOf(1, 2, 3))

        val previews = open(file)
        assertNull(previews.previewFor(0))
        assertTrue(previews.addPreview(0, preview()))
    }

    @Test fun indexTruncatedPartWayThroughIsTreatedAsNoFile() {
        val dir = tempFolder.newFolder()
        val file = File(dir, "previews.pgv")
        open(file, pageCount = 20).addPreview(0, preview())

        val fullBytes = file.readBytes()
        file.writeBytes(fullBytes.copyOf(fullBytes.size - 60))

        val reopened = open(file, pageCount = 20)
        assertNull(reopened.previewFor(0))
        assertTrue(reopened.addPreview(0, preview()))
    }

    @Test fun dataTruncatedAfterTheIndexIsAPerEntryMissNotAWholeFileMiss() {
        val dir = tempFolder.newFolder()
        val file = File(dir, "previews.pgv")
        val p0 = preview(seed = 1L)
        val p1 = preview(seed = 2L)
        val previews = open(file, pageCount = 20)
        previews.addPreview(0, p0)
        previews.addPreview(1, p1)

        // Truncate away exactly the second preview's pixel bytes, leaving the first one's data intact.
        val fullBytes = file.readBytes()
        file.writeBytes(fullBytes.copyOf(fullBytes.size - p1.pixels.size))

        val reopened = open(file, pageCount = 20)
        assertEquals(p0, reopened.previewFor(0))
        assertNull(reopened.previewFor(1))
    }

    @Test fun corruptMagicIsTreatedAsNoFile() {
        val dir = tempFolder.newFolder()
        val file = File(dir, "previews.pgv")
        open(file).addPreview(0, preview())

        RandomAccessFile(file, "rw").use { it.seek(0); it.writeInt(0xDEADBEEF.toInt()) }

        val reopened = open(file)
        assertNull(reopened.previewFor(0))
    }

    @Test fun wrongFormatVersionIsTreatedAsNoFile() {
        val dir = tempFolder.newFolder()
        val file = File(dir, "previews.pgv")
        open(file).addPreview(0, preview())

        RandomAccessFile(file, "rw").use { it.seek(4); it.writeInt(999) }

        val reopened = open(file)
        assertNull(reopened.previewFor(0))
    }

    @Test fun wrongIdentityIsTreatedAsNoFile() {
        val dir = tempFolder.newFolder()
        val file = File(dir, "previews.pgv")
        open(file, engineId = "engine-1").addPreview(0, preview())

        val reopened = open(file, engineId = "engine-2")
        assertNull(reopened.previewFor(0))
        assertTrue(reopened.addPreview(0, preview()))
    }

    @Test fun wrongPageCountIsTreatedAsNoFile() {
        val dir = tempFolder.newFolder()
        val file = File(dir, "previews.pgv")
        open(file, pageCount = 10).addPreview(0, preview())

        val reopened = open(file, pageCount = 20)
        assertNull(reopened.previewFor(0))
    }

    @Test fun indexEntryPointingPastEndOfFileIsAMiss() {
        val dir = tempFolder.newFolder()
        val file = File(dir, "previews.pgv")
        val previews = open(file, pageCount = 5)
        previews.addPreview(0, preview())

        // Corrupt page 1's (never-written, currently absent) index entry to point far past EOF.
        val entrySize = 20
        val indexStart = headerSizeFor("engine-1", "content-1", null)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(indexStart + 1L * entrySize)
            raf.writeLong(999_999L)
            raf.writeInt(4 * 4 * 2)
            raf.writeInt(4)
            raf.writeInt(4)
        }

        val reopened = open(file, pageCount = 5)
        assertNull(reopened.previewFor(1))
        assertEquals(preview(), reopened.previewFor(0))
    }

    private fun headerSizeFor(engineId: String, contentId: String, layoutVersion: String?): Long {
        // Mirrors PagePreviewFile's own header layout: magic + version (Int x2), two UTF strings,
        // a boolean, page count and pixel format byte.
        val buffer = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(buffer).use { out ->
            out.writeInt(0)
            out.writeInt(0)
            out.writeUTF(engineId)
            out.writeUTF(contentId)
            out.writeBoolean(layoutVersion != null)
            if (layoutVersion != null) out.writeUTF(layoutVersion)
            out.writeInt(0)
            out.writeByte(0)
        }
        return buffer.size().toLong()
    }

    @Test fun concurrentReadsDuringAppendsNeverSeeTornPreviews() {
        val dir = tempFolder.newFolder()
        val file = File(dir, "previews.pgv")
        val pageCount = 40
        val previews = open(file, pageCount = pageCount)
        val expected = (0 until pageCount).map { preview(seed = it.toLong() + 1) }

        val failures = AtomicInteger(0)
        val start = CountDownLatch(1)

        val writer = Thread {
            start.await()
            for (page in 0 until pageCount) previews.addPreview(page, expected[page])
        }

        val readers = (0 until 4).map {
            Thread {
                start.await()
                repeat(500) {
                    val page = (0 until pageCount).random()
                    val readBack = previews.previewFor(page)
                    if (readBack != null && readBack != expected[page]) failures.incrementAndGet()
                }
            }
        }

        (readers + writer).forEach { it.start() }
        start.countDown()
        (readers + writer).forEach { it.join(10_000) }

        assertEquals(0, failures.get())
        for (page in 0 until pageCount) assertEquals(expected[page], previews.previewFor(page))
    }

    @Test fun fileNameDiffersByEngineAndLayoutButNotByUnrelatedFields() {
        val a = PagePreviewFile.fileName("engine-1", "content-1", null)
        val b = PagePreviewFile.fileName("engine-2", "content-1", null)
        val c = PagePreviewFile.fileName("engine-1", "content-1", "layout-a")
        val d = PagePreviewFile.fileName("engine-1", "content-1", null)

        assertTrue(a != b)
        assertTrue(a != c)
        assertEquals(a, d)
    }
}
