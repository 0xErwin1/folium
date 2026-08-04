package com.folium.reader.library

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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

/** The book to open, its stored file and the page to restore, resolved off the main thread. */
data class OpenBookRequest(val book: LibraryBook, val file: File, val initialPage: Int)

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
    private val onState: (LibraryHome) -> Unit,
    private val worker: Executor = documentWork,
    private val mainPost: (() -> Unit) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
    private val delay: (Long, () -> Unit) -> Unit =
        { millis, action -> Handler(Looper.getMainLooper()).postDelayed(action, millis) },
    private val thumbnailDecoder: ThumbnailDecoder = BitmapFactoryThumbnailDecoder(),
    engine: PdfEngine = PdfEngines.load(),
    thumbnailWriter: ThumbnailWriter = BitmapThumbnailWriter(),
    newId: () -> String = { UUID.randomUUID().toString() },
    clock: () -> Long = System::currentTimeMillis
) {
    private val paths = LibraryPaths(filesDir)
    private val catalog = BookCatalogStore(paths)
    private val progress = ProgressStore(paths)
    private val files = BookFiles(paths)
    private val viewModes = ViewModeStore(paths)
    private val appearanceModes = AppearanceModeStore(paths)
    private val importer = BookImporter(paths, catalog, engine, thumbnailWriter, newId, clock)

    private val disposeLock = Any()
    private var disposed = false

    private val thumbnailLock = Any()
    private val thumbnailCache = mutableMapOf<BookId, Bitmap?>()

    private val pendingLock = Any()
    private var pendingProgress: Pair<BookId, Int>? = null
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

    fun import(sources: List<PickedSource>) {
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
            publish(LibraryHomeState.Shelf(entries, report = ImportReport(outcomes)))
        }
    }

    /**
     * Removes a book, catalog row first.
     *
     * The catalog is the library's only source of truth, so a failed catalog rewrite must leave the
     * book fully intact rather than gutted: deleting the files first would republish a listed book
     * whose `document.pdf` is already gone. A failed progress-row removal after a successful catalog
     * removal is ignored by design — the book is no longer listed, the shelf join drops the orphan
     * row, and the next progress write rewrites the file without it.
     */
    fun remove(id: BookId) {
        worker.execute {
            if (!catalog.remove(id)) {
                publish(LibraryHomeState.Shelf(joinedEntries()))
                return@execute
            }

            files.deleteBook(id)
            progress.remove(id)
            synchronized(thumbnailLock) {
                thumbnailCache.remove(id)
                lastThumbnails = thumbnailCache.toMap()
            }
            publish(LibraryHomeState.Shelf(joinedEntries()))
        }
    }

    fun openBook(id: BookId, onOpen: (OpenBookRequest?) -> Unit) {
        worker.execute {
            val book = catalog.read().firstOrNull { it.id == id }
            val request = book?.let {
                val storedPage = progress.read().firstOrNull { record -> record.bookId == id }?.pageIndex ?: 0
                OpenBookRequest(it, files.document(id), ShelfEntry(it, storedPage).pageIndex)
            }
            mainPost { if (!isDisposed()) onOpen(request) }
        }
    }

    fun recordProgress(id: BookId, pageIndex: Int) {
        val shouldSchedule = synchronized(pendingLock) {
            pendingProgress = id to pageIndex
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

        progress.put(pending.first, pending.second)
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
