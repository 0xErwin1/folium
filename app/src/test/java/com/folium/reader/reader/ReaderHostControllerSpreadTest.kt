package com.folium.reader.reader

import android.content.Context
import android.content.ContextWrapper
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.library.TwoPageSpreadPreferences
import com.folium.reader.core.ocr.OcrFailureMetadata
import com.folium.reader.core.ocr.OcrPageState
import com.folium.reader.core.ocr.OcrPageStatus
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.DocumentMetadata
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.HorizontalViewportState
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.ReadingPosition
import com.folium.reader.core.pdf.ReadingPositionToken
import com.folium.reader.core.pdf.ReadingPositionTokens
import com.folium.reader.core.pdf.ReflowSettings
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.SchedulerOutcome
import com.folium.reader.core.pdf.ViewportRenderRequest
import com.folium.reader.core.pdf.ViewportRenderer
import com.folium.reader.core.pdf.ViewportScheduler
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSearchSpec
import com.folium.reader.core.text.TextSource
import com.folium.reader.index.TextPageSearchHit
import com.folium.reader.library.OpenBookRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

private class SpreadFakeDocument(pageCount: Int) : PdfDocument {
    override val pageCount: Int = pageCount
    override val reflowable: Boolean = false

    override fun pageInfo(index: Int) = PageInfo(index, 1f, 1f, 0)
    override fun buildDisplayList(index: Int): DisplayList = error("no display list expected")
    override fun extractText(index: Int) = TextPage(emptyList(), TextSource.NATIVE_PDF)
    override fun outline(): List<OutlineEntry> = emptyList()
    override fun metadata() = DocumentMetadata.NONE
    override fun makePositionToken(pageIndex: Int): ReadingPositionToken =
        ReadingPositionTokens.mintPosition(ReadingPosition(pageIndex, 0))
    override fun resolvePositionToken(token: ReadingPositionToken): Int? = null
    override fun relayout(settings: ReflowSettings): Boolean = false
    override fun close() = Unit
}

private class SpreadEmptyRenderer : ViewportRenderer<BorrowedPage> {
    override fun render(
        request: ViewportRenderRequest,
        cancellationSignal: CancellationSignal
    ): RenderCandidate<BorrowedPage> = error("no render expected")
}

private object SpreadSilentTextLoader : SessionTextLoader {
    override fun load(pageIndex: Int, callback: (TextPageLoadResult) -> Unit) = Unit
    override fun close() = Unit
    override fun dispose() = Unit
}

/**
 * A real [ReaderSession]/[ReaderPresenter], built the same way [ReaderHostControllerRepaginationTest]'s
 * own `fakeSession` is, for the one test in this file that needs [ReaderHostController.dispatch] to
 * genuinely reach a presenter rather than no-op against a null [ReaderSession].
 */
private fun spreadFakeSession(
    document: SpreadFakeDocument,
    onChanged: (ReaderUiState<BorrowedPage>) -> Unit
): ReaderSession {
    fun scheduler(onOutcome: (SchedulerOutcome<BorrowedPage>) -> Unit) =
        ViewportScheduler(1, SpreadEmptyRenderer(), onOutcome = onOutcome)
    val readerDocument = ReaderDocument(
        document, BookId("book-1"), document.pageCount, emptyList(), TextEngineVersion("native-v1"),
        firstPageAspect = 1f, initialPage = 0, initialPageAspect = null
    )
    val presenter = ReaderPresenter(
        pageCount = document.pageCount, cacheBudgetBytes = 64L * 1024 * 1024,
        releaseValue = BorrowedPage::release, pageAspect = { 1f }, scheduleRetry = { _, _ -> },
        deliverToPresenter = { it() }, onChanged = onChanged, initialPage = 0,
        baseSchedulerFactory = ::scheduler, schedulerFactory = ::scheduler
    )
    val lifecycle = ReaderSessionLifecycle(
        unregisterCallbacks = {}, closeTextLoader = {}, closePresenter = {}, shutdownPresenter = {},
        disposeTextLoader = {}, closeTextIndex = {}, clearPageCache = {}, closeDocument = readerDocument::close
    )
    return ReaderSession(
        readerDocument, SpreadSilentTextLoader, lifecycle, null, OcrPipelineDispatch(),
        OcrStatusDispatch(), SearchOcrStatusDispatch(), DocumentPriorityGate(), presenter,
        noOpThumbnailPipeline()
    )
}

private class SpreadDirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

private fun spreadRequest() = OpenBookRequest(
    book = LibraryBook(BookId("book-1"), "Title", pageCount = 10, addedAtMillis = 0L),
    file = File("/does/not/matter.pdf"),
    initialPage = 0
)

private fun spreadReadingState(pageCount: Int, currentPage: Int, pagesPerView: Int = 1) =
    ReaderUiState<BorrowedPage>(HorizontalViewportState.initial(pageCount, currentPage, pagesPerView))

/**
 * Exercises [ReaderHostController.setSpreadEligible], [ReaderHostController.setTwoPageSpread] and
 * [ReaderScreenState.Reading.textPages] the same way [ReaderHostControllerTest] exercises the rest
 * of the controller: [ReaderHostController]'s own worker/main-post/session-opening seams are
 * replaced by direct-call fakes, and `onChanged` is captured so a test can publish a
 * [ReaderUiState] exactly as a real presenter would, without opening a real [ReaderSession].
 */
class ReaderHostControllerSpreadTest {

    private val context: Context = ContextWrapper(null)

    private fun controller(
        states: MutableList<ReaderScreenState>,
        resolveTwoPageSpreadPreference: () -> Boolean = { TwoPageSpreadPreferences.DEFAULT },
        persistTwoPageSpreadPreference: (Boolean) -> Unit = {}
    ): ReaderHostController {
        lateinit var changed: (ReaderUiState<BorrowedPage>) -> Unit
        val controller = ReaderHostController(
            context = context,
            request = spreadRequest(),
            onPageChanged = {},
            onState = { states += it },
            worker = SpreadDirectExecutor(),
            mainPost = { it() },
            openSession = { _, _, onChangedCallback -> changed = onChangedCallback; ReaderSessionResult.Missing },
            resolveTwoPageSpreadPreference = resolveTwoPageSpreadPreference,
            persistTwoPageSpreadPreference = persistTwoPageSpreadPreference
        )
        controller.start()
        changed(spreadReadingState(pageCount = 10, currentPage = 0))
        return controller
    }

    private fun List<ReaderScreenState>.lastReading(): ReaderScreenState.Reading =
        last { it is ReaderScreenState.Reading } as ReaderScreenState.Reading

    /** Like [controller], but also returns the captured `onChanged` a real presenter would call. */
    private fun controllerWithChanged(
        states: MutableList<ReaderScreenState>
    ): Pair<ReaderHostController, (ReaderUiState<BorrowedPage>) -> Unit> {
        lateinit var changed: (ReaderUiState<BorrowedPage>) -> Unit
        val controller = ReaderHostController(
            context = context,
            request = spreadRequest(),
            onPageChanged = {},
            onState = { states += it },
            worker = SpreadDirectExecutor(),
            mainPost = { it() },
            // The tests driving search do so entirely through publishSearch, never through the
            // debounced query this would otherwise schedule against the (unmocked-on-the-JVM) main
            // looper, so the scheduled action is simply never run.
            scheduleSearch = { _, _ -> {} },
            openSession = { _, _, onChangedCallback -> changed = onChangedCallback; ReaderSessionResult.Missing }
        )
        controller.start()
        changed(spreadReadingState(pageCount = 10, currentPage = 0))
        return controller to changed
    }

    private fun retryableOcrStatus() =
        OcrPageStatus(OcrPageState.FAILED, generation = 2, failure = OcrFailureMetadata("recognition", true))

    @Test fun `spread state starts unqualified with the default preference on`() {
        val states = mutableListOf<ReaderScreenState>()
        controller(states)

        assertEquals(ReaderSpreadState(false, true, 1), states.lastReading().spread)
    }

    @Test fun `an eligible window with the preference on turns pagesPerView effective`() {
        val states = mutableListOf<ReaderScreenState>()
        val controller = controller(states)

        controller.setSpreadEligible(eligible = true, gutterPx = 24)

        assertEquals(ReaderSpreadState(true, true, 2), states.lastReading().spread)
    }

    @Test fun `turning the preference off collapses an already-eligible window back to one page`() {
        val states = mutableListOf<ReaderScreenState>()
        val controller = controller(states)
        controller.setSpreadEligible(eligible = true, gutterPx = 24)

        controller.setTwoPageSpread(false)

        assertEquals(ReaderSpreadState(true, false, 1), states.lastReading().spread)
    }

    @Test fun `an ineligible window never shows a spread even with the preference on`() {
        val states = mutableListOf<ReaderScreenState>()
        val controller = controller(states)

        controller.setSpreadEligible(eligible = false, gutterPx = 24)

        assertEquals(ReaderSpreadState(false, true, 1), states.lastReading().spread)
    }

    @Test fun `the stored preference is resolved once at start, off the main thread rule`() {
        val states = mutableListOf<ReaderScreenState>()
        controller(states, resolveTwoPageSpreadPreference = { false })

        assertEquals(ReaderSpreadState(false, false, 1), states.lastReading().spread)
    }

    @Test fun `setTwoPageSpread persists the new value exactly once`() {
        val persisted = mutableListOf<Boolean>()
        val states = mutableListOf<ReaderScreenState>()
        val controller = controller(states, persistTwoPageSpreadPreference = { persisted += it })

        controller.setTwoPageSpread(false)

        assertEquals(listOf(false), persisted)
    }

    @Test fun `setTwoPageSpread with the value already in force persists nothing`() {
        val persisted = mutableListOf<Boolean>()
        val states = mutableListOf<ReaderScreenState>()
        val controller = controller(states, persistTwoPageSpreadPreference = { persisted += it })

        controller.setTwoPageSpread(true)

        assertEquals(emptyList<Boolean>(), persisted)
    }

    @Test fun `textPages holds both visible pages of a fitted spread and only the current page otherwise`() {
        val states = mutableListOf<ReaderScreenState>()
        lateinit var changed: (ReaderUiState<BorrowedPage>) -> Unit
        val controller = ReaderHostController(
            context = context,
            request = spreadRequest(),
            onPageChanged = {},
            onState = { states += it },
            worker = SpreadDirectExecutor(),
            mainPost = { it() },
            openSession = { _, _, onChangedCallback -> changed = onChangedCallback; ReaderSessionResult.Missing }
        )
        controller.start()

        changed(spreadReadingState(pageCount = 10, currentPage = 0))
        assertEquals(setOf(0), states.lastReading().textPages.keys)

        changed(spreadReadingState(pageCount = 10, currentPage = 0, pagesPerView = 2))
        assertEquals(setOf(0, 1), states.lastReading().textPages.keys)

        changed(spreadReadingState(pageCount = 10, currentPage = 3))
        assertEquals(setOf(3), states.lastReading().textPages.keys)
    }

    /** The last, odd page of an even-sized document has no right-hand neighbor to pair with. */
    @Test fun `the last odd page of a spread has no second visible page`() {
        val states = mutableListOf<ReaderScreenState>()
        lateinit var changed: (ReaderUiState<BorrowedPage>) -> Unit
        val controller = ReaderHostController(
            context = context,
            request = spreadRequest(),
            onPageChanged = {},
            onState = { states += it },
            worker = SpreadDirectExecutor(),
            mainPost = { it() },
            openSession = { _, _, onChangedCallback -> changed = onChangedCallback; ReaderSessionResult.Missing }
        )
        controller.start()

        changed(spreadReadingState(pageCount = 9, currentPage = 8, pagesPerView = 2))

        assertEquals(setOf(8), states.lastReading().textPages.keys)
    }

    @Test fun `an OCR event for the right page of a spread is published under its own key`() {
        val states = mutableListOf<ReaderScreenState>()
        val (controller, changed) = controllerWithChanged(states)
        changed(spreadReadingState(pageCount = 10, currentPage = 0, pagesPerView = 2))

        controller.publishOcrStatus(1, retryableOcrStatus())

        val ocrPages = states.lastReading().ocrPages
        assertEquals(1, ocrPages.getValue(1).pageIndex)
        assertTrue(ocrPages.getValue(1).retryAvailable)
        assertNull(ocrPages[0])
    }

    /** Single-page mode is unaffected: the same event still drives the untouched [ReaderScreenState.Reading.ocr] field. */
    @Test fun `single-page mode still drives the existing singular ocr field exactly as before`() {
        val states = mutableListOf<ReaderScreenState>()
        val (controller, _) = controllerWithChanged(states)

        controller.publishOcrStatus(0, retryableOcrStatus())

        val reading = states.lastReading()
        assertEquals(0, reading.ocr?.pageIndex)
        assertTrue(reading.ocr?.retryAvailable == true)
        assertEquals(0, reading.ocrPages.getValue(0).pageIndex)
    }

    @Test fun `a stale OCR event for a page that left the visible set is dropped`() {
        val states = mutableListOf<ReaderScreenState>()
        val (controller, changed) = controllerWithChanged(states)
        changed(spreadReadingState(pageCount = 10, currentPage = 0, pagesPerView = 2))
        controller.publishOcrStatus(1, retryableOcrStatus())
        assertTrue(1 in states.lastReading().ocrPages)

        changed(spreadReadingState(pageCount = 10, currentPage = 0, pagesPerView = 1))
        assertFalse(1 in states.lastReading().ocrPages)

        controller.publishOcrStatus(1, retryableOcrStatus())
        assertFalse("a late event for a page no longer visible must not resurrect it", 1 in states.lastReading().ocrPages)
    }

    @Test fun `retry targets the page it was asked for, not the reader's current page`() {
        val states = mutableListOf<ReaderScreenState>()
        val (controller, changed) = controllerWithChanged(states)
        changed(spreadReadingState(pageCount = 10, currentPage = 0, pagesPerView = 2))
        controller.publishOcrStatus(1, retryableOcrStatus())

        controller.retryOcr(1)

        val ocrPages = states.lastReading().ocrPages
        assertTrue(ocrPages.getValue(1).retryPending)
        assertNull(ocrPages[0])
    }

    @Test fun `retryOcr for the reader's current page delegates to the single-page overload`() {
        val states = mutableListOf<ReaderScreenState>()
        val (controller, changed) = controllerWithChanged(states)
        changed(spreadReadingState(pageCount = 10, currentPage = 0, pagesPerView = 2))
        controller.publishOcrStatus(1, retryableOcrStatus())

        controller.retryOcr(0)

        // Nothing to retry for page 0 (its singular ocrState was never set by a real session in this
        // harness), so the delegate is a no-op — the point is that page 1's independent state is
        // left untouched by a call naming page 0.
        assertTrue(states.lastReading().ocrPages.getValue(1).retryAvailable)
    }

    /**
     * O2(b): stepping to a match on the odd, right-hand page of a spread must open the spread it
     * belongs to, and the match itself must stay active even though the viewport's own `currentPage`
     * ends up being its left neighbour rather than its own page.
     *
     * This harness has no real [ReaderSession]/[ReaderPresenter] behind it (see this file's own doc),
     * so [ReaderHostController.dispatch] is a no-op here and the actual page landed on cannot be
     * observed through it — [com.folium.reader.core.pdf.HorizontalViewportReducer]'s own tests already
     * cover that a [GestureIntent.FlingToPage] to an odd page pairs down to its spread while a spread
     * is fitted. What this test proves is the host's own part: [ReaderHostController.selectSearchResult]
     * hands the reducer the match's real, unpaired page index (5, not 4) rather than pre-pairing it
     * itself, and [ReaderSearchState.activeIdentity]/[ReaderSearchState.activeMatch] track the
     * selected match by identity, never by `currentPage`, so they survive the pairing untouched.
     */
    @Test fun `selecting a match on an odd page keeps that match active`() {
        val states = mutableListOf<ReaderScreenState>()
        val document = SpreadFakeDocument(pageCount = 10)
        lateinit var session: ReaderSession
        val controller = ReaderHostController(
            context = context,
            request = spreadRequest(),
            onPageChanged = {},
            onState = { states += it },
            worker = SpreadDirectExecutor(),
            mainPost = { it() },
            scheduleSearch = { _, _ -> {} },
            openSession = { _, _, onChangedCallback ->
                session = spreadFakeSession(document, onChangedCallback)
                ReaderSessionResult.Opened(session)
            }
        )
        controller.start()
        session.presenter.dispatch(GestureIntent.SetPagesPerView(2))
        // Two hits, not one: a lone hit would already auto-navigate as "first result found" (see
        // ReaderHostControllerTest), which would leave this test unable to tell that behavior apart
        // from selectSearchResult's own navigation. The first hit absorbs the auto-navigate, so
        // selecting the second is the thing actually under test.
        val identity = ReaderSearchMatchIdentity(pageIndex = 5, occurrenceIndex = 0)
        val hitOnPageOne = TextPageSearchHit(1, TextSource.NATIVE_PDF, 0, 0..0, emptyList(), "term")
        val hitOnPageFive = TextPageSearchHit(5, TextSource.NATIVE_PDF, 0, 0..0, emptyList(), "term")
        controller.search(TextSearchSpec("term"))
        controller.publishSearch(
            1L,
            TextSearchProgress("term", listOf(hitOnPageOne, hitOnPageFive), 2, 0, 10, running = true)
        )

        controller.selectSearchResult(identity)

        assertEquals(4, session.presenter.uiState.state.currentPage)
        assertEquals(2, session.presenter.uiState.state.pagesPerView)
        val reading = states.lastReading()
        assertEquals(identity, reading.search?.activeIdentity)
        assertEquals(5, reading.search?.activeMatch?.pageIndex)
    }

    /**
     * O2(a): search highlights for a spread's right page are already available to the UI — every
     * match [ReaderSearchState.matches] holds spans the whole document, not just `currentPage`. The
     * only place a page's own matches were ever filtered out was [ReaderScreen]'s per-page rendering
     * (`if (pageIndex == currentPage) search else null`), which is the UI unit's concern, not the
     * host's; no host change was made or needed for this.
     */
    @Test fun `search matches for a page other than currentPage are already present in the search state`() {
        val states = mutableListOf<ReaderScreenState>()
        val (controller, changed) = controllerWithChanged(states)
        changed(spreadReadingState(pageCount = 10, currentPage = 0, pagesPerView = 2))
        val hitOnRightPage = TextPageSearchHit(1, TextSource.NATIVE_PDF, 0, 0..0, emptyList(), "term")
        controller.search(TextSearchSpec("term"))

        controller.publishSearch(1L, TextSearchProgress("term", listOf(hitOnRightPage), 1, 0, 10, running = true))

        assertEquals(listOf(1), states.lastReading().search?.matches?.map { it.pageIndex })
    }
}
