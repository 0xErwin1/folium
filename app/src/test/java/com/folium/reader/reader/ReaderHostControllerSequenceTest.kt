package com.folium.reader.reader

import android.content.Context
import android.content.ContextWrapper
import com.folium.reader.core.ink.Sheet
import com.folium.reader.core.ink.SheetAnchor
import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.ink.SheetListing
import com.folium.reader.core.ink.SheetSummary
import com.folium.reader.core.ink.SheetTemplate
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.DocumentMetadata
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.ReadingPosition
import com.folium.reader.core.pdf.ReadingPositionToken
import com.folium.reader.core.pdf.ReadingPositionTokens
import com.folium.reader.core.pdf.ReflowLayoutBox
import com.folium.reader.core.pdf.ReflowSettings
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.SchedulerOutcome
import com.folium.reader.core.pdf.ViewportRenderRequest
import com.folium.reader.core.pdf.ViewportRenderer
import com.folium.reader.core.pdf.ViewportScheduler
import com.folium.reader.core.sequence.PlacedSheet
import com.folium.reader.core.sequence.ReadingSequence
import com.folium.reader.core.sequence.SHEET_RANK_STEP
import com.folium.reader.core.sequence.SequenceItem
import com.folium.reader.core.sequence.SequenceLabel
import com.folium.reader.core.sequence.spreadUnits
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSearchSpec
import com.folium.reader.core.text.TextSource
import com.folium.reader.index.DocumentContentVersion
import com.folium.reader.index.TextPageSearchHit
import com.folium.reader.index.TransientTextPageIndex
import com.folium.reader.library.OpenBookRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

private val SEQUENCE_BOOK = BookId("book-1")

/**
 * A document whose EPUB-style positions resolve through [pageOfPosition], which a relayout swaps for
 * [relaidOutPageOfPosition] so a test can move an anchor's page by re-paginating. A batch naming any
 * position [failsOn] accepts fails as a whole, the way the engine does once the document is closed.
 */
private class SequenceFakeDocument(
    pageCount: Int,
    override val reflowable: Boolean = false,
    private val relayoutPageCount: Int = pageCount,
    private var pageOfPosition: (ReadingPosition) -> Int? = { null },
    private val relaidOutPageOfPosition: (ReadingPosition) -> Int? = pageOfPosition,
    private val failsOn: (ReadingPosition) -> Boolean = { false },
    private val positionOfPage: (Int) -> ReadingPosition? = { null }
) : PdfDocument {
    private var pageCountField = pageCount
    override val pageCount: Int get() = pageCountField

    override fun pageInfo(index: Int) = PageInfo(index, 1f, 1f, 0)
    override fun buildDisplayList(index: Int): DisplayList = error("no display list expected")
    override fun extractText(index: Int) = TextPage(emptyList(), TextSource.NATIVE_PDF)
    override fun outline(): List<OutlineEntry> = emptyList()
    override fun metadata() = DocumentMetadata.NONE
    override fun makePositionToken(pageIndex: Int): ReadingPositionToken =
        ReadingPositionTokens.mintPosition(ReadingPosition(pageIndex, 0))
    override fun resolvePositionToken(token: ReadingPositionToken): Int? = null

    override fun positionOf(pageIndex: Int): ReadingPosition? = positionOfPage(pageIndex)

    override fun resolvePositions(positions: List<ReadingPosition>): List<Int?> {
        if (positions.any(failsOn)) throw PdfException(PdfFailure.Closed)
        return positions.map(pageOfPosition)
    }

    override fun relayout(settings: ReflowSettings): Boolean {
        pageCountField = relayoutPageCount
        pageOfPosition = relaidOutPageOfPosition
        return true
    }

    override fun close() = Unit
}

private class SequenceEmptyRenderer : ViewportRenderer<BorrowedPage> {
    override fun render(
        request: ViewportRenderRequest,
        cancellationSignal: CancellationSignal
    ): RenderCandidate<BorrowedPage> = error("no render expected")
}

private object SequenceSilentTextLoader : SessionTextLoader {
    override fun load(pageIndex: Int, callback: (TextPageLoadResult) -> Unit) = Unit
    override fun close() = Unit
    override fun dispose() = Unit
}

private class SequenceDirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

/** Queues every job until the test runs it, so a test chooses how worker jobs interleave. */
private class SequenceDeferredExecutor : Executor {
    private val queued = ArrayDeque<Runnable>()

    val pending: Int get() = queued.size

    override fun execute(command: Runnable) {
        queued.addLast(command)
    }

    fun runNext() = queued.removeFirst().run()

    fun runLast() = queued.removeLast().run()

    fun runAll() {
        while (queued.isNotEmpty()) runNext()
    }
}

/**
 * A real session whose render outcomes are dropped rather than delivered: they arrive on scheduler
 * threads, and delivering them there would publish reader state concurrently with the test thread.
 * Every publish this file asserts on comes from a page change, which the presenter makes synchronously.
 */
private fun sequenceSession(
    document: SequenceFakeDocument,
    initialPage: Int,
    onChanged: (ReaderUiState<BorrowedPage>) -> Unit
): ReaderSession {
    val cache = ByteBoundedPageCache<RenderedPage>(64L * 1024 * 1024)
    val textIndex = TransientTextPageIndex()
    val rig = RepaginationRig(
        cache = cache, priorityGate = DocumentPriorityGate(), cacheBudgetBytes = 64L * 1024 * 1024,
        mainPost = {}, scheduleRetry = { _, _ -> }, onChanged = onChanged, textIndex = textIndex,
        documentVersion = DocumentContentVersion("ab".repeat(32)), nativeEngineVersion = TextEngineVersion("native-v1")
    )
    val readerDocument = ReaderDocument(
        document, SEQUENCE_BOOK, document.pageCount, emptyList(), TextEngineVersion("native-v1"),
        firstPageAspect = 1f, initialPage = initialPage, initialPageAspect = null
    )

    fun scheduler(onOutcome: (SchedulerOutcome<BorrowedPage>) -> Unit) =
        ViewportScheduler(1, SequenceEmptyRenderer(), onOutcome = onOutcome)

    val presenter = ReaderPresenter(
        pageCount = document.pageCount, cacheBudgetBytes = rig.cacheBudgetBytes,
        releaseValue = BorrowedPage::release, pageAspect = { 1f }, scheduleRetry = rig.scheduleRetry,
        deliverToPresenter = rig.mainPost, onChanged = onChanged, initialPage = initialPage,
        baseSchedulerFactory = ::scheduler, schedulerFactory = ::scheduler
    )
    val lifecycle = ReaderSessionLifecycle(
        unregisterCallbacks = {}, closeTextLoader = {}, closePresenter = {}, shutdownPresenter = {},
        disposeTextLoader = {}, closeTextIndex = textIndex::close, clearPageCache = cache::clear,
        closeDocument = readerDocument::close
    )
    return ReaderSession(
        readerDocument, SequenceSilentTextLoader, lifecycle, null, OcrPipelineDispatch(),
        OcrStatusDispatch(), SearchOcrStatusDispatch(), DocumentPriorityGate(), presenter,
        noOpThumbnailPipeline(), "aaaaaaaaaaaaaaaa", rig
    )
}

private fun summary(id: String, anchor: SheetAnchor, createdAt: Long = 0L) =
    SheetSummary(SheetId(id), id, createdAt, createdAt, SheetTemplate.BLANK, anchor)

private fun pageSheet(id: String, pageIndex: Int, rank: Long) =
    summary(id, SheetAnchor.Page(SEQUENCE_BOOK, pageIndex, rank))

private fun textSheet(id: String, position: ReadingPosition, rank: Long = 0L) =
    summary(id, SheetAnchor.Text(SEQUENCE_BOOK, position, rank))

/** The sheets a test book starts with, which [create] and [rerank] change the way the store would. */
private class SequenceSheetShelf(initial: List<SheetSummary>) {
    val sheets = initial.toMutableList()
    val created = mutableListOf<Sheet>()
    var failCreate = false

    fun create(sheet: Sheet) {
        if (failCreate) throw IOException("storage full")
        created += sheet
        sheets += SheetSummary(sheet.id, sheet.title, sheet.createdAtEpochMillis, sheet.updatedAtEpochMillis, sheet.template, sheet.anchor)
    }

    fun rerank(id: SheetId, rank: Long) {
        val index = sheets.indexOfFirst { it.id == id }
        val moved = when (val anchor = requireNotNull(sheets[index].anchor)) {
            is SheetAnchor.Page -> anchor.copy(rank = rank)
            is SheetAnchor.Text -> anchor.copy(rank = rank)
        }
        sheets[index] = sheets[index].copy(anchor = moved)
    }

    fun rankOf(id: String): Long = requireNotNull(sheets.single { it.id == SheetId(id) }.anchor).rank
}

/**
 * Exercises the interleaved reading sequence [ReaderHostController] keeps next to its page presenter:
 * a real [ReaderSession] and [ReaderPresenter] sit behind a direct worker and main thread, so a step
 * lands on the page the presenter really shows and the progress the activity would really record.
 */
class ReaderHostControllerSequenceTest {

    private val context: Context = ContextWrapper(null)

    private class Harness(
        val controller: ReaderHostController,
        val states: MutableList<ReaderScreenState>,
        val recorded: MutableList<Int>,
        val session: () -> ReaderSession
    ) {
        val reading: ReaderScreenState.Reading
            get() = states.last { it is ReaderScreenState.Reading } as ReaderScreenState.Reading

        val sequence: ReaderSequenceState get() = reading.sequence

        val presenterPage: Int get() = session().presenter.uiState.state.currentPage
    }

    private fun harness(
        document: SequenceFakeDocument,
        initialPage: Int,
        sheets: () -> List<SheetSummary>,
        scheduleSearch: (Long, () -> Unit) -> (() -> Unit) = { _, _ -> {} },
        worker: Executor = SequenceDirectExecutor(),
        shelf: SequenceSheetShelf? = null
    ): Harness {
        val states = mutableListOf<ReaderScreenState>()
        val recorded = mutableListOf<Int>()
        lateinit var session: ReaderSession

        val controller = ReaderHostController(
            context = context,
            request = OpenBookRequest(
                book = LibraryBook(SEQUENCE_BOOK, "Title", pageCount = document.pageCount, addedAtMillis = 0L),
                file = File("/does/not/matter.pdf"),
                initialPage = initialPage
            ),
            onPageChanged = { recorded += it },
            onState = { states += it },
            worker = worker,
            mainPost = { it() },
            scheduleSearch = scheduleSearch,
            openSession = { _, _, onChanged ->
                session = sequenceSession(document, initialPage, onChanged)
                ReaderSessionResult.Opened(session)
            },
            loadAnchoredSheets = { bookId ->
                assertEquals(SEQUENCE_BOOK, bookId)
                SheetListing(sheets(), emptyList())
            },
            createSheet = shelf?.let { it::create },
            rerankSheet = { id, rank -> requireNotNull(shelf).rerank(id, rank) }
        )
        controller.start()
        controller.setViewport(ReaderViewport(1200, 700))

        return Harness(controller, states, recorded) { session }
    }

    private fun twoSheetsOnPage18() = listOf(
        pageSheet("sheet-2", pageIndex = 18, rank = SHEET_RANK_STEP),
        pageSheet("sheet-1", pageIndex = 18, rank = 0L)
    )

    @Test fun `stepping forward from a page with two sheets reads both before the next page`() {
        val h = harness(SequenceFakeDocument(pageCount = 30), initialPage = 18, sheets = ::twoSheetsOnPage18)
        assertEquals(SequenceLabel(19, null), h.sequence.currentLabel)

        h.controller.step(+1)
        assertEquals(SheetId("sheet-1"), h.sequence.currentSheet)
        assertEquals(SequenceLabel(19, 1), h.sequence.currentLabel)
        assertEquals(18, h.presenterPage)

        h.controller.step(+1)
        assertEquals(SheetId("sheet-2"), h.sequence.currentSheet)
        assertEquals(SequenceLabel(19, 2), h.sequence.currentLabel)
        assertEquals(18, h.presenterPage)
        assertEquals("progress must not move while reading a page's sheets", emptyList<Int>(), h.recorded)

        h.controller.step(+1)
        assertNull(h.sequence.currentSheet)
        assertEquals(SequenceLabel(20, null), h.sequence.currentLabel)
        assertEquals(19, h.presenterPage)
        assertEquals(listOf(19), h.recorded)
    }

    @Test fun `stepping backward reads a page's sheets in reverse before the page itself`() {
        val h = harness(SequenceFakeDocument(pageCount = 30), initialPage = 19, sheets = ::twoSheetsOnPage18)

        h.controller.step(-1)
        assertEquals(SheetId("sheet-2"), h.sequence.currentSheet)
        assertEquals(18, h.presenterPage)

        h.controller.step(-1)
        assertEquals(SheetId("sheet-1"), h.sequence.currentSheet)
        assertEquals(18, h.presenterPage)

        h.controller.step(-1)
        assertNull(h.sequence.currentSheet)
        assertEquals(SequenceLabel(19, null), h.sequence.currentLabel)
        assertEquals(18, h.presenterPage)

        h.controller.step(-1)
        assertEquals(17, h.presenterPage)
        assertEquals(listOf(18, 17), h.recorded)
    }

    @Test fun `a direct page jump from a sheet clears the sheet cursor`() {
        val h = harness(SequenceFakeDocument(pageCount = 30), initialPage = 18, sheets = ::twoSheetsOnPage18)
        h.controller.step(+1)

        h.controller.dispatch(GestureIntent.FlingToPage(5))

        assertNull(h.sequence.currentSheet)
        assertEquals(SequenceLabel(6, null), h.sequence.currentLabel)
        assertEquals(5, h.presenterPage)
    }

    @Test fun `a jump to the sheet's own page still clears the sheet cursor`() {
        val h = harness(SequenceFakeDocument(pageCount = 30), initialPage = 18, sheets = ::twoSheetsOnPage18)
        h.controller.step(+1)

        h.controller.dispatch(GestureIntent.FlingToPage(18))

        assertNull(h.sequence.currentSheet)
        assertEquals(SequenceLabel(19, null), h.sequence.currentLabel)
        assertEquals(18, h.presenterPage)
    }

    @Test fun `a search result reached from a sheet clears the sheet cursor`() {
        val h = harness(SequenceFakeDocument(pageCount = 30), initialPage = 18, sheets = ::twoSheetsOnPage18)
        h.controller.step(+1)
        val hit = TextPageSearchHit(3, TextSource.NATIVE_PDF, 0, 0..0, emptyList(), "term")

        h.controller.search(TextSearchSpec("term"))
        h.controller.publishSearch(1L, TextSearchProgress("term", listOf(hit), 1, 0, 30, running = true))

        assertNull(h.sequence.currentSheet)
        assertEquals(3, h.presenterPage)
    }

    @Test fun `a reflowable book re-resolves its text anchors after every repagination`() {
        val position = ReadingPosition(chapterIndex = 0, characterOffset = 500)
        val document = SequenceFakeDocument(
            pageCount = 10,
            reflowable = true,
            relayoutPageCount = 12,
            pageOfPosition = { if (it == position) 4 else null },
            relaidOutPageOfPosition = { if (it == position) 7 else null }
        )
        val h = harness(document, initialPage = 0, sheets = { listOf(textSheet("note", position)) })
        val sheet = SequenceItem.Sheet(SheetId("note"), pageIndex = 4, ordinal = 1)
        assertEquals(sheet, h.sequence.units[5].left)

        h.controller.repaginate(ReflowSettings(ReflowLayoutBox(450f, 675f, 22f), ""))

        val moved = SequenceItem.Sheet(SheetId("note"), pageIndex = 7, ordinal = 1)
        assertEquals(13, h.sequence.units.size)
        assertEquals(moved, h.sequence.units[8].left)
    }

    @Test fun `a sheet being read stays current across a repagination that moves it`() {
        val position = ReadingPosition(chapterIndex = 0, characterOffset = 500)
        val document = SequenceFakeDocument(
            pageCount = 10,
            reflowable = true,
            relayoutPageCount = 12,
            pageOfPosition = { if (it == position) 4 else null },
            relaidOutPageOfPosition = { if (it == position) 7 else null }
        )
        val h = harness(document, initialPage = 0, sheets = { listOf(textSheet("note", position)) })
        h.controller.goToSheet(SheetId("note"))
        assertEquals(4, h.presenterPage)

        h.controller.repaginate(ReflowSettings(ReflowLayoutBox(450f, 675f, 22f), ""))

        assertEquals(SheetId("note"), h.sequence.currentSheet)
        assertEquals(7, h.presenterPage)
    }

    @Test fun `a text anchor that cannot be resolved is read after the last page`() {
        val h = harness(
            SequenceFakeDocument(pageCount = 10),
            initialPage = 0,
            sheets = { listOf(textSheet("lost", ReadingPosition(3, 40))) }
        )

        assertEquals(11, h.sequence.units.size)
        assertEquals(SequenceItem.Sheet(SheetId("lost"), pageIndex = 9, ordinal = 1), h.sequence.units.last().left)
    }

    @Test fun `with no sheets the sequence is exactly the pager's pages`() {
        val h = harness(SequenceFakeDocument(pageCount = 9), initialPage = 4, sheets = { emptyList() })
        assertEquals(pagerPageCount(9, 1), h.sequence.units.size)
        assertEquals(4, h.sequence.currentUnit)

        h.controller.step(+1)
        assertEquals(5, h.presenterPage)
        assertEquals(pagerPageFor(5, 1), h.sequence.currentUnit)
        assertEquals(listOf(5), h.recorded)

        h.controller.setSpreadEligible(eligible = true, gutterPx = 0)
        assertEquals(pagerPageCount(9, 2), h.sequence.units.size)
        assertEquals(pagerPageFor(h.presenterPage, 2), h.sequence.currentUnit)

        h.controller.step(+1)
        assertEquals(6, h.presenterPage)
        assertEquals(pagerPageFor(6, 2), h.sequence.currentUnit)

        h.controller.step(-1)
        assertEquals(4, h.presenterPage)
        assertNull(h.sequence.currentSheet)
    }

    @Test fun `two-page stepping walks the spread units with a sheet beside its page`() {
        val sheets = listOf(pageSheet("beside-3", pageIndex = 3, rank = 0L))
        val h = harness(SequenceFakeDocument(pageCount = 10), initialPage = 0, sheets = { sheets })
        h.controller.setSpreadEligible(eligible = true, gutterPx = 0)

        val expected = spreadUnits(
            ReadingSequence.build(10, listOf(PlacedSheet(SheetId("beside-3"), 3, 0L, 0L))),
            pagesPerView = 2
        ).units
        assertEquals(expected, h.sequence.units)

        val presenterPages = mutableListOf(h.presenterPage)
        val currentSheets = mutableListOf(h.sequence.currentSheet)
        repeat(3) {
            h.controller.step(+1)
            presenterPages += h.presenterPage
            currentSheets += h.sequence.currentSheet
        }

        assertEquals(listOf(0, 2, 2, 4), presenterPages)
        assertEquals(listOf(null, null, SheetId("beside-3"), null), currentSheets)
        assertEquals(3, h.sequence.currentUnit)
    }

    @Test fun `settling the pager on a unit makes it current`() {
        val h = harness(SequenceFakeDocument(pageCount = 30), initialPage = 0, sheets = ::twoSheetsOnPage18)

        h.controller.settleUnit(20)

        assertEquals(SheetId("sheet-2"), h.sequence.currentSheet)
        assertEquals(20, h.sequence.currentUnit)
        assertEquals(18, h.presenterPage)
    }

    @Test fun `going to a sheet by id lands on it and its page`() {
        val h = harness(SequenceFakeDocument(pageCount = 30), initialPage = 0, sheets = ::twoSheetsOnPage18)

        h.controller.goToSheet(SheetId("sheet-2"))

        assertEquals(SheetId("sheet-2"), h.sequence.currentSheet)
        assertEquals(SequenceLabel(19, 2), h.sequence.currentLabel)
        assertEquals(18, h.presenterPage)
    }

    @Test fun `reloading sheets picks up a sheet created after the book opened`() {
        val sheets = mutableListOf<SheetSummary>()
        val h = harness(SequenceFakeDocument(pageCount = 10), initialPage = 2, sheets = { sheets.toList() })
        assertEquals(10, h.sequence.units.size)

        sheets += pageSheet("fresh", pageIndex = 2, rank = 0L)
        h.controller.reloadSheets()

        assertEquals(11, h.sequence.units.size)
        assertEquals(SequenceItem.Sheet(SheetId("fresh"), 2, 1), h.sequence.units[3].left)
    }

    @Test fun `a sheet listing that fails keeps the sheets already placed`() {
        var failing = false
        val h = harness(SequenceFakeDocument(pageCount = 30), initialPage = 18, sheets = {
            if (failing) throw IOException("storage unavailable")
            twoSheetsOnPage18()
        })
        val placed = h.sequence.units

        failing = true
        h.controller.reloadSheets()

        assertEquals(placed, h.sequence.units)
    }

    @Test fun `a sheet listing that fails when the book opens leaves it reading without sheets`() {
        val h = harness(SequenceFakeDocument(pageCount = 9), initialPage = 4, sheets = {
            throw IOException("storage unavailable")
        })

        assertEquals(9, h.sequence.units.size)
        assertEquals(SequenceLabel(5, null), h.sequence.currentLabel)
    }

    @Test fun `text anchors the document fails to resolve are read after the last page`() {
        val document = SequenceFakeDocument(pageCount = 10, reflowable = true, failsOn = { true })
        val h = harness(document, initialPage = 0, sheets = {
            listOf(textSheet("lost", ReadingPosition(0, 40)), pageSheet("kept", pageIndex = 2, rank = 0L))
        })

        assertEquals(12, h.sequence.units.size)
        assertEquals(SequenceItem.Sheet(SheetId("kept"), pageIndex = 2, ordinal = 1), h.sequence.units[3].left)
        assertEquals(SequenceItem.Sheet(SheetId("lost"), pageIndex = 9, ordinal = 1), h.sequence.units.last().left)
    }

    @Test fun `one text anchor the document cannot resolve does not displace the others`() {
        val good = ReadingPosition(chapterIndex = 0, characterOffset = 500)
        val bad = ReadingPosition(chapterIndex = 7, characterOffset = 0)
        val document = SequenceFakeDocument(
            pageCount = 10,
            reflowable = true,
            pageOfPosition = { if (it == good) 4 else null },
            failsOn = { it == bad }
        )
        val h = harness(document, initialPage = 0, sheets = {
            listOf(textSheet("good", good), textSheet("bad", bad, rank = SHEET_RANK_STEP))
        })

        assertEquals(12, h.sequence.units.size)
        assertEquals(SequenceItem.Sheet(SheetId("good"), pageIndex = 4, ordinal = 1), h.sequence.units[5].left)
        assertEquals(SequenceItem.Sheet(SheetId("bad"), pageIndex = 9, ordinal = 1), h.sequence.units.last().left)
    }

    @Test fun `a sheet load superseded by a reload is not adopted when it lands late`() {
        val worker = SequenceDeferredExecutor()
        val listings = ArrayDeque(listOf(
            listOf(pageSheet("fresh", pageIndex = 3, rank = 0L)),
            listOf(pageSheet("stale", pageIndex = 5, rank = 0L))
        ))
        val h = harness(SequenceFakeDocument(pageCount = 10), initialPage = 0, sheets = { listings.removeFirst() }, worker = worker)
        worker.runNext()
        assertEquals(1, worker.pending)

        h.controller.reloadSheets()
        worker.runLast()
        worker.runNext()

        assertEquals(11, h.sequence.units.size)
        assertEquals(SequenceItem.Sheet(SheetId("fresh"), pageIndex = 3, ordinal = 1), h.sequence.units[4].left)
    }

    @Test fun `a sheet load resolved before a repagination is not adopted after it`() {
        val position = ReadingPosition(chapterIndex = 0, characterOffset = 500)
        val document = SequenceFakeDocument(
            pageCount = 10,
            reflowable = true,
            relayoutPageCount = 12,
            pageOfPosition = { if (it == position) 4 else null },
            relaidOutPageOfPosition = { if (it == position) 7 else null }
        )
        val worker = SequenceDeferredExecutor()
        val h = harness(document, initialPage = 0, sheets = { listOf(textSheet("note", position)) }, worker = worker)
        worker.runNext()

        h.controller.repaginate(ReflowSettings(ReflowLayoutBox(450f, 675f, 22f), ""))
        worker.runNext()
        assertNull(h.sequence.units.firstNotNullOfOrNull { it.left as? SequenceItem.Sheet })

        worker.runAll()

        assertEquals(13, h.sequence.units.size)
        assertEquals(SequenceItem.Sheet(SheetId("note"), pageIndex = 7, ordinal = 1), h.sequence.units[8].left)
    }

    @Test fun `deleting the sheet being read leaves the reader on that sheet's page`() {
        val sheets = twoSheetsOnPage18().toMutableList()
        val h = harness(SequenceFakeDocument(pageCount = 30), initialPage = 0, sheets = { sheets.toList() })
        h.controller.goToSheet(SheetId("sheet-2"))

        sheets.removeAll { it.id == SheetId("sheet-2") }
        h.controller.reloadSheets()

        assertNull(h.sequence.currentSheet)
        assertEquals(SequenceLabel(19, null), h.sequence.currentLabel)
        assertEquals(18, h.presenterPage)
        assertEquals(31, h.sequence.units.size)
    }

    private fun shelfHarness(
        document: SequenceFakeDocument,
        initialPage: Int,
        sheets: List<SheetSummary>,
        worker: Executor = SequenceDirectExecutor()
    ): Pair<Harness, SequenceSheetShelf> {
        val shelf = SequenceSheetShelf(sheets)
        return harness(document, initialPage, sheets = { shelf.sheets.toList() }, worker = worker, shelf = shelf) to shelf
    }

    private fun Harness.createSheet() = controller.createSheetAfterCurrent { pageNumber -> "Title · $pageNumber" }

    private fun Harness.sheetsOf(pageIndex: Int): List<SheetId> =
        sequence.units.mapNotNull { unit -> (unit.left as? SequenceItem.Sheet)?.takeIf { it.pageIndex == pageIndex }?.id }

    @Test fun `a sheet created on a page is read first after that page and becomes current`() {
        val (h, shelf) = shelfHarness(SequenceFakeDocument(pageCount = 30), initialPage = 18, sheets = twoSheetsOnPage18())

        h.createSheet()

        val created = shelf.created.single()
        assertEquals(SheetAnchor.Page(SEQUENCE_BOOK, 18, -SHEET_RANK_STEP), created.anchor)
        assertEquals("Title · 19", created.title)
        assertEquals(SheetTemplate.BLANK, created.template)
        assertEquals(listOf(created.id, SheetId("sheet-1"), SheetId("sheet-2")), h.sheetsOf(18))
        assertEquals(created.id, h.sequence.currentSheet)
        assertEquals(SequenceLabel(19, 1), h.sequence.currentLabel)
        assertEquals(18, h.presenterPage)
    }

    @Test fun `a sheet created on a sheet is read between it and the next one`() {
        val (h, shelf) = shelfHarness(SequenceFakeDocument(pageCount = 30), initialPage = 0, sheets = twoSheetsOnPage18())
        h.controller.goToSheet(SheetId("sheet-1"))

        h.createSheet()

        val created = shelf.created.single()
        assertEquals(SheetAnchor.Page(SEQUENCE_BOOK, 18, SHEET_RANK_STEP / 2), created.anchor)
        assertEquals(listOf(SheetId("sheet-1"), created.id, SheetId("sheet-2")), h.sheetsOf(18))
        assertEquals(created.id, h.sequence.currentSheet)
        assertEquals(SequenceLabel(19, 2), h.sequence.currentLabel)
    }

    @Test fun `a sheet created where no rank is left renumbers that page's sheets`() {
        val crowded = listOf(pageSheet("first", pageIndex = 5, rank = 0L), pageSheet("second", pageIndex = 5, rank = 1L))
        val (h, shelf) = shelfHarness(SequenceFakeDocument(pageCount = 10), initialPage = 0, sheets = crowded)
        h.controller.goToSheet(SheetId("first"))

        h.createSheet()

        val created = shelf.created.single()
        assertEquals(SHEET_RANK_STEP, requireNotNull(created.anchor).rank)
        assertEquals(0L, shelf.rankOf("first"))
        assertEquals(2 * SHEET_RANK_STEP, shelf.rankOf("second"))
        assertEquals(listOf(SheetId("first"), created.id, SheetId("second")), h.sheetsOf(5))
        assertEquals(created.id, h.sequence.currentSheet)
    }

    @Test fun `a sheet created in a reflowable book is anchored to the text its page starts at`() {
        val document = SequenceFakeDocument(
            pageCount = 10,
            reflowable = true,
            pageOfPosition = { position -> position.characterOffset / 100 },
            positionOfPage = { page -> ReadingPosition(chapterIndex = 0, characterOffset = page * 100) }
        )
        val (h, shelf) = shelfHarness(document, initialPage = 4, sheets = emptyList())

        h.createSheet()

        val created = shelf.created.single()
        assertEquals(SheetAnchor.Text(SEQUENCE_BOOK, ReadingPosition(0, 400), 0L), created.anchor)
        assertEquals(created.id, h.sequence.currentSheet)
        assertEquals(4, h.presenterPage)
    }

    @Test fun `a sheet created on a reflowable page with no text position is anchored to that page`() {
        val document = SequenceFakeDocument(pageCount = 10, reflowable = true)
        val (h, shelf) = shelfHarness(document, initialPage = 4, sheets = emptyList())

        h.createSheet()

        assertEquals(SheetAnchor.Page(SEQUENCE_BOOK, 4, 0L), shelf.created.single().anchor)
        assertEquals(shelf.created.single().id, h.sequence.currentSheet)
    }

    @Test fun `a second request while a sheet is being created creates nothing`() {
        val worker = SequenceDeferredExecutor()
        val (h, shelf) = shelfHarness(SequenceFakeDocument(pageCount = 10), initialPage = 2, sheets = emptyList(), worker = worker)
        worker.runAll()
        assertEquals(false, h.reading.creatingSheet)

        h.createSheet()
        h.createSheet()
        assertEquals(true, h.reading.creatingSheet)
        worker.runAll()

        assertEquals(1, shelf.created.size)
        assertEquals(false, h.reading.creatingSheet)
        assertEquals(shelf.created.single().id, h.sequence.currentSheet)

        h.createSheet()
        worker.runAll()
        assertEquals(2, shelf.created.size)
    }

    @Test fun `a sheet that fails to be created leaves the reader where it was`() {
        val (h, shelf) = shelfHarness(SequenceFakeDocument(pageCount = 10), initialPage = 2, sheets = emptyList())
        shelf.failCreate = true

        h.createSheet()

        assertEquals(emptyList<SheetSummary>(), shelf.sheets)
        assertNull(h.sequence.currentSheet)
        assertEquals(SequenceLabel(3, null), h.sequence.currentLabel)
        assertEquals(false, h.reading.creatingSheet)
    }
}
