package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryHomeState
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.PdfEngine
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.Raster
import com.folium.reader.core.pdf.RenderSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

private val FIXTURE_BYTES = byteArrayOf(1, 2, 3, 4)

private class ControllerDirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

private class ControllerFakeDisplayList : DisplayList {
    override fun render(spec: RenderSpec, cancellationSignal: CancellationSignal) = Raster(1, 1, byteArrayOf(0, 0, 0, 0))
    override fun close() = Unit
}

private class ControllerFakeDocument(override val pageCount: Int) : PdfDocument {
    override fun pageInfo(index: Int) = PageInfo(index, 100f, 200f, 0)
    override fun buildDisplayList(index: Int): DisplayList = ControllerFakeDisplayList()
    override fun extractText(index: Int): String = ""
    override fun outline() = emptyList<com.folium.reader.core.pdf.OutlineEntry>()
    override fun close() = Unit
}

private class ControllerFakeEngine(private val pageCount: Int = 3) : PdfEngine {
    override fun open(source: PdfSource): PdfDocument = ControllerFakeDocument(pageCount)
}

private class ControllerFakeThumbnailWriter : ThumbnailWriter {
    override fun write(raster: Raster, destination: File): Boolean {
        destination.writeBytes(byteArrayOf(1))
        return true
    }
}

private class RecordingThumbnailDecoder : ThumbnailDecoder {
    val decoded = mutableListOf<File>()
    override fun decode(file: File): android.graphics.Bitmap? {
        decoded += file
        return null
    }
}

class LibraryControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun controller(
        onState: (LibraryHomeState) -> Unit = {},
        thumbnailDecoder: ThumbnailDecoder = RecordingThumbnailDecoder(),
        ids: Iterator<String> = generateSequence(0) { it + 1 }.map { "id-$it" }.iterator()
    ) = LibraryController(
        filesDir = tempFolder.root,
        onState = onState,
        worker = ControllerDirectExecutor(),
        mainPost = { it() },
        delay = { _, action -> action() },
        thumbnailDecoder = thumbnailDecoder,
        engine = ControllerFakeEngine(),
        thumbnailWriter = ControllerFakeThumbnailWriter(),
        newId = { ids.next() }
    )

    @Test
    fun `load joins the catalog with progress and decodes each row's thumbnail`() {
        val importer = controller()
        importer.import(listOf(PickedSource("book.pdf") { FIXTURE_BYTES.inputStream() }))

        val states = mutableListOf<LibraryHomeState>()
        val decoder = RecordingThumbnailDecoder()
        val loader = controller(onState = { states += it }, thumbnailDecoder = decoder)

        loader.load()

        val shelf = states.single() as LibraryHomeState.Shelf
        assertEquals(1, shelf.entries.size)
        assertEquals(1, decoder.decoded.size)
    }

    @Test
    fun `an import batch reports per-file progress and sweeps staging exactly once`() {
        val states = mutableListOf<LibraryHomeState>()
        val paths = LibraryPaths(tempFolder.root)
        val garbage = paths.stagingDir("orphan")
        garbage.mkdirs()
        val controller = controller(onState = { states += it })

        controller.import(
            listOf(
                PickedSource("one.pdf") { FIXTURE_BYTES.inputStream() },
                PickedSource("two.pdf") { FIXTURE_BYTES.inputStream() }
            )
        )

        assertFalse("sweepStaging must run once per batch, before the first import", garbage.exists())

        val importingStates = states.filterIsInstance<LibraryHomeState.Shelf>().map { it.importing }
        assertEquals(listOf(1, 2), importingStates.filterNotNull().map { it.completed })
        assertTrue(importingStates.all { it == null || it.total == 2 })

        val finalState = states.last() as LibraryHomeState.Shelf
        assertNull(finalState.importing)
        assertEquals(2, finalState.report?.importedCount)
        assertEquals(2, finalState.entries.size)
    }

    @Test
    fun `remove deletes the book directory, the catalog row and the progress row`() {
        val states = mutableListOf<LibraryHomeState>()
        val controller = controller(onState = { states += it })
        controller.import(listOf(PickedSource("book.pdf") { FIXTURE_BYTES.inputStream() }))
        val imported = (states.last() as LibraryHomeState.Shelf).entries.single().book
        controller.recordProgress(imported.id, 1)
        val paths = LibraryPaths(tempFolder.root)

        controller.remove(imported.id)

        val shelf = states.last() as LibraryHomeState.Shelf
        assertTrue(shelf.entries.isEmpty())
        assertFalse(paths.bookDir(imported.id).exists())
        assertTrue(BookCatalogStore(paths).read().isEmpty())
        assertTrue(ProgressStore(paths).read().isEmpty())
    }

    @Test
    fun `openBook resolves the stored file and the clamped stored page`() {
        val states = mutableListOf<LibraryHomeState>()
        val controller = controller(onState = { states += it })
        controller.import(listOf(PickedSource("book.pdf") { FIXTURE_BYTES.inputStream() }))
        val imported = (states.last() as LibraryHomeState.Shelf).entries.single().book
        controller.recordProgress(imported.id, 50)
        var request: OpenBookRequest? = null

        controller.openBook(imported.id) { request = it }

        val paths = LibraryPaths(tempFolder.root)
        assertEquals(imported, request?.book)
        assertEquals(paths.documentFile(imported.id), request?.file)
        assertEquals(imported.pageCount - 1, request?.initialPage)
    }

    @Test
    fun `openBook reports no request for an id that is not in the catalog`() {
        val controller = controller()
        var invoked = false

        controller.openBook(BookId("missing")) { request ->
            invoked = true
            assertNull(request)
        }

        assertTrue(invoked)
    }

    @Test
    fun `dispose stops further state delivery`() {
        val states = mutableListOf<LibraryHomeState>()
        val controller = controller(onState = { states += it })

        controller.dispose()
        controller.load()

        assertTrue(states.isEmpty())
    }
}
