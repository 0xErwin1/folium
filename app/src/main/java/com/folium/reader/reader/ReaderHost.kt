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
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.text.TextPage
import com.folium.reader.library.OpenBookRequest
import com.folium.reader.library.documentWork
import java.util.concurrent.Executor

/** What the reader has to show while, and after, a document is being opened. */
sealed class ReaderScreenState {
    data object Opening : ReaderScreenState()
    data class Reading(
        val ui: ReaderUiState<BorrowedPage>,
        val text: ReaderTextState = ReaderTextState.Loading(ui.state.currentPage)
    ) : ReaderScreenState()
    data object Missing : ReaderScreenState()
    data class Unreadable(val failure: PdfFailure) : ReaderScreenState()
}

sealed class ReaderTextState {
    abstract val pageIndex: Int

    data class Loading(override val pageIndex: Int) : ReaderTextState()
    data class Loaded(override val pageIndex: Int, val page: TextPage) : ReaderTextState()
    data class Failed(override val pageIndex: Int) : ReaderTextState()
}

internal fun ReaderTextState.selectablePage(currentPage: Int): TextPage? =
    (this as? ReaderTextState.Loaded)?.takeIf { it.pageIndex == currentPage }?.page

object ReaderHostTestTags {
    const val OPENING = "reader-opening"
    const val FAILURE = "reader-failure"
    const val FAILURE_ACTION = "reader-failure-action"
}

/**
 * Owns one document's session for as long as the reader is on screen.
 *
 * Every open and every teardown runs on [documentWork], the same app-wide worker
 * [com.folium.reader.library.LibraryController] uses for import and library file I/O (design
 * decision D-T1). The rendering engine allows a single document session at a time, so reopening a
 * second document must not begin until the first has genuinely finished closing, and an import
 * batch must not overlap a live reader session; serializing every such operation onto one thread
 * makes both orderings structural rather than something a caller has to time.
 *
 * A session can finish opening after the reader has already left — the open is not interruptible
 * once it has started — so the session it produces is handed over under a lock that also records
 * whether anyone is still waiting for it. If nobody is, it is closed straight away rather than
 * being published to a composition that no longer exists.
 */
class ReaderHostController(
    private val context: Context,
    private val request: OpenBookRequest,
    private val onPageChanged: (Int) -> Unit,
    private val onState: (ReaderScreenState) -> Unit,
    private val worker: Executor = documentWork,
    private val mainPost: (() -> Unit) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
    private val openSession: (
        Context,
        OpenBookRequest,
        (ReaderUiState<BorrowedPage>) -> Unit
    ) -> ReaderSessionResult = { ctx, req, onChanged -> ReaderSession.open(ctx, req.file, req.book.id, req.initialPage, onChanged) }
) {
    private val lock = Any()

    @Volatile private var session: ReaderSession? = null
    private var disposed = false
    private var latestUi: ReaderUiState<BorrowedPage>? = null
    private var textPageIndex = -1
    private var textState: ReaderTextState? = null

    /** Seeded with the restored page so the initial state — already at that page — is not reported as a change. */
    private var lastReportedPage: Int = request.initialPage

    fun start() {
        worker.execute {
            val opened = openSession(context, request) { ui ->
                publishReading(ui)
            }
            publish(opened)
        }
    }

    fun dispose() {
        val abandoned = synchronized(lock) {
            disposed = true
            session.also { session = null }
        }

        abandoned?.let { session ->
            closeThenScheduleDispose(session::close) { worker.execute { session.dispose() } }
        }
    }

    fun dispatch(intent: GestureIntent) = session?.presenter?.dispatch(intent) ?: Unit

    fun setViewport(viewport: ReaderViewport?) = session?.presenter?.setViewport(viewport) ?: Unit

    fun pageAspect(pageIndex: Int): Float = session?.pageAspect(pageIndex) ?: 1f

    /** The open document's table of contents, or empty before it has opened or if it has none. */
    fun outline(): List<OutlineEntry> = session?.outline ?: emptyList()

    private fun reportPage(pageIndex: Int) {
        if (pageIndex == lastReportedPage) return
        lastReportedPage = pageIndex
        onPageChanged(pageIndex)
    }

    private fun publish(opened: ReaderSessionResult) {
        if (opened !is ReaderSessionResult.Opened) {
            mainPost { if (!isDisposed()) onState(failureState(opened)) }
            return
        }

        val accepted = synchronized(lock) {
            if (disposed) false else { session = opened.session; true }
        }

        if (accepted) {
            mainPost {
                textPageIndex = -1
                session?.let { publishReading(it.presenter.uiState) }
            }
        } else {
            // Both halves of teardown keep their threads even for a session nobody ever saw:
            // closing touches presenter state, which is confined to the main thread, and draining
            // blocks, which the main thread cannot afford.
            mainPost {
                closeThenScheduleDispose(opened.session::close) {
                    worker.execute { opened.session.dispose() }
                }
            }
        }
    }

    private fun isDisposed(): Boolean = synchronized(lock) { disposed }

    private fun publishReading(ui: ReaderUiState<BorrowedPage>) {
        if (isDisposed()) return
        latestUi = ui
        reportPage(ui.state.currentPage)

        if (textPageIndex != ui.state.currentPage) {
            textPageIndex = ui.state.currentPage
            textState = ReaderTextState.Loading(ui.state.currentPage)
            onState(ReaderScreenState.Reading(ui, requireNotNull(textState)))
            session?.loadTextPage(ui.state.currentPage) { result ->
                if (isDisposed() || textPageIndex != ui.state.currentPage) return@loadTextPage
                textState = when (result) {
                    is TextPageLoadResult.Loaded -> ReaderTextState.Loaded(ui.state.currentPage, result.page)
                    TextPageLoadResult.Failed -> ReaderTextState.Failed(ui.state.currentPage)
                }
                latestUi?.let { current ->
                    if (current.state.currentPage == textPageIndex) {
                        onState(ReaderScreenState.Reading(current, requireNotNull(textState)))
                    }
                }
            }
        } else {
            val currentText = textState?.takeIf { it.pageIndex == ui.state.currentPage }
                ?: ReaderTextState.Loading(ui.state.currentPage).also { textState = it }
            onState(ReaderScreenState.Reading(ui, currentText))
        }
    }

    private fun failureState(opened: ReaderSessionResult): ReaderScreenState = when (opened) {
        is ReaderSessionResult.Missing -> ReaderScreenState.Missing
        is ReaderSessionResult.Unreadable -> ReaderScreenState.Unreadable(opened.failure)
        is ReaderSessionResult.Opened -> error("an opened session is not a failure")
    }
}

/**
 * Opens [request]'s stored file and reads it, tearing the session down when it leaves the
 * composition. [onPageChanged] is called on the main thread whenever the current page differs from
 * the last one reported, which is how the activity keeps stored progress in step with reading.
 */
@Composable
fun ReaderHost(request: OpenBookRequest, onPageChanged: (Int) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var screen by remember(request.book.id) { mutableStateOf<ReaderScreenState>(ReaderScreenState.Opening) }

    val controller = remember(request.book.id) {
        ReaderHostController(context, request, onPageChanged, onState = { screen = it })
    }

    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.dispose() }
    }

    // A bound method reference is a fresh, non-equal instance every time it is written, so handing
    // one straight to a composable defeats that composable's skipping on every recomposition.
    val pageAspect = remember(controller) { controller::pageAspect }
    val onIntent = remember(controller) { controller::dispatch }
    val onViewportChanged = remember(controller) { controller::setViewport }

    when (val current = screen) {
        is ReaderScreenState.Opening -> ReaderMessage(
            tag = ReaderHostTestTags.OPENING,
            title = stringResource(R.string.reader_opening),
            body = request.book.title,
            onBack = onBack
        )

        is ReaderScreenState.Reading -> ReaderScreen(
            title = request.book.title,
            state = current.ui,
            pageAspect = pageAspect,
            onIntent = onIntent,
            onViewportChanged = onViewportChanged,
            onBack = onBack,
            outline = controller.outline(),
            textPage = current.text.selectablePage(current.ui.state.currentPage)
        )

        is ReaderScreenState.Missing -> ReaderMessage(
            tag = ReaderHostTestTags.FAILURE,
            title = stringResource(R.string.reader_missing_title),
            body = stringResource(R.string.reader_missing_body),
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
