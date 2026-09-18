package com.folium.reader.reader

import android.content.Context
import android.content.ContextWrapper
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.DocumentMetadata
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.ReadingPosition
import com.folium.reader.core.pdf.ReadingPositionToken
import com.folium.reader.core.pdf.ReadingPositionTokens
import com.folium.reader.core.pdf.ReflowLayoutBox
import com.folium.reader.core.pdf.ReflowSettings
import com.folium.reader.core.pdf.SchedulerOutcome
import com.folium.reader.core.pdf.ViewportRenderRequest
import com.folium.reader.core.pdf.ViewportRenderer
import com.folium.reader.core.pdf.ViewportScheduler
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
import com.folium.reader.index.DocumentContentVersion
import com.folium.reader.index.TransientTextPageIndex
import com.folium.reader.library.OpenBookRequest
import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Test

private class HostRepagDirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

/**
 * Exercises [ReaderHostController.repaginate]'s progress-recording discipline: a page, count and
 * token are only ever handed to [ReaderHostController]'s `recordRepagination` seam together, on
 * [RepaginationResult.Repaginated], and never at all otherwise.
 */
class ReaderHostControllerRepaginationTest {
    private val context: Context = ContextWrapper(null)
    private val box = ReflowLayoutBox(450f, 675f, 22f)
    private val settings = ReflowSettings(box, "")

    private fun request() = OpenBookRequest(
        book = LibraryBook(BookId("book-1"), "Title", pageCount = 3, addedAtMillis = 0L),
        file = File("/does/not/matter.epub"),
        initialPage = 0
    )

    @Test fun `progress is written once as one record on a successful repagination`() {
        val recorded = mutableListOf<Triple<Int, Int, ReadingPositionToken?>>()
        val document = HostRepagFakeDocument(pageCount = 3, relayoutPageCount = 5, resolve = { 4 })
        var controller: ReaderHostController? = null
        controller = ReaderHostController(
            context, request(), {}, {}, worker = HostRepagDirectExecutor(), mainPost = { it() },
            openSession = { _, _, onChanged -> ReaderSessionResult.Opened(fakeSession(document, onChanged)) },
            recordRepagination = { _, page, count, token -> recorded += Triple(page, count, token) }
        )

        controller.start()
        controller.repaginate(settings)

        assertEquals(1, recorded.size)
        assertEquals(4, recorded.single().first)
        assertEquals(5, recorded.single().second)
    }

    @Test fun `progress is not written when the drain abandons the repagination`() {
        val recorded = mutableListOf<Triple<Int, Int, ReadingPositionToken?>>()
        val document = HostRepagFakeDocument(pageCount = 3, relayoutPageCount = 5, resolve = { 4 })
        lateinit var controller: ReaderHostController
        controller = ReaderHostController(
            context, request(), {}, {}, worker = HostRepagDirectExecutor(), mainPost = { it() },
            openSession = { _, _, onChanged ->
                ReaderSessionResult.Opened(fakeSession(document, onChanged, timesOut = true))
            },
            recordRepagination = { _, page, count, token -> recorded += Triple(page, count, token) }
        )

        controller.start()
        controller.repaginate(settings)

        assertEquals(emptyList<Triple<Int, Int, ReadingPositionToken?>>(), recorded)
    }

    @Test fun `progress is not written when a newer request supersedes this one`() {
        val recorded = mutableListOf<Triple<Int, Int, ReadingPositionToken?>>()
        val document = HostRepagFakeDocument(pageCount = 3, relayoutPageCount = 5, resolve = { 4 })
        val deferredWork = mutableListOf<Runnable>()
        val results = mutableListOf<RepaginationResult>()
        lateinit var controller: ReaderHostController
        controller = ReaderHostController(
            context, request(), {}, {}, worker = { command -> deferredWork += command }, mainPost = { it() },
            openSession = { _, _, onChanged -> ReaderSessionResult.Opened(fakeSession(document, onChanged)) },
            recordRepagination = { _, page, count, token -> recorded += Triple(page, count, token) }
        )

        controller.start()
        deferredWork.removeAt(0).run() // the open itself, so `session` is populated before repaginate
        controller.repaginate(settings) { results += it }
        controller.repaginate(settings) { results += it }

        // The first submission is superseded by the second before it ever reaches the layout; the
        // second is the one whose result the caller actually wanted.
        deferredWork[0].run()
        deferredWork[1].run()

        assertEquals(listOf(RepaginationResult.Superseded), results.take(1))
        assertEquals(1, recorded.size)
        assertEquals(4, recorded.single().first)
    }

    private fun fakeSession(
        document: HostRepagFakeDocument,
        onChanged: (ReaderUiState<BorrowedPage>) -> Unit,
        timesOut: Boolean = false
    ): ReaderSession {
        val cache = ByteBoundedPageCache<RenderedPage>(64L * 1024 * 1024)
        val textIndex = TransientTextPageIndex()
        val rig = RepaginationRig(
            cache = cache, priorityGate = DocumentPriorityGate(), cacheBudgetBytes = 64L * 1024 * 1024,
            mainPost = { it() }, scheduleRetry = { _, _ -> }, onChanged = onChanged, textIndex = textIndex,
            documentVersion = DocumentContentVersion("ab".repeat(32)), nativeEngineVersion = TextEngineVersion("native-v1")
        )
        val readerDocument = ReaderDocument(
            document, BookId("book-1"), document.pageCount, emptyList(), TextEngineVersion("native-v1"),
            firstPageAspect = 1f, initialPage = 0, initialPageAspect = null
        )
        fun scheduler(onOutcome: (SchedulerOutcome<BorrowedPage>) -> Unit) = if (timesOut) {
            ViewportScheduler(1, HostRepagBlockingRenderer(), closeDrainTimeoutMillis = 30L, onOutcome = onOutcome)
        } else {
            ViewportScheduler(1, HostRepagEmptyRenderer(), onOutcome = onOutcome)
        }
        val presenter = ReaderPresenter(
            pageCount = document.pageCount, cacheBudgetBytes = rig.cacheBudgetBytes,
            releaseValue = BorrowedPage::release, pageAspect = { 1f }, scheduleRetry = rig.scheduleRetry,
            deliverToPresenter = rig.mainPost, onChanged = onChanged, initialPage = 0,
            baseSchedulerFactory = ::scheduler, schedulerFactory = ::scheduler
        )
        if (timesOut) presenter.setViewport(ReaderViewport(600, 800))
        val lifecycle = ReaderSessionLifecycle(
            unregisterCallbacks = {}, closeTextLoader = {}, closePresenter = {}, shutdownPresenter = {},
            disposeTextLoader = {}, closeTextIndex = textIndex::close, clearPageCache = cache::clear,
            closeDocument = readerDocument::close
        )
        return ReaderSession(
            readerDocument, HostRepagSilentTextLoader, lifecycle, null, OcrPipelineDispatch(),
            OcrStatusDispatch(), SearchOcrStatusDispatch(), DocumentPriorityGate(), presenter,
            noOpThumbnailPipeline(), "aaaaaaaaaaaaaaaa", rig
        )
    }
}

private object HostRepagSilentTextLoader : SessionTextLoader {
    override fun load(pageIndex: Int, callback: (TextPageLoadResult) -> Unit) = Unit
    override fun close() = Unit
    override fun dispose() = Unit
}

private class HostRepagEmptyRenderer : ViewportRenderer<BorrowedPage> {
    override fun render(
        request: ViewportRenderRequest,
        cancellationSignal: CancellationSignal
    ): RenderCandidate<BorrowedPage> = error("no render expected")
}

private class HostRepagBlockingRenderer : ViewportRenderer<BorrowedPage> {
    override fun render(
        request: ViewportRenderRequest,
        cancellationSignal: CancellationSignal
    ): RenderCandidate<BorrowedPage> {
        Thread.sleep(60_000)
        error("render must not complete inside the test")
    }
}

private class HostRepagFakeDocument(
    pageCount: Int,
    private val relayoutPageCount: Int = pageCount,
    private val resolve: (ReadingPositionToken) -> Int? = { null }
) : PdfDocument {
    var pageCountField = pageCount
    override val pageCount: Int get() = pageCountField
    override val reflowable: Boolean = true

    override fun pageInfo(index: Int) = PageInfo(index, 1f, 1f, 0)
    override fun buildDisplayList(index: Int): DisplayList = error("no display list expected")
    override fun extractText(index: Int) = TextPage(emptyList(), TextSource.NATIVE_PDF)
    override fun outline(): List<OutlineEntry> = emptyList()
    override fun metadata() = DocumentMetadata.NONE

    override fun makePositionToken(pageIndex: Int): ReadingPositionToken =
        ReadingPositionTokens.mintPosition(ReadingPosition(pageIndex, 0))

    override fun resolvePositionToken(token: ReadingPositionToken): Int? = resolve(token)

    override fun relayout(settings: ReflowSettings): Boolean {
        pageCountField = relayoutPageCount
        return true
    }

    override fun close() = Unit
}
