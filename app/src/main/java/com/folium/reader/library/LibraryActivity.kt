package com.folium.reader.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import com.folium.reader.R
import com.folium.reader.core.library.LibraryDocumentCandidate
import com.folium.reader.core.library.LibraryLoadResult
import com.folium.reader.core.library.LibraryState
import com.folium.reader.core.library.LibraryStateReducer
import com.folium.reader.saf.SafGrantRepository
import com.folium.reader.saf.SafRootResult
import com.folium.reader.saf.SharedPreferencesSafRootStorage
import com.folium.reader.ui.FoliumTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hosts the library and owns SAF root selection.
 *
 * The persistable read grant returned by `ACTION_OPEN_DOCUMENT_TREE` belongs to the activity that
 * receives the result: once that activity is destroyed the system asynchronously releases it. The
 * grant is therefore taken synchronously in [onRootSelected] and never handed to the composition,
 * a background dispatcher, or a later screen to persist.
 */
class LibraryActivity : ComponentActivity() {

    private lateinit var grants: SafGrantRepository
    private lateinit var session: LibrarySession
    private lateinit var picker: ActivityResultLauncher<Intent>

    private var rootSelectionFailure by mutableStateOf<LibraryState?>(null)
    private var reloads by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        grants = SafGrantRepository(contentResolver, SharedPreferencesSafRootStorage(this))
        session = LibrarySession(grants, SafLibraryRepository(grants))
        picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onRootSelected(result.resultCode, result.data?.data)
        }

        setContent {
            FoliumTheme {
                LibraryScreen(
                    state = libraryState(),
                    onSelectRoot = ::launchPicker,
                    onRetry = ::reload,
                    onOpenDocument = ::openDocument
                )
            }
        }
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
     * Dispatch carries the stable provider identity rather than a transient URI or a list position,
     * so the receiving reader can re-resolve the document across relaunches.
     */
    private fun openDocument(document: LibraryDocumentCandidate) {
        val identity = document.identity
        check(identity.documentId.isNotBlank())

        Toast.makeText(this, getString(R.string.library_opening, document.displayName), Toast.LENGTH_SHORT).show()
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
