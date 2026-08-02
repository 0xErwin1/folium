package com.folium.reader.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.folium.reader.R
import com.folium.reader.core.library.LibraryDocumentCandidate
import com.folium.reader.core.library.RecoveryState
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.library.LibraryCopy
import java.util.concurrent.Executors

/** What the reader has to show while, and after, a document is being opened. */
sealed class ReaderScreenState {
    data object Opening : ReaderScreenState()
    data class Reading(val ui: ReaderUiState<BorrowedPage>) : ReaderScreenState()
    data class Unavailable(val recovery: RecoveryState) : ReaderScreenState()
    data class Unreadable(val failure: PdfFailure) : ReaderScreenState()
}

object ReaderHostTestTags {
    const val OPENING = "reader-opening"
    const val FAILURE = "reader-failure"
    const val FAILURE_ACTION = "reader-failure-action"
}

/**
 * Every open and every teardown runs here, one at a time and in order.
 *
 * The rendering engine allows a single document session at a time, so reopening a second document
 * must not begin until the first has genuinely finished closing. Serializing both operations onto
 * one thread makes that ordering structural rather than something the caller has to time, and keeps
 * both off the main thread, where either would block: opening streams and parses a file, and
 * closing waits for every render in flight to finish.
 */
private val readerWork = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "folium-reader-session")
}

/**
 * Owns one document's session for as long as the reader is on screen.
 *
 * A session can finish opening after the reader has already left — the open is not interruptible
 * once it has started — so the session it produces is handed over under a lock that also records
 * whether anyone is still waiting for it. If nobody is, it is closed straight away rather than
 * being published to a composition that no longer exists.
 */
class ReaderHostController(
    private val context: Context,
    private val document: LibraryDocumentCandidate,
    private val onState: (ReaderScreenState) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var session: ReaderSession? = null
    private var disposed = false

    fun start() {
        readerWork.execute {
            val opened = ReaderSession.open(context, document.identity) { ui ->
                onState(ReaderScreenState.Reading(ui))
            }
            publish(opened)
        }
    }

    fun dispose() {
        val abandoned = synchronized(lock) {
            disposed = true
            session.also { session = null }
        }

        abandoned?.close()
        if (abandoned != null) readerWork.execute { abandoned.dispose() }
    }

    fun dispatch(intent: GestureIntent) = session?.presenter?.dispatch(intent) ?: Unit

    fun setViewport(viewport: ReaderViewport?) = session?.presenter?.setViewport(viewport) ?: Unit

    fun pageAspect(pageIndex: Int): Float = session?.pageAspect(pageIndex) ?: 1f

    private fun publish(opened: ReaderSessionResult) {
        if (opened !is ReaderSessionResult.Opened) {
            main.post { if (!isDisposed()) onState(failureState(opened)) }
            return
        }

        val accepted = synchronized(lock) {
            if (disposed) false else { session = opened.session; true }
        }

        if (accepted) {
            main.post { session?.let { onState(ReaderScreenState.Reading(it.presenter.uiState)) } }
        } else {
            // Both halves of teardown keep their threads even for a session nobody ever saw:
            // closing touches presenter state, which is confined to the main thread, and draining
            // blocks, which the main thread cannot afford.
            main.post { opened.session.close(); readerWork.execute { opened.session.dispose() } }
        }
    }

    private fun isDisposed(): Boolean = synchronized(lock) { disposed }

    private fun failureState(opened: ReaderSessionResult): ReaderScreenState = when (opened) {
        is ReaderSessionResult.Unavailable -> ReaderScreenState.Unavailable(opened.recovery)
        is ReaderSessionResult.Unreadable -> ReaderScreenState.Unreadable(opened.failure)
        is ReaderSessionResult.Opened -> error("an opened session is not a failure")
    }
}

/** Opens [document] and reads it, tearing the session down when it leaves the composition. */
@Composable
fun ReaderHost(document: LibraryDocumentCandidate, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var screen by remember(document.identity) { mutableStateOf<ReaderScreenState>(ReaderScreenState.Opening) }

    val controller = remember(document.identity) {
        ReaderHostController(context, document) { screen = it }
    }

    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.dispose() }
    }

    when (val current = screen) {
        is ReaderScreenState.Opening -> ReaderMessage(
            tag = ReaderHostTestTags.OPENING,
            title = stringResource(R.string.reader_opening),
            body = document.displayName,
            onBack = onBack
        )

        is ReaderScreenState.Reading -> ReaderScreen(
            title = document.displayName,
            state = current.ui,
            pageAspect = controller::pageAspect,
            onIntent = controller::dispatch,
            onViewportChanged = controller::setViewport,
            onBack = onBack
        )

        is ReaderScreenState.Unavailable -> ReaderMessage(
            tag = ReaderHostTestTags.FAILURE,
            title = stringResource(LibraryCopy.rootTitle(current.recovery.reason)),
            body = stringResource(LibraryCopy.skipExplanation(current.recovery.reason)),
            onBack = onBack
        )

        is ReaderScreenState.Unreadable -> ReaderMessage(
            tag = ReaderHostTestTags.FAILURE,
            title = stringResource(ReaderCopy.title(current.failure)),
            body = stringResource(ReaderCopy.body(current.failure)),
            onBack = onBack
        )
    }
}

@Composable
private fun ReaderMessage(tag: String, title: String, body: String, onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).padding(24.dp).testTag(tag),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(32.dp))

                Button(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(ReaderHostTestTags.FAILURE_ACTION)
                ) {
                    Text(stringResource(R.string.reader_back))
                }
            }
        }
    }
}
