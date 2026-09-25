package com.folium.reader.library

import com.folium.reader.core.ink.SheetAlreadyOpenException
import com.folium.reader.core.ink.SheetAnchor
import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.ink.SheetSummary
import com.folium.reader.core.ink.SheetTemplate
import com.folium.reader.core.library.BookFormat
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.AppearanceMode
import com.folium.reader.core.library.ImportProgress
import com.folium.reader.core.library.LibraryHomeState
import com.folium.reader.core.library.LibraryViewMode
import com.folium.reader.core.library.LibraryViewModes
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.PdfEngine
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.Raster
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextEngineVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

private val FIXTURE_BYTES = "%PDF-1.4 fixture bytes".toByteArray()

private class ControllerDirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

private class ControllerQueuedExecutor : Executor {
    private val commands = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        commands.addLast(command)
    }

    fun runNext() = commands.removeFirst().run()
}

private class ControllerFakeDisplayList : DisplayList {
    override fun render(spec: RenderSpec, cancellationSignal: CancellationSignal) = Raster(1, 1, byteArrayOf(0, 0, 0, 0))
    override fun close() = Unit
}

private class ControllerFakeDocument(override val pageCount: Int) : PdfDocument {
    override fun pageInfo(index: Int) = PageInfo(index, 100f, 200f, 0)
    override fun buildDisplayList(index: Int): DisplayList = ControllerFakeDisplayList()
    override fun extractText(index: Int): TextPage = TextPage(emptyList(), TextSource.NATIVE_PDF)
    override fun outline() = emptyList<com.folium.reader.core.pdf.OutlineEntry>()
    override fun metadata() = com.folium.reader.core.pdf.DocumentMetadata.NONE
    override fun close() = Unit
}

private class ControllerFakeEngine(private val pageCount: Int = 3) : PdfEngine {
    override val textEngineVersion = TextEngineVersion("test-pdf")
    override fun open(source: PdfSource): PdfDocument = ControllerFakeDocument(pageCount)
}

private class ControllerFakeThumbnailWriter : ThumbnailWriter {
    override fun write(raster: Raster, destination: File): Boolean {
        destination.writeBytes(byteArrayOf(1))
        return true
    }
}

private class RecordingThumbnailDecoder(private val beforeDecode: () -> Unit = {}) : ThumbnailDecoder {
    val decoded = mutableListOf<File>()
    override fun decode(file: File): android.graphics.Bitmap? {
        beforeDecode()
        decoded += file
        return null
    }
}

/** The sheets a test library holds, changed the way [SheetStore] would change them. */
private class FakeBookSheets(initial: List<SheetSummary>) : BookSheets {
    val sheets = initial.toMutableList()
    val open = mutableSetOf<SheetId>()

    override fun anchoredTo(book: BookId): List<SheetSummary> = sheets.filter { it.anchor?.bookId == book }

    override fun detach(id: SheetId, title: String) {
        if (id in open) throw SheetAlreadyOpenException(id)
        val index = sheets.indexOfFirst { it.id == id }
        sheets[index] = sheets[index].copy(anchor = null, title = title)
    }

    override fun delete(id: SheetId) {
        check(id !in open) { "cannot delete sheet $id while it is open" }
        sheets.removeAll { it.id == id }
    }

    fun byId(id: String): SheetSummary? = sheets.firstOrNull { it.id == SheetId(id) }
}

private fun anchoredSheet(id: String, title: String, book: BookId?) = SheetSummary(
    SheetId(id), title, 0L, 0L, SheetTemplate.BLANK,
    book?.let { SheetAnchor.Page(it, pageIndex = 18, rank = 0L) }
)

class LibraryControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun controller(
        onState: (LibraryHome) -> Unit = {},
        thumbnailDecoder: ThumbnailDecoder = RecordingThumbnailDecoder(),
        ids: Iterator<String> = generateSequence(0) { it + 1 }.map { "id-$it" }.iterator(),
        worker: Executor = ControllerDirectExecutor(),
        sheets: BookSheets? = null
    ) = LibraryController(
        filesDir = tempFolder.root,
        onState = onState,
        worker = worker,
        mainPost = { it() },
        delay = { _, action -> action() },
        thumbnailDecoder = thumbnailDecoder,
        engine = ControllerFakeEngine(),
        thumbnailWriter = ControllerFakeThumbnailWriter(),
        newId = { ids.next() },
        sheets = sheets
    )

    @Test
    fun `load joins the catalog with progress and decodes each row's thumbnail`() {
        val importer = controller()
        importer.import(listOf(PickedSource("book.pdf") { FIXTURE_BYTES.inputStream() }))

        val states = mutableListOf<LibraryHomeState>()
        val decoder = RecordingThumbnailDecoder()
        val loader = controller(onState = { states += it.state }, thumbnailDecoder = decoder)

        loader.load()

        assertEquals(LibraryHomeState.Loading, states.first())
        val shelf = states.last() as LibraryHomeState.Shelf
        assertEquals(1, shelf.entries.size)
        assertEquals(1, decoder.decoded.size)
    }

    /**
     * The screen renders the thumbnails it is handed, so a decode that lands has to arrive as a new
     * published value rather than land in a cache the screen happens to read later. Publishing the
     * map is what makes the arrival observable at all.
     */
    @Test
    fun `a landed decode reaches the screen as part of the published state`() {
        val homes = mutableListOf<LibraryHome>()
        val controller = controller(onState = { homes += it })

        controller.import(listOf(PickedSource("book.pdf") { FIXTURE_BYTES.inputStream() }))

        val importedId = (homes.last().state as LibraryHomeState.Shelf).entries.single().book.id
        assertEquals(
            "no state published before the decode ran may claim to know the row's thumbnail",
            emptyMap<BookId, android.graphics.Bitmap?>(),
            homes.first().thumbnails
        )
        assertTrue(
            "the state published after the decode must carry the decoded thumbnail",
            importedId in homes.last().thumbnails
        )
    }

    /**
     * Every reader close reloads the shelf, so the common reload decodes nothing and must hand the
     * screen back the very map it already has rather than an equal copy of it.
     */
    @Test
    fun `a reload that decodes nothing new republishes the same thumbnails`() {
        val homes = mutableListOf<LibraryHome>()
        val controller = controller(onState = { homes += it })
        controller.import(listOf(PickedSource("book.pdf") { FIXTURE_BYTES.inputStream() }))
        val decoded = homes.last().thumbnails

        controller.load()

        assertSame(decoded, homes.last().thumbnails)
    }

    @Test
    fun `a chosen view mode is published at once and read back by the next load`() {
        val homes = mutableListOf<LibraryHome>()
        val controller = controller(onState = { homes += it })
        controller.load()

        controller.setViewMode(LibraryViewMode.GRID)

        assertEquals(LibraryViewMode.GRID, homes.last().viewMode)
        val restarted = mutableListOf<LibraryHome>()
        controller(onState = { restarted += it }).load()
        assertEquals(LibraryViewMode.GRID, restarted.last().viewMode)
    }

    @Test
    fun `a library with no stored preference opens on the default`() {
        val homes = mutableListOf<LibraryHome>()

        controller(onState = { homes += it }).load()

        assertEquals(LibraryViewModes.DEFAULT, homes.last().viewMode)
    }

    @Test
    fun `a chosen appearance is published and read back by the next controller`() {
        val homes = mutableListOf<LibraryHome>()
        val controller = controller(onState = { homes += it })
        controller.load()

        controller.setAppearanceMode(AppearanceMode.DARK)

        assertEquals(AppearanceMode.DARK, homes.last().appearanceMode)
        val restarted = mutableListOf<LibraryHome>()
        controller(onState = { restarted += it }).load()
        assertEquals(AppearanceMode.DARK, restarted.last().appearanceMode)
    }

    @Test
    fun `a library with no stored appearance follows the system`() {
        val homes = mutableListOf<LibraryHome>()

        controller(onState = { homes += it }).load()

        assertEquals(AppearanceMode.SYSTEM, homes.last().appearanceMode)
    }

    @Test
    fun `startup publishes persisted preferences in loading before decoding and then publishes the shelf`() {
        val paths = LibraryPaths(tempFolder.root)
        controller().import(listOf(PickedSource("book.pdf") { FIXTURE_BYTES.inputStream() }))
        ViewModeStore(paths).write(LibraryViewMode.GRID)
        AppearanceModeStore(paths).write(AppearanceMode.LIGHT)
        val homes = mutableListOf<LibraryHome>()
        val worker = ControllerQueuedExecutor()
        val decoder = RecordingThumbnailDecoder {
            assertEquals(1, homes.size)
            assertEquals(LibraryHomeState.Loading, homes.single().state)
            assertEquals(LibraryViewMode.GRID, homes.single().viewMode)
            assertEquals(AppearanceMode.LIGHT, homes.single().appearanceMode)
        }
        val controller = controller(onState = { homes += it }, thumbnailDecoder = decoder, worker = worker)

        controller.load()

        assertTrue("load must only enqueue blocking file work", homes.isEmpty())
        worker.runNext()
        assertEquals(1, decoder.decoded.size)
        assertEquals(2, homes.size)
        assertTrue(homes.first().state is LibraryHomeState.Loading)
        assertTrue(homes.last().state is LibraryHomeState.Shelf)
        assertEquals(listOf(LibraryViewMode.GRID, LibraryViewMode.GRID), homes.map { it.viewMode })
        assertEquals(listOf(AppearanceMode.LIGHT, AppearanceMode.LIGHT), homes.map { it.appearanceMode })
    }

    /** Choosing the mode already in force must not cost a republication or a file write. */
    @Test
    fun `choosing the mode already in force changes nothing`() {
        val homes = mutableListOf<LibraryHome>()
        val controller = controller(onState = { homes += it })
        controller.load()
        val published = homes.size

        controller.setViewMode(LibraryViewModes.DEFAULT)

        assertEquals(published, homes.size)
        assertFalse(LibraryPaths(tempFolder.root).viewModeFile.exists())
    }

    @Test
    fun `an import batch reports per-file progress and sweeps staging exactly once`() {
        val states = mutableListOf<LibraryHomeState>()
        val paths = LibraryPaths(tempFolder.root)
        val garbage = paths.stagingDir("orphan")
        garbage.mkdirs()
        val controller = controller(onState = { states += it.state })

        controller.import(
            listOf(
                PickedSource("one.pdf") { FIXTURE_BYTES.inputStream() },
                PickedSource("two.pdf") { FIXTURE_BYTES.inputStream() }
            )
        )

        assertFalse("sweepStaging must run once per batch, before the first import", garbage.exists())
        val firstImporting = states.filterIsInstance<LibraryHomeState.Shelf>().first().importing
        assertEquals(
            "the importing state must be published before the first file's blocking work starts",
            ImportProgress(0, 2),
            firstImporting
        )

        val importingStates = states.filterIsInstance<LibraryHomeState.Shelf>().map { it.importing }
        assertEquals(listOf(0, 1, 2), importingStates.filterNotNull().map { it.completed })
        assertTrue(importingStates.all { it == null || it.total == 2 })

        val finalState = states.last() as LibraryHomeState.Shelf
        assertNull(finalState.importing)
        assertEquals(2, finalState.report?.importedCount)
        assertEquals(2, finalState.entries.size)
    }

    @Test
    fun `remove deletes the book directory, the catalog row and the progress row`() {
        val states = mutableListOf<LibraryHomeState>()
        val controller = controller(onState = { states += it.state })
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

    /**
     * The catalog row goes first precisely so this case leaves an intact book rather than a listed
     * one whose file has already been deleted. The rewrite is made to fail by occupying the temp
     * path [AtomicTextFile] stages into with a non-empty directory.
     */
    @Test
    fun `a failed catalog rewrite leaves the book listed and its files intact`() {
        val states = mutableListOf<LibraryHomeState>()
        val controller = controller(onState = { states += it.state })
        controller.import(listOf(PickedSource("book.pdf") { FIXTURE_BYTES.inputStream() }))
        val imported = (states.last() as LibraryHomeState.Shelf).entries.single().book
        controller.recordProgress(imported.id, 1)
        val paths = LibraryPaths(tempFolder.root)
        blockRewriteOf(paths.catalogFile)

        controller.remove(imported.id)

        val shelf = states.last() as LibraryHomeState.Shelf
        assertEquals(listOf(imported), shelf.entries.map { it.book })
        assertTrue(paths.documentFile(imported.id, BookFormat.PDF).exists())
        assertEquals(listOf(imported), BookCatalogStore(paths).read())
        assertEquals(1, ProgressStore(paths).read().size)
    }

    /**
     * A progress row that outlives its book is dropped by the shelf join and rewritten away by the
     * next progress write, so a failed removal there must not stop the book from disappearing.
     */
    @Test
    fun `a failed progress rewrite still removes the book`() {
        val states = mutableListOf<LibraryHomeState>()
        val controller = controller(onState = { states += it.state })
        controller.import(listOf(PickedSource("book.pdf") { FIXTURE_BYTES.inputStream() }))
        val imported = (states.last() as LibraryHomeState.Shelf).entries.single().book
        controller.recordProgress(imported.id, 1)
        val paths = LibraryPaths(tempFolder.root)
        blockRewriteOf(paths.progressFile)

        controller.remove(imported.id)

        val shelf = states.last() as LibraryHomeState.Shelf
        assertTrue(shelf.entries.isEmpty())
        assertFalse(paths.bookDir(imported.id).exists())
        assertTrue(BookCatalogStore(paths).read().isEmpty())
    }

    private fun importedWith(states: MutableList<LibraryHomeState>, controller: LibraryController): com.folium.reader.core.library.LibraryBook {
        controller.import(listOf(PickedSource("book.pdf") { FIXTURE_BYTES.inputStream() }))
        return (states.last() as LibraryHomeState.Shelf).entries.single().book
    }

    @Test
    fun `removing a book keeps its sheets, detached and named after it`() {
        val states = mutableListOf<LibraryHomeState>()
        val sheets = FakeBookSheets(emptyList())
        val controller = controller(onState = { states += it.state }, sheets = sheets)
        val book = importedWith(states, controller)
        sheets.sheets += listOf(
            anchoredSheet("notes", "Notes", book.id),
            anchoredSheet("default", "${book.title} · 19", book.id),
            anchoredSheet("elsewhere", "Other", BookId("other-book")),
            anchoredSheet("standalone", "Loose", null)
        )

        controller.remove(book.id)

        assertEquals(anchoredSheet("notes", "${book.title} · Notes", null), sheets.byId("notes"))
        assertEquals(anchoredSheet("default", "${book.title} · 19", null), sheets.byId("default"))
        assertEquals(anchoredSheet("elsewhere", "Other", BookId("other-book")), sheets.byId("elsewhere"))
        assertEquals(anchoredSheet("standalone", "Loose", null), sheets.byId("standalone"))
        assertTrue((states.last() as LibraryHomeState.Shelf).entries.isEmpty())
    }

    @Test
    fun `removing a book with its sheets deletes only that book's sheets`() {
        val states = mutableListOf<LibraryHomeState>()
        val sheets = FakeBookSheets(emptyList())
        val controller = controller(onState = { states += it.state }, sheets = sheets)
        val book = importedWith(states, controller)
        sheets.sheets += listOf(
            anchoredSheet("notes", "Notes", book.id),
            anchoredSheet("elsewhere", "Other", BookId("other-book")),
            anchoredSheet("standalone", "Loose", null)
        )

        controller.remove(book.id, deleteSheets = true)

        assertEquals(listOf(SheetId("elsewhere"), SheetId("standalone")), sheets.sheets.map { it.id })
        assertTrue((states.last() as LibraryHomeState.Shelf).entries.isEmpty())
    }

    @Test
    fun `an open sheet is skipped and the rest of the removal still happens`() {
        val states = mutableListOf<LibraryHomeState>()
        val sheets = FakeBookSheets(emptyList())
        val controller = controller(onState = { states += it.state }, sheets = sheets)
        val book = importedWith(states, controller)
        sheets.sheets += listOf(anchoredSheet("open", "Open", book.id), anchoredSheet("closed", "Closed", book.id))
        sheets.open += SheetId("open")

        controller.remove(book.id, deleteSheets = true)

        assertEquals(listOf(anchoredSheet("open", "Open", book.id)), sheets.sheets)
        assertTrue((states.last() as LibraryHomeState.Shelf).entries.isEmpty())
    }

    @Test
    fun `removing a book forgets the sheet it was last read on and then reports completion`() {
        val states = mutableListOf<LibraryHomeState>()
        val controller = controller(onState = { states += it.state }, sheets = FakeBookSheets(emptyList()))
        val book = importedWith(states, controller)
        controller.recordSheetCursor(book.id, SheetId("notes"))
        var completed = false

        controller.remove(book.id) { completed = true }

        assertNull(SheetCursorStore(LibraryPaths(tempFolder.root)).get(book.id))
        assertTrue(completed)
    }

    @Test
    fun `a failed catalog rewrite leaves the book's sheets anchored to it`() {
        val states = mutableListOf<LibraryHomeState>()
        val sheets = FakeBookSheets(emptyList())
        val controller = controller(onState = { states += it.state }, sheets = sheets)
        val book = importedWith(states, controller)
        sheets.sheets += anchoredSheet("notes", "Notes", book.id)
        blockRewriteOf(LibraryPaths(tempFolder.root).catalogFile)

        controller.remove(book.id, deleteSheets = true)

        assertEquals(listOf(anchoredSheet("notes", "Notes", book.id)), sheets.sheets)
    }

    private fun blockRewriteOf(target: File) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.mkdirs()
        File(temp, "occupied").writeBytes(byteArrayOf(1))
    }

    @Test
    fun `openBook resolves the stored file and the clamped stored page`() {
        val states = mutableListOf<LibraryHomeState>()
        val controller = controller(onState = { states += it.state })
        controller.import(listOf(PickedSource("book.pdf") { FIXTURE_BYTES.inputStream() }))
        val imported = (states.last() as LibraryHomeState.Shelf).entries.single().book
        controller.recordProgress(imported.id, 50)
        var request: OpenBookRequest? = null

        controller.openBook(imported.id) { request = it }

        val paths = LibraryPaths(tempFolder.root)
        assertEquals(imported, request?.book)
        assertEquals(paths.documentFile(imported.id, BookFormat.PDF), request?.file)
        assertEquals(imported.pageCount - 1, request?.initialPage)
    }

    @Test
    fun `openBook carries the sheet the book was last read on`() {
        val states = mutableListOf<LibraryHomeState>()
        val controller = controller(onState = { states += it.state })
        controller.import(listOf(PickedSource("book.pdf") { FIXTURE_BYTES.inputStream() }))
        val imported = (states.last() as LibraryHomeState.Shelf).entries.single().book
        var request: OpenBookRequest? = null

        controller.recordSheetCursor(imported.id, SheetId("sheet-1"))
        controller.openBook(imported.id) { request = it }

        assertEquals(SheetId("sheet-1"), request?.initialSheet)
        assertEquals(SheetId("sheet-1"), SheetCursorStore(LibraryPaths(tempFolder.root)).get(imported.id))
    }

    @Test
    fun `a book last read on a page opens with no sheet`() {
        val states = mutableListOf<LibraryHomeState>()
        val controller = controller(onState = { states += it.state })
        controller.import(listOf(PickedSource("book.pdf") { FIXTURE_BYTES.inputStream() }))
        val imported = (states.last() as LibraryHomeState.Shelf).entries.single().book
        var request: OpenBookRequest? = null

        controller.recordSheetCursor(imported.id, SheetId("sheet-1"))
        controller.recordSheetCursor(imported.id, null)
        controller.openBook(imported.id) { request = it }

        assertNull(request?.initialSheet)
        assertNull(SheetCursorStore(LibraryPaths(tempFolder.root)).get(imported.id))
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

    /**
     * Mirrors `FoliumActivity.showBook(null)`: the reader closes, the pending page is flushed, and
     * the shelf is reloaded — the two calls post to the same serial worker, so the load must see the
     * flush's write rather than the page recorded before the read started.
     */
    @Test
    fun `flushProgressNow followed by load surfaces the page just recorded`() {
        val states = mutableListOf<LibraryHomeState>()
        val controller = controller(onState = { states += it.state })
        controller.import(listOf(PickedSource("book.pdf") { FIXTURE_BYTES.inputStream() }))
        val imported = (states.last() as LibraryHomeState.Shelf).entries.single().book

        controller.recordProgress(imported.id, 2)
        controller.flushProgressNow()
        controller.load()

        val shelf = states.last() as LibraryHomeState.Shelf
        assertEquals(2, shelf.entries.single().pageIndex)
    }

    @Test
    fun `import completion reports successful and failed sources`() {
        val controller = controller()
        var report: com.folium.reader.core.library.ImportReport? = null

        controller.import(
            listOf(
                PickedSource("book.pdf") { FIXTURE_BYTES.inputStream() },
                PickedSource("missing.pdf") { throw java.io.FileNotFoundException() }
            )
        ) { completed -> report = completed }

        assertEquals(1, report?.importedCount)
        assertEquals(1, report?.failures?.size)
    }

    @Test
    fun `dispose suppresses import completion`() {
        val worker = ControllerQueuedExecutor()
        val controller = controller(worker = worker)
        var completed = false

        controller.import(listOf(PickedSource("book.pdf") { FIXTURE_BYTES.inputStream() })) { completed = true }
        controller.dispose()
        worker.runNext()

        assertFalse(completed)
    }

    @Test
    fun `dispose stops further state delivery`() {
        val states = mutableListOf<LibraryHomeState>()
        val controller = controller(onState = { states += it.state })

        controller.dispose()
        controller.load()

        assertTrue(states.isEmpty())
    }
}
