package com.folium.reader.library

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.library.AppearanceMode
import com.folium.reader.core.library.AppearanceModes
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.ImportOutcome
import com.folium.reader.core.library.ImportProgress
import com.folium.reader.core.library.ImportReport
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.library.LibraryHomeState
import com.folium.reader.core.library.LibraryShelf
import com.folium.reader.core.library.LibraryViewMode
import com.folium.reader.core.library.LibraryViewModes
import com.folium.reader.core.library.ShelfEntry
import com.folium.reader.core.pdf.PdfEngine
import com.folium.reader.reader.PdfEngines
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The single thread every operation that touches the engine or the library files runs on, app-wide
 * (design decision D-T1). Serializing every such operation onto one thread makes the engine's
 * single-session rule and the single-writer rule on `filesDir/library/` structural rather than
 * asserted. `ReaderHostController`'s own worker is folded into this one once the reader-open-path
 * rework wires it through; until then it keeps its separate thread.
 */
val documentWork: Executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "folium-document-work") }

/**
 * The book to open, its stored file and the page to restore, resolved off the main thread, with the
 * sheet the reader was last left on when it was left on one. [initialSheet] is only a candidate: the
 * reader resumes on it once the book's sheets have loaded and it is still among them.
 */
data class OpenBookRequest(val book: LibraryBook, val file: File, val initialPage: Int, val initialSheet: SheetId? = null)

/** A reading position not yet written, carrying the pagination it was reached under. */
private data class PendingProgress(
    val bookId: BookId,
    val pageIndex: Int,
    val pageCount: Int,
    val token: com.folium.reader.core.pdf.ReadingPositionToken? = null
)

/**
 * The library home as the app renders it: the neutral [LibraryHomeState], decoded thumbnails, and
 * the global preferences that the activity applies to both the library and reader.
 *
 * The pairing lives here rather than inside `reader-core`'s shelf model so that model stays free of
 * platform types. Carrying the thumbnails in the published value rather than exposing the decoder's
 * cache is what makes a decode that lands after a row is on screen reach it: the screen renders the
 * map it was handed, so a new one arriving is an ordinary state change. A book with no thumbnail,
 * or one that would not decode, is present with a `null` value.
 */
data class LibraryHome(
    val state: LibraryHomeState,
    val thumbnails: Map<BookId, Bitmap?> = emptyMap(),
    val viewMode: LibraryViewMode = LibraryViewModes.DEFAULT,
    val appearanceMode: AppearanceMode = AppearanceModes.DEFAULT
)

/**
 * Owns the app-managed library for the activity's whole lifetime: created in `onCreate`, disposed
 * in `onDestroy`. Every load, import, removal and progress write runs on [worker]; state reaches
 * [onState] as a whole [LibraryHome] through [mainPost], mirroring `ReaderHostController`'s own
 * worker-plus-main-post shape.
 *
 * [recordProgress] coalesces a burst of page changes into a single write: the pending page is
 * stored immediately, but the write itself is scheduled through [delay] and only the last page
 * before the delay elapses is ever persisted. [flushProgressNow] runs the same drain with no delay,
 * so posting it before a reader session's teardown guarantees the write lands first.
 */
class LibraryController(
    filesDir: File,
    private var onState: (LibraryHome) -> Unit,
    private val worker: Executor = documentWork,
    private val mainPost: (() -> Unit) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
    private val delay: (Long, () -> Unit) -> Unit =
        { millis, action -> Handler(Looper.getMainLooper()).postDelayed(action, millis) },
    private val thumbnailDecoder: ThumbnailDecoder = BitmapFactoryThumbnailDecoder(),
    engine: PdfEngine = PdfEngines.load(),
    thumbnailWriter: ThumbnailWriter = BitmapThumbnailWriter(),
    newId: () -> String = { UUID.randomUUID().toString() },
    clock: () -> Long = System::currentTimeMillis,
    private val sheets: BookSheets? = null
) {
    private val paths = LibraryPaths(filesDir)
    private val catalog = BookCatalogStore(paths)
    private val progress = ProgressStore(paths)
    private val sheetCursors = SheetCursorStore(paths)
    private val files = BookFiles(paths)
    private val viewModes = ViewModeStore(paths)
    private val appearanceModes = AppearanceModeStore(paths)
    private val importer = BookImporter(paths, catalog, engine, thumbnailWriter, newId, clock)

    private val disposeLock = Any()
    private var disposed = false

    private val thumbnailLock = Any()
    private val thumbnailCache = mutableMapOf<BookId, Bitmap?>()

    private val pendingLock = Any()
    private var pendingProgress: PendingProgress? = null
    private var progressFlushScheduled = false

    @Volatile private var lastShelf = LibraryHomeState.Shelf(emptyList())
    @Volatile private var lastThumbnails: Map<BookId, Bitmap?> = emptyMap()
    @Volatile private var lastViewMode = LibraryViewModes.DEFAULT
    @Volatile private var lastAppearanceMode = AppearanceModes.DEFAULT
    @Volatile private var shelfPublished = false

    fun load() {
        worker.execute {
            lastViewMode = viewModes.read()
            lastAppearanceMode = appearanceModes.read()
            publishLoading()

            val entries = joinedEntries()
            decodeThumbnails(entries)
            publish(LibraryHomeState.Shelf(entries))
        }
    }

    fun import(sources: List<PickedSource>, onComplete: ((ImportReport) -> Unit)? = null) {
        if (sources.isEmpty()) return

        worker.execute {
            importer.sweepStaging()
            val total = sources.size
            val outcomes = ArrayList<ImportOutcome>(total)

            publishImporting(ImportProgress(0, total))

            sources.forEachIndexed { index, source ->
                outcomes += importer.import(source)
                publishImporting(ImportProgress(index + 1, total))
            }

            val entries = joinedEntries()
            decodeThumbnails(entries)
            val report = ImportReport(outcomes)
            publish(LibraryHomeState.Shelf(entries, report = report))
            if (onComplete != null) mainPost { if (!isDisposed()) onComplete(report) }
        }
    }

    /** Replaces the activity-owned state receiver after a configuration change. */
    fun rebind(onState: (LibraryHome) -> Unit) {
        this.onState = onState
    }

    /**
     * Removes a book, catalog row first.
     *
     * The catalog is the library's only source of truth, so a failed catalog rewrite must leave the
     * book fully intact rather than gutted: deleting the files first would republish a listed book
     * whose `document.pdf` is already gone. A failed progress-row removal after a successful catalog
     * removal is ignored by design — the book is no longer listed, the shelf join drops the orphan
     * row, and the next progress write rewrites the file without it. The sheet it was last read on
     * is forgotten the same way.
     *
     * Its sheets are handled only once the catalog row is gone, so a failed removal leaves them
     * anchored to a book that is still there: by default each one is detached and kept, named after
     * the book — see [detachedSheetTitle] — and with [deleteSheets] each one is deleted instead. A
     * sheet that cannot be changed, an open one included, is skipped and stays anchored to a book no
     * longer on the shelf, which the shelf shows rather than hides. [onComplete] runs on the main
     * thread once all of it has, so the caller can re-read the sheets it shows.
     */
    fun remove(id: BookId, deleteSheets: Boolean = false, onComplete: (() -> Unit)? = null) {
        worker.execute {
            val book = catalog.read().firstOrNull { it.id == id }

            if (!catalog.remove(id)) {
                publish(LibraryHomeState.Shelf(joinedEntries()))
                if (onComplete != null) mainPost { if (!isDisposed()) onComplete() }
                return@execute
            }

            files.deleteBook(id)
            progress.remove(id)
            sheetCursors.remove(id)
            if (book != null) releaseSheets(book, deleteSheets)

            synchronized(thumbnailLock) {
                thumbnailCache.remove(id)
                lastThumbnails = thumbnailCache.toMap()
            }
            publish(LibraryHomeState.Shelf(joinedEntries()))
            if (onComplete != null) mainPost { if (!isDisposed()) onComplete() }
        }
    }

    private fun releaseSheets(book: LibraryBook, deleteSheets: Boolean) {
        val port = sheets ?: return
        val anchored = runCatching { port.anchoredTo(book.id) }.getOrDefault(emptyList())

        anchored.forEach { sheet ->
            runCatching {
                if (deleteSheets) port.delete(sheet.id) else port.detach(sheet.id, detachedSheetTitle(sheet.title, book.title))
            }
        }
    }

    /**
     * Counts how many of [id]'s pages have handwriting on the worker and reports it on the main
     * thread; a page ink directory that cannot be listed counts as none.
     */
    fun countInkedPages(id: BookId, onCount: (Int) -> Unit) {
        worker.execute {
            val count = runCatching { files.inkedPageCount(id) }.getOrDefault(0)
            mainPost { if (!isDisposed()) onCount(count) }
        }
    }

    fun openBook(id: BookId, onOpen: (OpenBookRequest?) -> Unit) {
        worker.execute {
            val book = catalog.read().firstOrNull { it.id == id }
            val request = book?.let {
                val record = progress.read().firstOrNull { candidate -> candidate.bookId == id }
                val entry = ShelfEntry(it, record?.pageIndex ?: 0, record?.pageCount ?: 0)
                OpenBookRequest(it, files.document(it), entry.pageIndex, sheetCursors.get(id))
            }
            mainPost { if (!isDisposed()) onOpen(request) }
        }
    }

    fun recordProgress(
        id: BookId,
        pageIndex: Int,
        pageCount: Int = 0,
        token: com.folium.reader.core.pdf.ReadingPositionToken? = null
    ) {
        val shouldSchedule = synchronized(pendingLock) {
            pendingProgress = PendingProgress(id, pageIndex, pageCount, token)
            if (progressFlushScheduled) {
                false
            } else {
                progressFlushScheduled = true
                true
            }
        }

        if (shouldSchedule) {
            delay(PROGRESS_WRITE_DELAY_MILLIS) { worker.execute(::flushPendingProgress) }
        }
    }

    /**
     * Stores [sheet] as the sheet book [id] is being read on, or clears it when the reader lands on
     * one of the book's pages. Written on [worker] straight away rather than coalesced like
     * [recordProgress]: the sheet being read changes a step at a time, never in a burst.
     */
    fun recordSheetCursor(id: BookId, sheet: SheetId?) {
        worker.execute {
            if (sheet == null) sheetCursors.remove(id) else sheetCursors.put(id, sheet)
        }
    }

    fun flushProgressNow() {
        worker.execute(::flushPendingProgress)
    }

    /**
     * Switches the home's layout and stores the choice.
     *
     * The shelf is republished before the write rather than after it, so the screen switches at the
     * speed of a post while the file catches up behind it. A shelf that has not been published yet
     * is not invented here: the load already on its way reads the mode back from the file it is
     * about to be written to.
     */
    fun setViewMode(mode: LibraryViewMode) {
        worker.execute {
            if (lastViewMode == mode) return@execute

            lastViewMode = mode
            if (shelfPublished) publish(lastShelf)

            viewModes.write(mode)
        }
    }

    /** Republishes the current shelf with the new palette, then persists it on the same worker. */
    fun setAppearanceMode(mode: AppearanceMode) {
        worker.execute {
            if (lastAppearanceMode == mode) return@execute

            lastAppearanceMode = mode
            if (shelfPublished) publish(lastShelf)

            appearanceModes.write(mode)
        }
    }

    fun dismissReport() {
        worker.execute { publish(lastShelf.copy(report = null)) }
    }

    fun dispose() {
        synchronized(disposeLock) { disposed = true }
    }

    private fun flushPendingProgress() {
        val pending = synchronized(pendingLock) {
            progressFlushScheduled = false
            pendingProgress.also { pendingProgress = null }
        } ?: return

        progress.put(pending.bookId, pending.pageIndex, pending.pageCount, pending.token)
    }

    private fun joinedEntries(): List<ShelfEntry> = LibraryShelf.entries(catalog.read(), progress.read())

    /**
     * A snapshot is taken only when a decode actually landed, so a load that finds every row
     * already decoded republishes the same map instance. `Bitmap` is mutable and therefore compared
     * by identity where the shelf is rendered; handing back the same map is what lets rows that did
     * not change be skipped rather than recomposed on every reload.
     *
     * Decoding happens outside [thumbnailLock] and each result is inserted on its own, so the lock
     * is never held for longer than a map write however many files a batch has to read.
     */
    private fun decodeThumbnails(entries: List<ShelfEntry>) {
        val undecoded = synchronized(thumbnailLock) { entries.filter { it.book.id !in thumbnailCache } }
        if (undecoded.isEmpty()) return

        undecoded.forEach { entry ->
            val decoded = thumbnailDecoder.decode(files.thumbnail(entry.book.id))
            synchronized(thumbnailLock) { thumbnailCache[entry.book.id] = decoded }
        }

        synchronized(thumbnailLock) { lastThumbnails = thumbnailCache.toMap() }
    }

    /**
     * Republishes the shelf as it currently stands with [progress] attached. Publishing `0 of n`
     * before the first file is what makes the screen's mitigation for the single worker — rows
     * non-clickable, Add disabled — engage during that first file's blocking copy and probe rather
     * than only once it has already finished.
     */
    private fun publishImporting(progress: ImportProgress) {
        publish(LibraryHomeState.Shelf(lastShelf.entries, importing = progress))
    }

    private fun publishLoading() {
        val home = LibraryHome(
            state = LibraryHomeState.Loading,
            viewMode = lastViewMode,
            appearanceMode = lastAppearanceMode
        )
        mainPost { if (!isDisposed()) onState(home) }
    }

    private fun publish(shelf: LibraryHomeState.Shelf) {
        lastShelf = shelf
        shelfPublished = true

        val home = LibraryHome(shelf, lastThumbnails, lastViewMode, lastAppearanceMode)
        mainPost { if (!isDisposed()) onState(home) }
    }

    private fun isDisposed(): Boolean = synchronized(disposeLock) { disposed }

    companion object {
        const val PROGRESS_WRITE_DELAY_MILLIS = 750L
    }
}
