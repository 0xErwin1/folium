package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryHomeState
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.PdfEngine
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.Raster
import com.folium.reader.core.text.TextEngineVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

private class CoalescingDirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

/** Never opened by these tests — only present because [LibraryController] requires an engine. */
private class UnusedEngine : PdfEngine {
    override val textEngineVersion = TextEngineVersion("test-pdf")
    override fun open(source: PdfSource): PdfDocument = error("not exercised by this test")
}

/** Never written by these tests — only present because [LibraryController] requires a writer. */
private class UnusedThumbnailWriter : ThumbnailWriter {
    override fun write(raster: Raster, destination: File): Boolean = error("not exercised by this test")
}

/** Captures every scheduled delayed action instead of running it, so a test can drive them by hand. */
private class RecordingDelay {
    val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    val scheduler: (Long, () -> Unit) -> Unit = { millis, action -> scheduled += millis to action }
    fun runNext() = scheduled.removeAt(0).second()
}

class ProgressWriteCoalescingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun paths() = LibraryPaths(tempFolder.root)

    private fun controller(delay: RecordingDelay) = LibraryController(
        filesDir = tempFolder.root,
        onState = {},
        worker = CoalescingDirectExecutor(),
        mainPost = { it() },
        delay = delay.scheduler,
        engine = UnusedEngine(),
        thumbnailWriter = UnusedThumbnailWriter()
    )

    @Test
    fun `a burst of page changes for the same book schedules exactly one write`() {
        val delay = RecordingDelay()
        val controller = controller(delay)
        val id = BookId("book-1")

        controller.recordProgress(id, 1)
        controller.recordProgress(id, 2)
        controller.recordProgress(id, 3)

        assertEquals(1, delay.scheduled.size)
        assertEquals(LibraryController.PROGRESS_WRITE_DELAY_MILLIS, delay.scheduled.single().first)
    }

    @Test
    fun `the coalesced write persists only the last page`() {
        val delay = RecordingDelay()
        val controller = controller(delay)
        val id = BookId("book-1")

        controller.recordProgress(id, 1)
        controller.recordProgress(id, 2)
        controller.recordProgress(id, 7)
        delay.runNext()

        assertEquals(listOf(com.folium.reader.core.library.ProgressRecord(id, 7)), ProgressStore(paths()).read())
    }

    @Test
    fun `a burst after a completed write schedules a second write`() {
        val delay = RecordingDelay()
        val controller = controller(delay)
        val id = BookId("book-1")

        controller.recordProgress(id, 1)
        delay.runNext()
        controller.recordProgress(id, 2)

        assertEquals(1, delay.scheduled.size)
    }

    @Test
    fun `flushProgressNow writes immediately without waiting for the delay`() {
        val states = mutableListOf<LibraryHomeState>()
        val delay = RecordingDelay()
        val controller = LibraryController(
            filesDir = tempFolder.root,
            onState = { states += it.state },
            worker = CoalescingDirectExecutor(),
            mainPost = { it() },
            delay = delay.scheduler,
            engine = UnusedEngine(),
            thumbnailWriter = UnusedThumbnailWriter()
        )
        val id = BookId("book-1")
        controller.recordProgress(id, 5)

        controller.flushProgressNow()

        assertEquals(listOf(com.folium.reader.core.library.ProgressRecord(id, 5)), ProgressStore(paths()).read())
    }

    @Test
    fun `flushProgressNow with nothing pending writes nothing`() {
        val delay = RecordingDelay()
        val controller = controller(delay)

        controller.flushProgressNow()

        assertTrue(ProgressStore(paths()).read().isEmpty())
    }
}
