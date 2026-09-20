package com.folium.reader

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.folium.reader.core.ink.OpenSheet
import com.folium.reader.core.ink.Sheet
import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.ink.SheetListing
import com.folium.reader.core.ink.SheetStore
import com.folium.reader.core.ink.SheetTemplate
import com.folium.reader.core.library.BookFormat
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.library.LibraryHomeState
import com.folium.reader.ink.SheetPane
import com.folium.reader.library.LibraryController
import com.folium.reader.library.SheetOpenRouter
import com.folium.reader.library.documentWork
import com.folium.reader.ui.FoliumWidthClass
import com.folium.reader.library.BookDetailBody
import java.util.UUID
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
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

private const val STATE_DETAIL_BOOK_ID = "folium.detail-book-id"
private const val STATE_DETAIL_BOOK_FORMAT = "folium.detail-book-format"
private const val STATE_TYPOGRAPHY_BOOK_ID = "folium.typography-book-id"
private const val STATE_TYPOGRAPHY_BOOK_FORMAT = "folium.typography-book-format"
private const val STATE_OPEN_BOOK_ID = "folium.open-book-id"
private const val STATE_PENDING_BOOK_ID = "folium.pending-book-id"
private const val STATE_OPEN_SHEET_ID = "folium.open-sheet-id"

/**
 * The book the detail screen is showing and the format its stored copy is in, kept together so the
 * two cannot drift apart: [BookDetailLoader] needs both to resolve the right file, and the format is
 * not something the saved-state restore path can look up on its own — it runs before the shelf has
 * loaded, so there is no [com.folium.reader.core.library.LibraryBook] on hand to read it from.
 */
private data class DetailTarget(val id: BookId, val format: BookFormat)

/**
 * The book the typography sheet is open over, and its format — the same pairing [DetailTarget]
 * carries, and for the same reason. Honoured only once a book reopens with a matching id; a sheet
 * over a book that is not open is not a state the reader can have been in.
 */
internal data class TypographyTarget(val id: BookId, val format: BookFormat)

private data class RetainedActivityState(
    val library: LibraryController,
    val externalIntake: ExternalDocumentIntake,
    val bookRouter: BookOpenRouter,
    val sheets: SheetStore,
    val sheetRouter: SheetOpenRouter,
    val openSheet: OpenSheet?
)

/**
 * Decodes a restored [TypographyTarget] from its two saved-state tokens. A missing id, a missing
 * format, or a format token the running app no longer recognizes all restore to no sheet rather
 * than throwing.
 */
internal fun restoreTypographyTarget(bookId: String?, formatToken: String?): TypographyTarget? {
    val id = bookId?.let(::BookId) ?: return null
    val format = formatToken?.let { token -> runCatching { BookFormat.valueOf(token) }.getOrNull() } ?: return null
    return TypographyTarget(id, format)
}

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
    private lateinit var externalIntake: ExternalDocumentIntake
    private lateinit var bookRouter: BookOpenRouter
    private lateinit var sheets: SheetStore
    private lateinit var sheetRouter: SheetOpenRouter
    private lateinit var picker: ActivityResultLauncher<Array<String>>

    private var home by mutableStateOf(LibraryHome(LibraryHomeState.Loading))
    private var sheetListing by mutableStateOf(SheetListing(emptyList(), emptyList()))
    private var openBook by mutableStateOf<OpenBookRequest?>(null)
    private var openSheetScreen by mutableStateOf<OpenSheet?>(null)
    private var sheetCreationFailed by mutableStateOf(false)
    private var detailTarget by mutableStateOf<DetailTarget?>(null)
    private var detail by mutableStateOf(BookDetail.LOADING)
    private lateinit var details: BookDetailLoader
    private var typographyTarget by mutableStateOf<TypographyTarget?>(null)

    /**
     * Enabled while a sheet or a book is open, so back leaves whichever one is on screen for the
     * library there and keeps its ordinary meaning — leaving the app — everywhere else. The sheet
     * screen takes priority: it is always the topmost one when it is showing.
     */
    private val leaveBook = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                openSheetScreen != null -> closeSheetScreen()
                openBook != null -> showBook(null)
                else -> showDetail(null)
            }
        }
    }

    private fun updateBackEnabled() {
        leaveBook.isEnabled = openSheetScreen != null || openBook != null || detailTarget != null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val retained = lastCustomNonConfigurationInstance as? RetainedActivityState
        if (retained == null) {
            val createdLibrary = LibraryController(filesDir, onState = { home = it })
            library = createdLibrary
            externalIntake = ExternalDocumentIntake { sources, onComplete -> createdLibrary.import(sources, onComplete) }
            bookRouter = BookOpenRouter(createdLibrary::openBook)
            sheets = SheetStore(File(filesDir, "sheets"))
            sheetRouter = SheetOpenRouter(
                openSheet = { id, callback -> openSheetOffMainThread(callback) { sheets.open(id) } },
                createSheet = { sheet, callback -> openSheetOffMainThread(callback) { sheets.create(sheet) } }
            )
        } else {
            library = retained.library
            library.rebind { home = it }
            externalIntake = retained.externalIntake
            bookRouter = retained.bookRouter
            sheets = retained.sheets
            sheetRouter = retained.sheetRouter
            openSheetScreen = retained.openSheet
        }
        bookRouter.rebind(::showBook)
        externalIntake.rebind(::requestBook)
        sheetRouter.rebind(onOpened = ::showSheetOpened, onFailed = { sheetCreationFailed = true })
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
        typographyTarget = restoreTypographyTarget(
            savedInstanceState?.getString(STATE_TYPOGRAPHY_BOOK_ID),
            savedInstanceState?.getString(STATE_TYPOGRAPHY_BOOK_FORMAT)
        )
        val restoredOpenId = savedInstanceState?.getString(STATE_OPEN_BOOK_ID)?.let(::BookId)
        val restoredPendingId = savedInstanceState?.getString(STATE_PENDING_BOOK_ID)?.let(::BookId)
        when {
            retained == null && restoredPendingId != null -> requestBook(restoredPendingId)
            bookRouter.pendingBookId == null && restoredOpenId != null -> requestBook(restoredOpenId)
        }

        // A process death loses whatever writer the previous instance held open, so the sheet is
        // reopened from its id rather than carried across, the same way a restored book is re-fetched
        // rather than kept. A configuration change never reaches this branch: `retained` already
        // carries the live `OpenSheet` across it.
        val restoredSheetId = savedInstanceState?.getString(STATE_OPEN_SHEET_ID)?.let(::SheetId)
        if (retained == null && restoredSheetId != null) sheetRouter.open(restoredSheetId)
        updateBackEnabled()

        setContent {
            FoliumTheme(appearanceMode = home.appearanceMode) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val sheet = openSheetScreen
                    val request = openBook
                    val detailId = detailTarget?.id
                    val entry = detailId?.let { id ->
                        (home.state as? LibraryHomeState.Shelf)?.entries?.firstOrNull { it.book.id == id }
                    }
                    val windowWidthClass = FoliumWidthClass.of(maxWidth)
                    val wide = windowWidthClass.showsTwoPanes

                    if (sheet != null) {
                        SheetPane(
                            openSheet = sheet,
                            onBack = ::closeSheetScreen,
                            onRename = { newTitle -> documentWork.execute { sheet.rename(newTitle) } },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (request == null && entry != null && !wide) {
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
                            onNewSheet = ::requestNewSheet,
                            onOpenBook = ::requestBook,
                            onShowDetail = { showDetail(it) },
                            onRemoveBook = library::remove,
                            selectedBookId = entry?.book?.id,
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
                            onAppearanceModeChange = library::setAppearanceMode,
                            windowWidthClass = windowWidthClass,
                            sheetCreationFailed = sheetCreationFailed,
                            onDismissSheetCreationFailed = { sheetCreationFailed = false },
                            sheets = sheetListing.sheets,
                            unreadableSheetCount = sheetListing.unreadable.size,
                            onSheetOpen = sheetRouter::open
                        )
                    } else {
                        val typographySheetOpen = typographyTarget?.let {
                            it.id == request.book.id && it.format == request.book.format
                        } == true

                        ReaderHost(
                            request = request,
                            onPageChanged = { page -> library.recordProgress(request.book.id, page, request.book.pageCount) },
                            onBack = { showBook(null) },
                            typographySheetOpen = typographySheetOpen,
                            onTypographySheetOpenChange = { open ->
                                typographyTarget = if (open) TypographyTarget(request.book.id, request.book.format) else null
                            },
                            onRepaginated = library::recordProgress,
                            appearanceMode = home.appearanceMode
                        )
                    }
                }
            }
        }

        if (savedInstanceState == null) receiveExternalDocument(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveExternalDocument(intent)
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
        typographyTarget?.let { target ->
            outState.putString(STATE_TYPOGRAPHY_BOOK_ID, target.id.value)
            outState.putString(STATE_TYPOGRAPHY_BOOK_FORMAT, target.format.name)
        }
        openBook?.let { request -> outState.putString(STATE_OPEN_BOOK_ID, request.book.id.value) }
        bookRouter.pendingBookId?.let { id -> outState.putString(STATE_PENDING_BOOK_ID, id.value) }
        openSheetScreen?.let { sheet -> outState.putString(STATE_OPEN_SHEET_ID, sheet.sheet.id.value) }
    }

    override fun onStart() {
        super.onStart()
        refreshLibrary()
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

    override fun onRetainCustomNonConfigurationInstance(): Any =
        RetainedActivityState(library, externalIntake, bookRouter, sheets, sheetRouter, openSheetScreen)

    /**
     * A configuration change keeps [openSheetScreen] alive through [onRetainCustomNonConfigurationInstance]
     * rather than closing it here: the sheet's writer is a live [OpenSheet], and closing it on every
     * rotation only to reopen an identical one on the other side would cost a flush and a re-open for
     * nothing this screen shows.
     */
    override fun onDestroy() {
        if (!isChangingConfigurations) {
            library.dispose()
            sheetRouter.cancel()
            openSheetScreen?.let { sheet -> documentWork.execute(sheet::close) }
        }
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
        updateBackEnabled()
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
        bookRouter.request(id)
    }

    private fun receiveExternalDocument(intent: android.content.Intent) {
        val uri = intent.externalDocumentUri() ?: return
        if (openBook != null) showBook(null)
        val resolver = applicationContext.contentResolver
        externalIntake.import(uri, displayNameOf(uri)) {
            resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        }
    }

    /**
     * Leaving the reader (`request == null`) must make the shelf reflect whatever page was reached:
     * [LibraryController.flushProgressNow] and [LibraryController.load] both post to the same serial
     * worker, so flushing first guarantees the load's catalog-progress join reads the write that just
     * landed rather than racing it.
     */
    private fun showBook(request: OpenBookRequest?) {
        if (request == null) {
            bookRouter.cancel()
            externalIntake.cancel()
            library.flushProgressNow()
            refreshLibrary()
        }
        openBook = request
        updateBackEnabled()
    }

    /**
     * Runs [openOrCreate] on [documentWork], the same worker every other blocking store call in this
     * activity uses, and hands the result back to [callback] on the main thread. A failed open or
     * create — [SheetStore] throwing rather than returning — reports as `null` instead of propagating,
     * so a reader who tapped "New sheet" sees the failure banner rather than a crash.
     */
    private fun openSheetOffMainThread(callback: (OpenSheet?) -> Unit, openOrCreate: () -> OpenSheet) {
        documentWork.execute {
            val result = runCatching(openOrCreate).getOrNull()
            runOnUiThread { callback(result) }
        }
    }

    private fun showSheetOpened(openSheet: OpenSheet) {
        openSheetScreen = openSheet
        sheetCreationFailed = false
        updateBackEnabled()
    }

    /**
     * Leaves the sheet screen. [openSheetScreen] is cleared first, which is what takes [SheetPane] out
     * of composition and runs its own flush-and-close of the drawing surface; only once that has
     * happened is the [OpenSheet] itself closed, on [documentWork] rather than the main thread, since
     * [OpenSheet.close] is blocking I/O. Closing it before [SheetPane] has left composition would race
     * that surface's own close against this one, both touching the same stroke log.
     */
    private fun closeSheetScreen() {
        val closing = openSheetScreen ?: return
        sheetRouter.cancel()
        openSheetScreen = null
        updateBackEnabled()
        documentWork.execute(closing::close)
        refreshLibrary()
    }

    /**
     * Reloads both halves of the shelf: the app-managed book library, and the handwritten sheets
     * [SheetStore] holds on the side. Every occasion the shelf needs to reflect what is on disk —
     * app start, leaving a book, leaving a sheet screen — reloads both together, so the sheets on
     * screen are never a stale reading of a shelf the books half has already moved past.
     */
    private fun refreshLibrary() {
        library.load()
        loadSheets()
    }

    /** Reads [SheetStore.list] off [documentWork] and hands the result back to [sheetListing]. */
    private fun loadSheets() {
        documentWork.execute {
            val listing = sheets.list()
            runOnUiThread { sheetListing = listing }
        }
    }

    /**
     * A blank, standalone sheet with a freshly minted id, opened the moment [SheetStore.create]
     * returns it. The id is minted here rather than by the store, so [SheetOpenRouter] can guard a
     * double tap on "New sheet" before the store is ever called, the same way it guards a double tap
     * on an existing sheet's row.
     */
    private fun requestNewSheet() {
        val now = System.currentTimeMillis()
        val sheet = Sheet(
            id = SheetId(UUID.randomUUID().toString()),
            title = getString(R.string.library_new_sheet_default_title),
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            template = SheetTemplate.BLANK,
            anchor = null
        )
        sheetRouter.create(sheet)
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
