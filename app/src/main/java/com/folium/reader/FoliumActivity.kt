package com.folium.reader

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.folium.reader.core.library.BookFormat
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.library.LibraryHomeState
import com.folium.reader.library.LibraryController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import com.folium.reader.ui.FoliumWidthClass
import com.folium.reader.library.BookDetailBody
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import com.folium.reader.reader.PdfEngines
import com.folium.reader.library.LibraryPaths
import com.folium.reader.library.BookDetailScreen
import com.folium.reader.library.BookDetailLoader
import com.folium.reader.library.BookDetail
import com.folium.reader.library.LibraryHome
import com.folium.reader.library.LibraryScreen
import com.folium.reader.library.OpenBookRequest
import com.folium.reader.library.PickedSource
import com.folium.reader.reader.ReaderHost
import com.folium.reader.ui.FoliumTheme
import java.io.FileNotFoundException
import java.io.InputStream

private const val STATE_DETAIL_BOOK_ID = "folium.detail-book-id"
private const val STATE_DETAIL_BOOK_FORMAT = "folium.detail-book-format"

/**
 * The book the detail screen is showing and the format its stored copy is in, kept together so the
 * two cannot drift apart: [BookDetailLoader] needs both to resolve the right file, and the format is
 * not something the saved-state restore path can look up on its own — it runs before the shelf has
 * loaded, so there is no [com.folium.reader.core.library.LibraryBook] on hand to read it from.
 */
private data class DetailTarget(val id: BookId, val format: BookFormat)

/**
 * The app's only activity: it owns the app-managed library and hosts both the home screen and the
 * book opened from it.
 *
 * The reader is a screen rather than a second activity because the rendering engine allows one open
 * document at a time. Two activities would overlap by design — the incoming one is created before
 * the outgoing one is destroyed — so opening a second document would race the first one's teardown.
 * With one activity, leaving a book and opening the next are ordered by construction.
 *
 * Nothing here is asynchronous on its own account: every blocking operation belongs to
 * [LibraryController]'s worker, and state arrives back through its main-thread posts.
 */
class FoliumActivity : ComponentActivity() {

    private lateinit var library: LibraryController
    private lateinit var picker: ActivityResultLauncher<Array<String>>

    private var home by mutableStateOf(LibraryHome(LibraryHomeState.Loading))
    private var openBook by mutableStateOf<OpenBookRequest?>(null)
    private var detailTarget by mutableStateOf<DetailTarget?>(null)
    private var detail by mutableStateOf(BookDetail.LOADING)
    private lateinit var details: BookDetailLoader

    /**
     * Enabled only while a book is open, so back leaves the reader for the library there and keeps
     * its ordinary meaning — leaving the app — everywhere else.
     */
    private val leaveBook = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (openBook != null) showBook(null) else showDetail(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        library = LibraryController(filesDir, onState = { home = it })
        details = BookDetailLoader(
            paths = LibraryPaths(filesDir),
            engine = PdfEngines.load(),
            worker = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "folium-detail").apply { isDaemon = true }
            },
            main = Executor { action -> runOnUiThread(action) }
        )
        picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), ::onFilesPicked)

        onBackPressedDispatcher.addCallback(this, leaveBook)

        // After the loader exists: restoring the choice re-reads the document it describes. The
        // shelf has not loaded yet, so the format has to come from saved state rather than a lookup.
        restoreDetailTarget(savedInstanceState)

        setContent {
            FoliumTheme(appearanceMode = home.appearanceMode) {
                val request = openBook
                val detailId = detailTarget?.id
                val entry = detailId?.let { id ->
                    (home.state as? LibraryHomeState.Shelf)?.entries?.firstOrNull { it.book.id == id }
                }
                val wide = LocalConfiguration.current.screenWidthDp.dp >= FoliumWidthClass.EXPANDED_FROM

                if (request == null && entry != null && !wide) {
                    BookDetailScreen(
                        entry = entry,
                        detail = detail,
                        thumbnail = home.thumbnails[entry.book.id],
                        onBack = { showDetail(null) },
                        onOpen = { showDetail(null); requestBook(entry.book.id) },
                        onOpenAt = { page -> openAt(entry.book, page) },
                        onRemove = { showDetail(null); library.remove(entry.book.id) }
                    )
                } else if (request == null) {
                    LibraryScreen(
                        state = home.state,
                        thumbnails = home.thumbnails,
                        viewMode = home.viewMode,
                        appearanceMode = home.appearanceMode,
                        onAddBooks = { picker.launch(BookFormat.entries.map { it.mimeType }.toTypedArray()) },
                        onOpenBook = ::requestBook,
                        onShowDetail = { showDetail(it) },
                        onRemoveBook = library::remove,
                        sidePane = entry?.let { chosen ->
                            {
                                BookDetailBody(
                                    entry = chosen,
                                    detail = detail,
                                    thumbnail = home.thumbnails[chosen.book.id],
                                    onOpen = { requestBook(chosen.book.id) },
                                    onOpenAt = { page -> openAt(chosen.book, page) },
                                    onRemove = { showDetail(null); library.remove(chosen.book.id) }
                                )
                            }
                        },
                        onDismissReport = library::dismissReport,
                        onViewModeChange = library::setViewMode,
                        onAppearanceModeChange = library::setAppearanceMode
                    )
                } else {
                    ReaderHost(
                        request = request,
                        onPageChanged = { page -> library.recordProgress(request.book.id, page, request.book.pageCount) },
                        onBack = { showBook(null) }
                    )
                }
            }
        }
    }

    /**
     * A rotation recreates the activity, and losing the chosen book across one would read as the
     * app forgetting what was on screen — most visibly on a wide layout, where that book is a whole
     * pane rather than a screen that could be reopened.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        detailTarget?.let { target ->
            outState.putString(STATE_DETAIL_BOOK_ID, target.id.value)
            outState.putString(STATE_DETAIL_BOOK_FORMAT, target.format.name)
        }
    }

    override fun onStart() {
        super.onStart()
        library.load()
    }

    /**
     * The last page reached is written here rather than by a background job: a process killed while
     * the app is not visible is the case progress has to survive, and by the time this runs the
     * page is already known.
     */
    override fun onStop() {
        library.flushProgressNow()
        super.onStop()
    }

    override fun onDestroy() {
        library.dispose()
        super.onDestroy()
    }

    /**
     * The detail is reached by the title under a cover, while the cover itself opens the book: the
     * gesture a reader repeats daily costs one touch, and the one they use twice in a book's life
     * is the one that asks.
     */
    /**
     * Reached from the shelf, which already has the row's [com.folium.reader.core.library.LibraryBook]
     * on screen: the format travels with [id] by looking the row back up, rather than by asking the
     * caller to carry it. A row that has disappeared from the shelf (removed elsewhere, or simply not
     * found) shows no detail rather than one for a book that may no longer exist.
     */
    private fun showDetail(id: BookId?) {
        val target = id?.let { bookId ->
            (home.state as? LibraryHomeState.Shelf)?.entries?.firstOrNull { it.book.id == bookId }
                ?.let { entry -> DetailTarget(bookId, entry.book.format) }
        }
        applyDetailTarget(target)
    }

    /**
     * Restores whatever [DetailTarget] the previous instance was showing, from its two saved-state
     * keys rather than a shelf lookup: this runs in [onCreate], before [LibraryController.load] has
     * populated the shelf [showDetail] would otherwise search. A missing or unrecognized format token
     * restores to no detail rather than throwing.
     */
    private fun restoreDetailTarget(savedInstanceState: Bundle?) {
        val id = savedInstanceState?.getString(STATE_DETAIL_BOOK_ID)?.let(::BookId) ?: return
        val format = savedInstanceState.getString(STATE_DETAIL_BOOK_FORMAT)
            ?.let { token -> runCatching { BookFormat.valueOf(token) }.getOrNull() }
            ?: return
        applyDetailTarget(DetailTarget(id, format))
    }

    private fun applyDetailTarget(target: DetailTarget?) {
        detailTarget = target
        detail = BookDetail.LOADING
        leaveBook.isEnabled = target != null || openBook != null
        target?.let { chosen ->
            details.load(chosen.id, chosen.format) { loaded -> if (detailTarget == chosen) detail = loaded }
        }
    }


    /**
     * Opening on a chapter's page, not on the page the reader left.
     *
     * The position has to be written before the open reads it back: both go to the controller's
     * serial worker, so flushing first is what orders them — the same reason leaving the reader
     * flushes before reloading the shelf. Recording alone would leave the write sitting in the
     * coalescing window while the open read the previous page.
     */
    private fun openAt(book: LibraryBook, pageIndex: Int) {
        library.recordProgress(book.id, pageIndex, book.pageCount)
        library.flushProgressNow()
        showDetail(null)
        requestBook(book.id)
    }

    private fun requestBook(id: BookId) {
        library.openBook(id) { request -> if (request != null) showBook(request) }
    }

    /**
     * Leaving the reader (`request == null`) must make the shelf reflect whatever page was reached:
     * [LibraryController.flushProgressNow] and [LibraryController.load] both post to the same serial
     * worker, so flushing first guarantees the load's catalog-progress join reads the write that just
     * landed rather than racing it.
     */
    private fun showBook(request: OpenBookRequest?) {
        if (request == null) {
            library.flushProgressNow()
            library.load()
        }
        openBook = request
        leaveBook.isEnabled = request != null
    }

    /**
     * Reads each picked file's display name and hands the batch to the library.
     *
     * The picker's read grants are task-scoped and are consumed by the import that follows, so the
     * names are resolved here, in the callback that receives them, rather than deferred. Each
     * lookup is a single-row metadata query; the stream itself is only opened later, on the
     * library worker.
     */
    private fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return

        library.import(uris.map { uri -> PickedSource(displayNameOf(uri)) { openStream(uri) } })
    }

    private fun openStream(uri: Uri): InputStream =
        contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())

    /**
     * The provider's own name for the file when it offers one, and the URI's last segment when it
     * does not. A provider is free to omit or mangle the name, and the importer sanitizes whatever
     * comes back, so nothing here rejects a value.
     */
    private fun displayNameOf(uri: Uri): String {
        val queried = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

        return displayName(queried, uri.lastPathSegment, uri.toString())
    }
}

/**
 * Picks the best name to show for a picked file: the provider's own name when it is present and
 * non-blank, the URI's last path segment when it is not, and [fallback] when neither is usable.
 */
internal fun displayName(queried: String?, lastPathSegment: String?, fallback: String): String =
    queried?.takeIf { it.isNotBlank() }
        ?: lastPathSegment?.takeIf { it.isNotBlank() }
        ?: fallback
