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
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryHomeState
import com.folium.reader.library.LibraryController
import com.folium.reader.library.LibraryScreen
import com.folium.reader.library.OpenBookRequest
import com.folium.reader.library.PickedSource
import com.folium.reader.reader.ReaderHost
import com.folium.reader.ui.FoliumTheme
import java.io.FileNotFoundException
import java.io.InputStream

private const val PDF_MIME_TYPE = "application/pdf"

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

    private var home by mutableStateOf<LibraryHomeState>(LibraryHomeState.Loading)
    private var openBook by mutableStateOf<OpenBookRequest?>(null)

    /**
     * Enabled only while a book is open, so back leaves the reader for the library there and keeps
     * its ordinary meaning — leaving the app — everywhere else.
     */
    private val leaveBook = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showBook(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        library = LibraryController(filesDir, onState = { home = it })
        picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), ::onFilesPicked)

        onBackPressedDispatcher.addCallback(this, leaveBook)

        setContent {
            FoliumTheme {
                val request = openBook
                if (request == null) {
                    LibraryScreen(
                        state = home,
                        thumbnailFor = library::thumbnail,
                        onAddBooks = { picker.launch(arrayOf(PDF_MIME_TYPE)) },
                        onOpenBook = ::requestBook,
                        onRemoveBook = library::remove,
                        onDismissReport = library::dismissReport
                    )
                } else {
                    ReaderHost(
                        request = request,
                        onPageChanged = { page -> library.recordProgress(request.book.id, page) },
                        onBack = { showBook(null) }
                    )
                }
            }
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

    private fun requestBook(id: BookId) {
        library.openBook(id) { request -> if (request != null) showBook(request) }
    }

    private fun showBook(request: OpenBookRequest?) {
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
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()

        return queried ?: uri.lastPathSegment ?: uri.toString()
    }
}
