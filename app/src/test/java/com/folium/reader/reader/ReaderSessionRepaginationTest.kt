package com.folium.reader.reader

import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.DocumentMetadata
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.Raster
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.ReadingPosition
import com.folium.reader.core.pdf.ReadingPositionToken
import com.folium.reader.core.pdf.ReadingPositionTokens
import com.folium.reader.core.pdf.ReflowLayoutBox
import com.folium.reader.core.pdf.ReflowSettings
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.pdf.SchedulerOutcome
import com.folium.reader.core.pdf.ViewportRenderRequest
import com.folium.reader.core.pdf.ViewportRenderer
import com.folium.reader.core.pdf.ViewportScheduler
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
import com.folium.reader.index.DocumentContentVersion
import com.folium.reader.index.TransientTextPageIndex
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [ReaderSession.repaginate]'s state machine — what it returns and what it swaps — against
 * fakes on the host JVM.
 *
 * What these tests do NOT prove: that phase A (closing the outgoing presenter on the presenter
 * thread, done by [ReaderHostController] before it ever calls this method) and phase B (the drain
 * this method performs next) are ordered so that no worker thread can ever reach a page that phase C
 * is about to change out from under it. That is an absence-of-a-race argument about native memory
 * that a single-process JVM test cannot observe either way — it is a manual-review invariant, backed
 * by [ViewportScheduler.close]'s own drain guarantee and by never touching `RenderCookie` or
 * `RenderAbortWatcher`, not by anything asserted here.
 */
class ReaderSessionRepaginationTest {

    private val box = ReflowLayoutBox(450f, 675f, 22f)
    private val settings = ReflowSettings(box, "")

    @Test fun `a drain that times out yields Abandoned and leaves the presenter, page count and text loader untouched`() {
        val document = SessionRepagFakeDocument(pageCount = 3)
        val blockingRenderer = BlockingRenderer()
        val dispatched = CountDownLatch(1)
        val session = session(document, presenterFactory = { rig ->
            timingOutPresenter(document, rig, blockingRenderer, dispatched)
        })

        // Put one request in flight so the scheduler genuinely has something to fail to drain.
        session.presenter.setViewport(ReaderViewport(600, 800))
        assertTrue(dispatched.await(2, TimeUnit.SECONDS))
        val presenterBeforeRepaginate = session.presenter

        val result = session.repaginate(settings, token = null)

        assertEquals(RepaginationResult.Abandoned, result)
        assertSame(presenterBeforeRepaginate, session.presenter)
        assertEquals(3, session.pageCount)
        blockingRenderer.release()
    }

    @Test fun `a superseded request returns Superseded and lays nothing out`() {
        val document = SessionRepagFakeDocument(pageCount = 3)
        val session = session(document)

        val result = session.repaginate(settings, token = null, isCurrent = { false })

        assertEquals(RepaginationResult.Superseded, result)
        assertEquals(3, session.pageCount)
        assertFalse(document.relayoutCalled)
    }

    @Test fun `a token that will not resolve falls back to the clamped stored index and reports unresolved`() {
        val document = SessionRepagFakeDocument(pageCount = 3, relayoutPageCount = 2, resolve = { null })
        val session = session(document, initialPage = 2)

        val result = session.repaginate(settings, token = null) as RepaginationResult.Repaginated

        assertFalse(result.resolved)
        assertEquals(1, result.pageIndex) // clamped from stored page 2 into the new page count of 2
        assertEquals(2, result.pageCount)
    }

    @Test fun `a token whose document scope does not match is treated as unresolvable`() {
        val document = SessionRepagFakeDocument(pageCount = 3, relayoutPageCount = 4, resolve = { 3 })
        val session = session(document, initialPage = 0, documentScope = "aaaaaaaaaaaaaaaa")
        val foreignToken = ReadingPositionTokens.rescope(
            ReadingPositionTokens.mintPosition(ReadingPosition(0, 0)),
            "bbbbbbbbbbbbbbbb"
        )

        val result = session.repaginate(settings, token = foreignToken) as RepaginationResult.Repaginated

        assertFalse(result.resolved)
        assertEquals(0, result.pageIndex)
    }

    @Test fun `a token that resolves reports the resolved page and a fresh token scoped to this document`() {
        val document = SessionRepagFakeDocument(pageCount = 3, relayoutPageCount = 5, resolve = { 4 })
        val session = session(document, initialPage = 0, documentScope = "aaaaaaaaaaaaaaaa")
        val inner = ReadingPositionTokens.mintPosition(ReadingPosition(0, 0))
        val token = ReadingPositionTokens.rescope(inner, "aaaaaaaaaaaaaaaa")

        val result = session.repaginate(settings, token = token) as RepaginationResult.Repaginated

        assertTrue(result.resolved)
        assertEquals(4, result.pageIndex)
        assertEquals(5, result.pageCount)
        assertNotNull(result.token)
        assertEquals(
            "aaaaaaaaaaaaaaaa",
            ReadingPositionTokens.unscope(result.token!!, "aaaaaaaaaaaaaaaa")?.let { "aaaaaaaaaaaaaaaa" }
        )
    }

    @Test fun `repaginate rebuilds the presenter at the new page count and resolved page`() {
        val document = SessionRepagFakeDocument(pageCount = 3, relayoutPageCount = 7, resolve = { 6 })
        val session = session(document, initialPage = 0, documentScope = "aaaaaaaaaaaaaaaa")
        val inner = ReadingPositionTokens.mintPosition(ReadingPosition(0, 0))
        val token = ReadingPositionTokens.rescope(inner, "aaaaaaaaaaaaaaaa")
        val presenterBefore = session.presenter

        session.repaginate(settings, token = token)

        assertTrue(session.presenter !== presenterBefore)
        assertEquals(7, session.presenter.pageCount)
        assertEquals(6, session.presenter.uiState.state.currentPage)
    }

    @Test fun `a non-reflowable document is left alone`() {
        val document = SessionRepagFakeDocument(pageCount = 3, reflowable = false)
        val session = session(document)
        val presenterBefore = session.presenter

        val result = session.repaginate(settings, token = null)

        assertEquals(RepaginationResult.Abandoned, result)
        assertSame(presenterBefore, session.presenter)
    }

    @Test fun `the OCR pipeline is not started for a reflowable document`() {
        val document = SessionRepagFakeDocument(pageCount = 3)
        var engineCreations = 0
        session(document, ocrEngineFactory = { engineCreations++; error("must not be created") })

        assertEquals(0, engineCreations)
    }

    private fun session(
        document: SessionRepagFakeDocument,
        initialPage: Int = 0,
        documentScope: String? = "aaaaaaaaaaaaaaaa",
        ocrEngineFactory: (() -> com.folium.reader.core.ocr.OcrEngine)? = null,
        presenterFactory: (RepaginationRig) -> ReaderPresenter<BorrowedPage> = { rig -> quietPresenter(document, rig, initialPage) }
    ): ReaderSession {
        val cache = com.folium.reader.core.pdf.ByteBoundedPageCache<RenderedPage>(64L * 1024 * 1024)
        val textIndex = TransientTextPageIndex()
        val rig = RepaginationRig(
            cache = cache,
            priorityGate = DocumentPriorityGate(),
            cacheBudgetBytes = 64L * 1024 * 1024,
            mainPost = { it() },
            scheduleRetry = { _, _ -> },
            onChanged = {},
            textIndex = textIndex,
            documentVersion = DocumentContentVersion("ab".repeat(32)),
            nativeEngineVersion = TextEngineVersion("native-v1")
        )
        val readerDocument = ReaderDocument(
            document, BookId("book"), document.pageCount, emptyList(), TextEngineVersion("native-v1"),
            firstPageAspect = 1f, initialPage = initialPage, initialPageAspect = null
        )
        val presenter = presenterFactory(rig)
        val lifecycle = ReaderSessionLifecycle(
            unregisterCallbacks = {}, closeTextLoader = {}, closePresenter = {}, shutdownPresenter = {},
            disposeTextLoader = {}, closeTextIndex = textIndex::close, clearPageCache = cache::clear,
            closeDocument = readerDocument::close
        )
        return ReaderSession(
            readerDocument,
            SilentTextLoader,
            lifecycle,
            ocrEngineFactory,
            OcrPipelineDispatch(),
            OcrStatusDispatch(),
            SearchOcrStatusDispatch(),
            DocumentPriorityGate(),
            presenter,
            noOpThumbnailPipeline(),
            documentScope,
            rig
        )
    }

    private fun quietPresenter(
        document: SessionRepagFakeDocument,
        rig: RepaginationRig,
        initialPage: Int
    ): ReaderPresenter<BorrowedPage> {
        fun scheduler(onOutcome: (SchedulerOutcome<BorrowedPage>) -> Unit) = ViewportScheduler(
            1, SessionRepagEmptyRenderer(), workerPoolName = "repagination-test", onOutcome = onOutcome
        )
        return ReaderPresenter(
            pageCount = document.pageCount, cacheBudgetBytes = rig.cacheBudgetBytes,
            releaseValue = BorrowedPage::release, pageAspect = { 1f }, scheduleRetry = rig.scheduleRetry,
            deliverToPresenter = rig.mainPost, onChanged = rig.onChanged, initialPage = initialPage,
            baseSchedulerFactory = ::scheduler, schedulerFactory = ::scheduler
        )
    }

    private fun timingOutPresenter(
        document: SessionRepagFakeDocument,
        rig: RepaginationRig,
        renderer: BlockingRenderer,
        dispatched: CountDownLatch
    ): ReaderPresenter<BorrowedPage> {
        fun scheduler(onOutcome: (SchedulerOutcome<BorrowedPage>) -> Unit) = ViewportScheduler(
            1, renderer.also { it.onDispatch = dispatched::countDown },
            closeDrainTimeoutMillis = 50L, workerPoolName = "repagination-timeout-test", onOutcome = onOutcome
        )
        return ReaderPresenter(
            pageCount = document.pageCount, cacheBudgetBytes = rig.cacheBudgetBytes,
            releaseValue = BorrowedPage::release, pageAspect = { 1f }, scheduleRetry = rig.scheduleRetry,
            deliverToPresenter = rig.mainPost, onChanged = rig.onChanged, initialPage = 0,
            baseSchedulerFactory = ::scheduler, schedulerFactory = ::scheduler
        )
    }
}

private class SessionRepagEmptyRenderer : ViewportRenderer<BorrowedPage> {
    override fun render(
        request: ViewportRenderRequest,
        cancellationSignal: CancellationSignal
    ): RenderCandidate<BorrowedPage> = error("no render expected")
}

/** Never returns until [release] is called, so [ViewportScheduler.close] genuinely has to wait. */
private class BlockingRenderer : ViewportRenderer<BorrowedPage> {
    var onDispatch: () -> Unit = {}
    private val gate = CountDownLatch(1)

    fun release() = gate.countDown()

    override fun render(
        request: ViewportRenderRequest,
        cancellationSignal: CancellationSignal
    ): RenderCandidate<BorrowedPage> {
        onDispatch()
        gate.await(5, TimeUnit.SECONDS)
        error("render must not complete inside the test")
    }
}

private object SilentTextLoader : SessionTextLoader {
    override fun load(pageIndex: Int, callback: (TextPageLoadResult) -> Unit) = Unit
    override fun search(spec: com.folium.reader.core.text.TextSearchSpec, callback: (TextSearchProgress) -> Unit) = Unit
    override fun setProgressiveOcrActive(active: Boolean) = Unit
    override fun closeSearch() = Unit
    override fun ocrStatus(pageIndex: Int, callback: (OcrCommandResult<com.folium.reader.core.ocr.OcrPageStatus?>) -> Unit) = Unit
    override fun close() = Unit
    override fun dispose() = Unit
}

private class SessionRepagFakeDocument(
    pageCount: Int,
    private val relayoutPageCount: Int = pageCount,
    private val resolve: (ReadingPositionToken) -> Int? = { null },
    override val reflowable: Boolean = true
) : PdfDocument {
    var pageCountField = pageCount
    override val pageCount: Int get() = pageCountField
    var relayoutCalled = false
        private set

    override fun pageInfo(index: Int) = PageInfo(index, 1f, 1f, 0)
    override fun buildDisplayList(index: Int): DisplayList = error("no display list expected")
    override fun extractText(index: Int) = TextPage(emptyList(), TextSource.NATIVE_PDF)
    override fun outline(): List<OutlineEntry> = emptyList()
    override fun metadata() = DocumentMetadata.NONE

    override fun makePositionToken(pageIndex: Int): ReadingPositionToken =
        ReadingPositionTokens.mintPosition(ReadingPosition(pageIndex, 0))

    override fun resolvePositionToken(token: ReadingPositionToken): Int? = resolve(token)

    override fun relayout(settings: ReflowSettings): Boolean {
        if (!reflowable) return false
        relayoutCalled = true
        pageCountField = relayoutPageCount
        return true
    }

    override fun close() = Unit
}
