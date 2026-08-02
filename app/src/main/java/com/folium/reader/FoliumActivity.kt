package com.folium.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.folium.reader.core.library.LibraryDocumentCandidate
import com.folium.reader.core.library.LibraryLoadResult
import com.folium.reader.core.library.LibraryState
import com.folium.reader.core.library.LibraryStateReducer
import com.folium.reader.library.LibraryScreen
import com.folium.reader.library.LibrarySession
import com.folium.reader.library.SafLibraryRepository
import com.folium.reader.reader.ReaderHost
import com.folium.reader.saf.SafGrantRepository
import com.folium.reader.saf.SafRootResult
import com.folium.reader.saf.SharedPreferencesSafRootStorage
import com.folium.reader.ui.FoliumTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's only activity: it owns SAF root selection and hosts both the library and the document
 * the reader opens from it.
 *
 * The persistable read grant returned by `ACTION_OPEN_DOCUMENT_TREE` belongs to the activity that
 * receives the result: once that activity is destroyed the system asynchronously releases it. The
 * grant is therefore taken synchronously in [onRootSelected] and never handed to the composition,
 * a background dispatcher, or a later screen to persist.
 *
 * The reader is a screen rather than a second activity because the rendering engine allows one open
 * document at a time. Two activities would overlap by design — the incoming one is created before
 * the outgoing one is destroyed — so opening a second document would race the first one's teardown.
 * With one activity, leaving a document and opening the next are ordered by construction.
 */
class FoliumActivity : ComponentActivity() {

    private lateinit var grants: SafGrantRepository
    private lateinit var session: LibrarySession
    private lateinit var picker: ActivityResultLauncher<Intent>

    private var rootSelectionFailure by mutableStateOf<LibraryState?>(null)
    private var openDocument by mutableStateOf<LibraryDocumentCandidate?>(null)
    private var reloads by mutableIntStateOf(0)

    /**
     * Enabled only while a document is open, so back leaves the reader for the library there and
     * keeps its ordinary meaning — leaving the app — everywhere else.
     */
    private val leaveDocument = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showDocument(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        grants = SafGrantRepository(contentResolver, SharedPreferencesSafRootStorage(this))
        session = LibrarySession(grants, SafLibraryRepository(grants))
        picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onRootSelected(result.resultCode, result.data?.data)
        }

        onBackPressedDispatcher.addCallback(this, leaveDocument)

        setContent {
            FoliumTheme {
                val document = openDocument
                if (document == null) {
                    LibraryScreen(
                        state = libraryState(),
                        onSelectRoot = ::launchPicker,
                        onRetry = ::reload,
                        onOpenDocument = ::showDocument
                    )
                } else {
                    ReaderHost(document = document, onBack = { showDocument(null) })
                }
            }
        }
    }

    private fun showDocument(document: LibraryDocumentCandidate?) {
        openDocument = document
        leaveDocument.isEnabled = document != null
    }

    private fun onRootSelected(resultCode: Int, treeUri: Uri?) {
        if (resultCode != Activity.RESULT_OK || treeUri == null) return

        when (val bound = grants.bind(treeUri)) {
            is SafRootResult.Ready -> reload()

            is SafRootResult.Unavailable ->
                rootSelectionFailure = LibraryStateReducer.reduce(LibraryLoadResult.Unavailable(bound.failure.recovery))
        }
    }

    private fun launchPicker() {
        rootSelectionFailure = null
        picker.launch(grants.selectionIntent())
    }

    private fun reload() {
        rootSelectionFailure = null
        reloads++
    }

    /**
     * A root that failed while being selected outranks the last loaded result: the reader just
     * acted, and the outcome of that action is what they need to see.
     */
    @Composable
    private fun libraryState(): LibraryState {
        var loaded by remember { mutableStateOf<LibraryState>(LibraryState.Loading) }

        LaunchedEffect(reloads) {
            loaded = LibraryState.Loading
            loaded = withContext(Dispatchers.IO) { session.load() }
        }

        return rootSelectionFailure ?: loaded
    }
}
