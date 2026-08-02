package com.folium.reader.library

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.ImportOutcome
import com.folium.reader.core.library.ImportProgress
import com.folium.reader.core.library.ImportReport
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.library.LibraryHomeState
import com.folium.reader.core.library.LibraryShelf
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
 * Owns the app-managed library for the activity's whole lifetime: created in `onCreate`, disposed
 * in `onDestroy`. Every load, import, removal and progress write runs on [worker]; state reaches
 * [onState] through [mainPost], mirroring `ReaderHostController`'s own worker-plus-main-post shape.
 *
 * [recordProgress] coalesces a burst of page changes into a single write: the pending page is
 * stored immediately, but the write itself is scheduled through [delay] and only the last page
 * before the delay elapses is ever persisted. [flushProgressNow] runs the same drain with no delay,
 * so posting it before a reader session's teardown guarantees the write lands first.
 */
class LibraryController(
    filesDir: File,
    private val onState: (LibraryHomeState) -> Unit,
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
    private val importer = BookImporter(paths, catalog, engine, thumbnailWriter, newId, clock)

    private val disposeLock = Any()
    private var disposed = false

    private val thumbnailLock = Any()
    private val thumbnailCache = mutableMapOf<BookId, Bitmap?>()

    private val pendingLock = Any()
    private var pendingProgress: Pair<BookId, Int>? = null
    private var progressFlushScheduled = false

    @Volatile private var lastShelf = LibraryHomeState.Shelf(emptyList())

    fun load() {
        worker.execute {
            val entries = joinedEntries()
            decodeThumbnails(entries)
            publish(LibraryHomeState.Shelf(entries))
        }
    }

    /** The row thumbnail decoded during the most recent [load] or [import], or `null` if there is none. */
    fun thumbnail(id: BookId): Bitmap? = synchronized(thumbnailLock) { thumbnailCache[id] }

    fun import(sources: List<PickedSource>) {
        if (sources.isEmpty()) return

        worker.execute {
            importer.sweepStaging()
            val total = sources.size
            val outcomes = ArrayList<ImportOutcome>(total)

            sources.forEachIndexed { index, source ->
                outcomes += importer.import(source)
                publish(LibraryHomeState.Shelf(lastShelf.entries, importing = ImportProgress(index + 1, total)))
            }

            val entries = joinedEntries()
            decodeThumbnails(entries)
            publish(LibraryHomeState.Shelf(entries, report = ImportReport(outcomes)))
        }
    }

    fun remove(id: BookId) {
        worker.execute {
            files.deleteBook(id)
            catalog.remove(id)
            progress.remove(id)
            synchronized(thumbnailLock) { thumbnailCache.remove(id) }
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

    private fun decodeThumbnails(entries: List<ShelfEntry>) {
        synchronized(thumbnailLock) {
            entries.forEach { entry ->
                if (entry.book.id !in thumbnailCache) {
                    thumbnailCache[entry.book.id] = thumbnailDecoder.decode(files.thumbnail(entry.book.id))
                }
            }
        }
    }

    private fun publish(shelf: LibraryHomeState.Shelf) {
        lastShelf = shelf
        mainPost { if (!isDisposed()) onState(shelf) }
    }

    private fun isDisposed(): Boolean = synchronized(disposeLock) { disposed }

    companion object {
        const val PROGRESS_WRITE_DELAY_MILLIS = 750L
    }
}
