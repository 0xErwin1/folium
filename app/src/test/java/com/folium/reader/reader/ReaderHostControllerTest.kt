package com.folium.reader.reader

import android.content.Context
import android.content.ContextWrapper
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.pdf.HorizontalViewportState
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.index.TextPageSearchHit
import com.folium.reader.core.text.TextSearchMode
import com.folium.reader.core.text.TextSearchSpec
import com.folium.reader.core.ocr.OcrCancellationReason
import com.folium.reader.core.ocr.OcrFailureMetadata
import com.folium.reader.core.ocr.OcrPageState
import com.folium.reader.core.ocr.OcrPageStatus
import com.folium.reader.library.OpenBookRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

private class DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

private fun request(initialPage: Int = 0) = OpenBookRequest(
    book = LibraryBook(BookId("book-1"), "Title", pageCount = 1000, addedAtMillis = 0L),
    file = File("/does/not/matter.pdf"),
    initialPage = initialPage
)

private fun readingState(pageCount: Int, currentPage: Int) =
    ReaderUiState<BorrowedPage>(HorizontalViewportState.initial(pageCount, currentPage))

/**
 * Exercises [ReaderHostController] with [ReaderHostController]'s own worker, main-post and
 * session-opening functions replaced by direct-call fakes, since the real path opens a
 * [ReaderSession] against an Android [Context] this host process has no way to provide.
 */
class ReaderHostControllerTest {

    /**
     * Never dereferenced: every seam these tests inject (worker, mainPost, openSession) replaces the
     * exact code path that would otherwise call into it, so a non-functional [ContextWrapper] is
     * enough — no Android framework behavior is genuinely exercised on the host.
     */
    private val context: Context = ContextWrapper(null)

    private data class ScheduledSearch(
        val delayMillis: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false
    )

    @Test fun `nonblank search and option changes debounce for 250ms while clear is immediate`() {
        val scheduled = mutableListOf<ScheduledSearch>()
        val controller = ReaderHostController(
            context, request(), {}, {}, worker = DirectExecutor(), mainPost = { it() },
            scheduleSearch = { delay, action ->
                ScheduledSearch(delay, action).also(scheduled::add).let { item ->
                    { item.cancelled = true }
                }
            },
            openSession = { _, _, _ -> ReaderSessionResult.Missing }
        )

        controller.search(TextSearchSpec("word"))
        controller.search(TextSearchSpec("word", TextSearchMode.REGEX))
        assertEquals(listOf(250L, 250L), scheduled.map { it.delayMillis })
        assertTrue(scheduled.first().cancelled)

        controller.search(TextSearchSpec(""))
        assertTrue(scheduled.last().cancelled)
        assertEquals(2, scheduled.size)
    }

    @Test fun `clear and retype identical query creates a fresh scheduled generation`() {
        val scheduled = mutableListOf<ScheduledSearch>()
        val controller = ReaderHostController(
            context, request(), {}, {}, worker = DirectExecutor(), mainPost = { it() },
            scheduleSearch = { delay, action ->
                ScheduledSearch(delay, action).also(scheduled::add).let { item ->
                    { item.cancelled = true }
                }
            },
            openSession = { _, _, _ -> ReaderSessionResult.Missing }
        )

        controller.search(TextSearchSpec("same"))
        controller.search(TextSearchSpec(""))
        controller.search(TextSearchSpec("same"))

        assertEquals(2, scheduled.size)
        assertTrue(scheduled.first().cancelled)
        assertFalse(scheduled.last().cancelled)
    }

    @Test fun `search publishes debounce then query and only the current callback clears pending`() {
        val scheduled = mutableListOf<ScheduledSearch>()
        val states = mutableListOf<ReaderScreenState>()
        lateinit var onChanged: (ReaderUiState<BorrowedPage>) -> Unit
        val controller = ReaderHostController(
            context = context,
            request = request(),
            onPageChanged = {},
            onState = { states += it },
            worker = DirectExecutor(),
            mainPost = { it() },
            scheduleSearch = { delay, action ->
                ScheduledSearch(delay, action).also(scheduled::add).let { item ->
                    { item.cancelled = true }
                }
            },
            openSession = { _, _, changed -> onChanged = changed; ReaderSessionResult.Missing }
        )
        controller.start()
        onChanged(readingState(pageCount = 10, currentPage = 0))
        val first = TextSearchSpec("first")
        val second = TextSearchSpec("second")

        controller.search(first)
        assertEquals(ReaderSearchPending.DEBOUNCE, states.lastReadingSearch().pending)
        scheduled.single().action()
        assertEquals(ReaderSearchPending.QUERY, states.lastReadingSearch().pending)

        controller.search(second)
        assertEquals(ReaderSearchPending.DEBOUNCE, states.lastReadingSearch().pending)
        scheduled.first().action()
        assertEquals(second, states.lastReadingSearch().spec)
        assertEquals(ReaderSearchPending.DEBOUNCE, states.lastReadingSearch().pending)
        controller.publishSearch(1L, searchProgress(first, indexed = 1, running = true))
        assertEquals(second, states.lastReadingSearch().spec)
        assertEquals(ReaderSearchPending.DEBOUNCE, states.lastReadingSearch().pending)

        scheduled.last().action()
        assertEquals(ReaderSearchPending.QUERY, states.lastReadingSearch().pending)
        controller.publishSearch(2L, searchProgress(second, indexed = 2, running = true))
        assertEquals(second, states.lastReadingSearch().spec)
        assertEquals(null, states.lastReadingSearch().pending)
        assertEquals(2, states.lastReadingSearch().coverage.indexedPages)

        controller.search(TextSearchSpec(""))
        assertEquals(null, (states.last() as ReaderScreenState.Reading).search)
    }

    @Test fun `a page change is forwarded once, and repeating the same page is not`() {
        var reportedPages = mutableListOf<Int>()
        lateinit var onChanged: (ReaderUiState<BorrowedPage>) -> Unit

        val controller = ReaderHostController(
            context = context,
            request = request(initialPage = 5),
            onPageChanged = { page -> reportedPages += page },
            onState = {},
            worker = DirectExecutor(),
            mainPost = { it() },
            openSession = { _, _, changed -> onChanged = changed; ReaderSessionResult.Missing }
        )

        controller.start()

        onChanged(readingState(pageCount = 1000, currentPage = 5))
        onChanged(readingState(pageCount = 1000, currentPage = 5))
        onChanged(readingState(pageCount = 1000, currentPage = 137))
        onChanged(readingState(pageCount = 1000, currentPage = 137))
        onChanged(readingState(pageCount = 1000, currentPage = 138))

        assertEquals(listOf(137, 138), reportedPages)
    }

    @Test fun `every page change also republishes the reading state`() {
        val states = mutableListOf<ReaderScreenState>()
        lateinit var onChanged: (ReaderUiState<BorrowedPage>) -> Unit

        val controller = ReaderHostController(
            context = context,
            request = request(),
            onPageChanged = {},
            onState = { states += it },
            worker = DirectExecutor(),
            mainPost = { it() },
            openSession = { _, _, changed -> onChanged = changed; ReaderSessionResult.Missing }
        )

        controller.start()
        onChanged(readingState(pageCount = 10, currentPage = 3))

        val reading = states.last()
        assertTrue(reading is ReaderScreenState.Reading)
        assertEquals(3, (reading as ReaderScreenState.Reading).ui.state.currentPage)
    }

    @Test fun `a missing file surfaces as the Missing screen state`() {
        val states = mutableListOf<ReaderScreenState>()

        val controller = ReaderHostController(
            context = context,
            request = request(),
            onPageChanged = {},
            onState = { states += it },
            worker = DirectExecutor(),
            mainPost = { it() },
            openSession = { _, _, _ -> ReaderSessionResult.Missing }
        )

        controller.start()

        assertEquals(listOf(ReaderScreenState.Missing), states)
    }

    @Test fun `an unreadable file surfaces its typed failure`() {
        val states = mutableListOf<ReaderScreenState>()
        val failure = PdfFailure.Corrupt

        val controller = ReaderHostController(
            context = context,
            request = request(),
            onPageChanged = {},
            onState = { states += it },
            worker = DirectExecutor(),
            mainPost = { it() },
            openSession = { _, _, _ -> ReaderSessionResult.Unreadable(failure) }
        )

        controller.start()

        assertEquals(listOf(ReaderScreenState.Unreadable(failure)), states)
    }

    @Test fun `disposing before a delayed open completes suppresses the late state`() {
        val states = mutableListOf<ReaderScreenState>()
        var openInvoked: (() -> ReaderSessionResult)? = null

        val controller = ReaderHostController(
            context = context,
            request = request(),
            onPageChanged = {},
            onState = { states += it },
            worker = Executor { command -> openInvoked = { command.run(); ReaderSessionResult.Missing } },
            mainPost = { it() },
            openSession = { _, _, _ -> ReaderSessionResult.Missing }
        )

        controller.start()
        controller.dispose()
        openInvoked?.invoke()

        assertTrue("no state must reach a disposed controller's caller", states.isEmpty())
    }

    @Test fun `loading failure and stale loaded text never expose a selectable page`() {
        val page = TextPage(emptyList(), TextSource.NATIVE_PDF)

        assertEquals(null, ReaderTextState.Loading(3).selectablePage(currentPage = 3))
        assertEquals(null, ReaderTextState.Failed(3).selectablePage(currentPage = 3))
        assertEquals(null, ReaderTextState.Loaded(2, page).selectablePage(currentPage = 3))
        assertEquals(page, ReaderTextState.Loaded(3, page).selectablePage(currentPage = 3))
    }

    @Test fun `page OCR feedback exposes only contractually valid retry actions`() {
        val retryableFailure = ReaderOcrState(
            0,
            OcrPageStatus(OcrPageState.FAILED, 2, failure = OcrFailureMetadata("recognition", true))
        )
        val terminalFailure = ReaderOcrState(
            0,
            OcrPageStatus(OcrPageState.FAILED, 2, failure = OcrFailureMetadata("data", false))
        )
        val userCancelled = ReaderOcrState(
            0,
            OcrPageStatus(OcrPageState.CANCELLED, 2, cancellationReason = OcrCancellationReason.USER)
        )
        val nativeSelected = ReaderOcrState(
            0,
            OcrPageStatus(OcrPageState.CANCELLED, 2, cancellationReason = OcrCancellationReason.NATIVE_TEXT)
        )

        assertTrue(retryableFailure.visible)
        assertTrue(retryableFailure.retryAvailable)
        assertTrue(terminalFailure.visible)
        assertFalse(terminalFailure.retryAvailable)
        assertTrue(userCancelled.visible)
        assertTrue(userCancelled.retryAvailable)
        assertFalse(nativeSelected.visible)
        assertFalse(nativeSelected.retryAvailable)
        assertTrue(ReaderOcrState(0, OcrPageStatus(OcrPageState.STALE, 3)).visible)
        assertFalse(ReaderOcrState(0, OcrPageStatus(OcrPageState.COMPLETED, 3)).visible)
    }

    @Test fun `late OCR status cannot regress the active generation or attempt`() {
        val running = ReaderOcrState(0, OcrPageStatus(OcrPageState.RUNNING, 4))
        val completed = ReaderOcrState(0, OcrPageStatus(OcrPageState.COMPLETED, 4))

        assertFalse(running.accepts(OcrPageStatus(OcrPageState.QUEUED, 4)))
        assertTrue(running.accepts(OcrPageStatus(OcrPageState.COMPLETED, 4)))
        assertFalse(completed.accepts(OcrPageStatus(OcrPageState.RUNNING, 4)))
        assertFalse(completed.accepts(OcrPageStatus(OcrPageState.COMPLETED, 3)))
        assertTrue(completed.accepts(OcrPageStatus(OcrPageState.QUEUED, 5)))
    }

    @Test fun `invalidated OCR page drops only its derived hits and active identity`() {
        val native = searchHit(page = 0, occurrence = 0)
        val ocr = searchHit(page = 2, occurrence = 0, source = TextSource.OCR)
        val otherOcr = searchHit(page = 3, occurrence = 0, source = TextSource.OCR)
        val merged = ReaderSearchState("term").merge(
            progress(listOf(native, ocr, otherOcr), indexed = 3)
        )
        val state = merged.copy(activeIdentity = merged.matches.single { it.pageIndex == 2 }.identity)

        val invalidated = state.withoutOcrPage(2)

        assertEquals(listOf(0, 3), invalidated.matches.map { it.pageIndex })
        assertEquals(listOf(TextSource.NATIVE_PDF, TextSource.OCR),
            invalidated.matches.map { it.identity.source })
        assertEquals(invalidated.matches.first().identity, invalidated.activeIdentity)
    }

    @Test fun `progress adding earlier results preserves the active occurrence identity and order`() {
        val active = searchHit(page = 4, occurrence = 0)
        val initial = ReaderSearchState("term").merge(progress(listOf(active), indexed = 1))
        val selected = initial.copy(activeIdentity = initial.matches.single().identity)
        val earlier = searchHit(page = 1, occurrence = 0)

        val merged = selected.merge(progress(listOf(earlier, active), indexed = 2))

        assertEquals(listOf(1, 4), merged.matches.map { it.pageIndex })
        assertEquals(1, merged.activeIndex)
        assertEquals(4, merged.activeMatch?.pageIndex)
    }

    @Test fun `terminal search state rejects stale running progress for the same spec`() {
        val terminal = ReaderSearchState(
            TextSearchSpec("term"),
            coverage = ReaderSearchCoverage(10, 0, 10, running = false)
        )

        assertEquals(terminal, terminal.merge(progress(listOf(searchHit(3, 0)), indexed = 3)))
    }

    @Test fun `paused search rejects late running coverage and resume accepts a newer revision`() {
        val paused = ReaderSearchState(
            TextSearchSpec("term"),
            matches = listOf(ReaderSearchState("term").merge(
                progress(listOf(searchHit(3, 0)), indexed = 3)
            ).matches.single()),
            coverage = ReaderSearchCoverage(3, 0, 10, running = false, revision = 8),
            ocrPlan = searchOcrState(generation = 2, revision = 4, paused = true)
        )

        val late = paused.merge(TextSearchProgress(
            "term", emptyList(), 2, 0, 10, running = true,
            coverageRevision = 8, spec = TextSearchSpec("term")
        ))
        assertEquals(paused, late)

        val resuming = paused.copy(
            ocrPlan = searchOcrState(generation = 3, revision = 5, searchActive = true)
        )
        val resumed = resuming.merge(TextSearchProgress(
            "term", listOf(searchHit(3, 0), searchHit(6, 0)), 4, 0, 10, running = true,
            coverageRevision = 9, spec = TextSearchSpec("term")
        ))
        assertTrue(resumed.coverage.running)
        assertTrue(resumed.ocrPlan?.searchActive == true)
        assertEquals(listOf(3, 6), resumed.matches.map { it.pageIndex })
    }

    @Test fun `search OCR state rejects late generations and revisions`() {
        val current = searchOcrState(
            generation = 4,
            revision = 8,
            searchActive = false,
            paused = true
        )

        assertFalse(current.accepts(searchOcrState(3, 99, searchActive = true, running = true)))
        assertFalse(current.accepts(searchOcrState(4, 8, searchActive = true, running = true)))
        assertTrue(current.accepts(searchOcrState(4, 9, searchActive = true, queued = true)))
        assertTrue(current.accepts(searchOcrState(5, 1, searchActive = true)))
    }

    @Test fun `initial inactive OCR state is authoritative but not resumable`() {
        val initial = searchOcrState(
            generation = 0,
            revision = 0,
            searchActive = false
        )

        assertFalse(initial.canPause)
        assertFalse(initial.canResume)
        assertTrue(initial.accepts(searchOcrState(1, 1, searchActive = true)))
    }

    private fun searchOcrState(
        generation: Long,
        revision: Long,
        searchActive: Boolean = false,
        running: Boolean = false,
        queued: Boolean = false,
        plannable: Boolean = false,
        paused: Boolean = false,
        draining: Boolean = false
    ) = SearchOcrPlanState(
        generation,
        revision,
        searchActive,
        running,
        queued,
        plannable,
        paused,
        draining
    )

    @Test fun `search navigation clamps at ends and returns the selected page`() {
        val hits = listOf(searchHit(1, 0), searchHit(4, 0))
        val first = ReaderSearchState("term").merge(progress(hits, indexed = 2))
        assertEquals(first to null, first.moveActiveBy(-1))

        val (second, nextPage) = first.moveActiveBy(1)
        assertEquals(4, nextPage)
        assertEquals(1, second.activeIndex)
        assertEquals(second to null, second.moveActiveBy(1))
    }

    @Test fun `first result navigates immediately and next selects the second result`() {
        val first = searchHit(2, 0)
        val second = searchHit(6, 0)
        val update = ReaderSearchState("term").mergeWithInitialNavigation(
            progress(listOf(first, second), indexed = 2),
            currentPage = 9
        )

        assertEquals(GestureIntent.FlingToPage(2), update.navigation)
        assertEquals(0, update.state.activeIndex)
        val (next, targetPage) = update.state.moveActiveBy(1)
        assertEquals(6, targetPage)
        assertEquals(1, next.activeIndex)
    }

    private fun progress(hits: List<TextPageSearchHit>, indexed: Int) = TextSearchProgress(
        "term", hits, indexed, 0, 10, running = true
    )

    private fun searchProgress(
        spec: TextSearchSpec,
        indexed: Int,
        running: Boolean
    ) = TextSearchProgress(
        spec.query,
        emptyList(),
        indexed,
        0,
        10,
        running,
        spec = spec
    )

    private fun List<ReaderScreenState>.lastReadingSearch(): ReaderSearchState =
        requireNotNull((last { it is ReaderScreenState.Reading } as ReaderScreenState.Reading).search)

    private fun searchHit(
        page: Int,
        occurrence: Int,
        source: TextSource = TextSource.NATIVE_PDF
    ) = TextPageSearchHit(
        page,
        source,
        occurrence,
        0..0,
        listOf(PageSpaceRect(.1f, .1f, .2f, .2f)),
        "term"
    )
}
