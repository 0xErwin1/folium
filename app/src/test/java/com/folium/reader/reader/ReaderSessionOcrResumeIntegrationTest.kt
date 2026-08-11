package com.folium.reader.reader

import com.folium.reader.core.library.BookId
import com.folium.reader.core.ocr.OcrCancellationReason
import com.folium.reader.core.ocr.OcrEngine
import com.folium.reader.core.ocr.OcrException
import com.folium.reader.core.ocr.OcrFailure
import com.folium.reader.core.ocr.OcrRequest
import com.folium.reader.core.ocr.PageImage
import com.folium.reader.core.ocr.OcrPageState
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.Raster
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.pdf.SchedulerOutcome
import com.folium.reader.core.pdf.ViewportRenderRequest
import com.folium.reader.core.pdf.ViewportRenderer
import com.folium.reader.core.pdf.ViewportScheduler
import com.folium.reader.core.text.NATIVE_TEXT_USABILITY_POLICY_VERSION
import com.folium.reader.core.text.TextBlock
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextLine
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSearchSpec
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextWord
import com.folium.reader.index.DocumentContentVersion
import com.folium.reader.index.OcrAttempt
import com.folium.reader.index.OcrPageKey
import com.folium.reader.index.OcrTransition
import com.folium.reader.index.TextPageIndex
import com.folium.reader.index.TextPageIndexKey
import com.folium.reader.index.TransientTextPageIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReaderSessionOcrResumeIntegrationTest {
    @Test fun immediateSearchReopenResumesPausedAttemptWithoutLateGenerationOverwrite() {
        val firstRecognition = CountDownLatch(1)
        val cancellationEntered = CountDownLatch(1)
        val allowCancellation = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val delegate = preparedIndex(OcrCancellationReason.SEARCH_PAUSE, active = false)
        val index = ControlledIndex(delegate, cancellationEntered, allowCancellation, completed)
        val pdf = TestPdfDocument()
        val loader = textLoader(index, pdf)
        val recognitionCalls = AtomicInteger()
        val session = session(loader, index, pdf) {
            object : TestOcrEngine() {
                override fun recognize(
                    image: PageImage,
                    request: OcrRequest,
                    cancellationSignal: CancellationSignal
                ): TextPage {
                    if (recognitionCalls.getAndIncrement() == 0) {
                        firstRecognition.countDown()
                        while (!cancellationSignal.isCancelled()) Thread.yield()
                        throw OcrException(OcrFailure.Cancelled)
                    }
                    return ocrPage("resumed")
                }
            }
        }
        session.searchText(TextSearchSpec("missing")) {}
        assertTrue(firstRecognition.await(2, TimeUnit.SECONDS))

        session.closeSearch()
        assertTrue(cancellationEntered.await(2, TimeUnit.SECONDS))
        session.searchText(TextSearchSpec("missing")) {}
        allowCancellation.countDown()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        val paused = requireNotNull(index.pausedStatus)
        val final = requireNotNull(delegate.ocrStatus(ocrKey))
        assertEquals(OcrPageState.CANCELLED, paused.state)
        assertEquals(OcrCancellationReason.SEARCH_PAUSE, paused.cancellationReason)
        assertEquals(OcrPageState.COMPLETED, final.state)
        assertEquals(paused.generation + 1, final.generation)
        assertEquals("resumed", delegate.loadSelected(nativeKey, ocrKey)?.text)
        assertEquals(2, recognitionCalls.get())
        session.close()
        session.dispose()
    }

    @Test fun terminalUserAndSessionCancellationAreNotResumedBySearch() {
        listOf(OcrCancellationReason.USER, OcrCancellationReason.SESSION).forEach { reason ->
            val resumeChecked = CountDownLatch(1)
            val claimChecked = CountDownLatch(1)
            val allowResume = CountDownLatch(1)
            val delegate = preparedIndex(reason, active = true)
            val index = ControlledIndex(
                delegate,
                resumeChecked = resumeChecked,
                claimChecked = claimChecked,
                allowResume = allowResume
            )
            val pdf = TestPdfDocument()
            val loader = textLoader(index, pdf)
            val engineCreations = AtomicInteger()
            val session = session(loader, index, pdf) {
                engineCreations.incrementAndGet()
                TestOcrEngine()
            }

            session.searchText(TextSearchSpec("missing")) {}
            allowResume.countDown()

            assertTrue(resumeChecked.await(2, TimeUnit.SECONDS))
            assertTrue(claimChecked.await(2, TimeUnit.SECONDS))
            val status = requireNotNull(delegate.ocrStatus(ocrKey))
            assertEquals(OcrPageState.CANCELLED, status.state)
            assertEquals(reason, status.cancellationReason)
            assertEquals(0, engineCreations.get())
            session.close()
            session.dispose()
        }
    }

    private fun session(
        loader: SessionTextLoader,
        index: TextPageIndex,
        pdf: PdfDocument,
        engineFactory: () -> OcrEngine
    ): ReaderSession {
        val document = ReaderDocument(
            pdf, BookId("book"), 1, emptyList(), TextEngineVersion("native-v1"),
            firstPageAspect = 1f, initialPage = 0, initialPageAspect = null
        )
        val presenter = presenter()
        val lifecycle = ReaderSessionLifecycle(
            unregisterCallbacks = {}, closeTextLoader = {}, closePresenter = presenter::close,
            shutdownPresenter = presenter::shutdown, disposeTextLoader = loader::dispose,
            closeTextIndex = index::close, clearPageCache = {}, closeDocument = document::close
        )
        return ReaderSession(
            document, loader, lifecycle, engineFactory, OcrPipelineDispatch(),
            DocumentPriorityGate(), presenter
        )
    }

    private fun presenter(): ReaderPresenter<BorrowedPage> {
        val renderer = object : ViewportRenderer<BorrowedPage> {
            override fun render(
                request: ViewportRenderRequest,
                cancellationSignal: CancellationSignal
            ): RenderCandidate<BorrowedPage> = error("no viewport requests expected")
        }
        fun scheduler(onOutcome: (SchedulerOutcome<BorrowedPage>) -> Unit) =
            ViewportScheduler(1, renderer, workerPoolName = "session-ocr-test", onOutcome = onOutcome)
        return ReaderPresenter(
            pageCount = 1, releaseValue = BorrowedPage::release, pageAspect = { 1f },
            scheduleRetry = { _, _ -> }, deliverToPresenter = { it() }, onChanged = {},
            baseSchedulerFactory = ::scheduler, schedulerFactory = ::scheduler
        )
    }
}

private class ControlledIndex(
    private val delegate: TransientTextPageIndex,
    private val cancellationEntered: CountDownLatch? = null,
    private val allowCancellation: CountDownLatch? = null,
    private val completed: CountDownLatch? = null,
    private val resumeChecked: CountDownLatch? = null,
    private val claimChecked: CountDownLatch? = null,
    private val allowResume: CountDownLatch? = null
) : TextPageIndex by delegate {
    @Volatile var pausedStatus: com.folium.reader.core.ocr.OcrPageStatus? = null

    override fun cancelOcr(
        attempt: OcrAttempt,
        reason: OcrCancellationReason
    ): OcrTransition {
        cancellationEntered?.countDown()
        check(allowCancellation?.await(2, TimeUnit.SECONDS) != false)
        return delegate.cancelOcr(attempt, reason).also {
            pausedStatus = delegate.ocrStatus(attempt.key)
        }
    }

    override fun resumePausedOcr(key: OcrPageKey): OcrTransition {
        check(allowResume?.await(2, TimeUnit.SECONDS) != false)
        return delegate.resumePausedOcr(key).also { resumeChecked?.countDown() }
    }

    override fun claimOcr(key: OcrPageKey): OcrTransition =
        delegate.claimOcr(key).also { claimChecked?.countDown() }

    override fun completeOcr(attempt: OcrAttempt, page: TextPage): OcrTransition =
        delegate.completeOcr(attempt, page).also { completed?.countDown() }
}

private open class TestOcrEngine : OcrEngine {
    override fun textEngineVersion(request: OcrRequest) = TextEngineVersion("ocr-v1")
    override fun recognize(image: PageImage, request: OcrRequest, cancellationSignal: CancellationSignal) =
        ocrPage("recognized")
    override fun close() = Unit
}

private class TestPdfDocument : PdfDocument {
    override val pageCount = 1
    override fun pageInfo(index: Int) = PageInfo(index, 1f, 1f, 0)
    override fun buildDisplayList(index: Int) = object : DisplayList {
        override fun render(spec: RenderSpec, cancellationSignal: CancellationSignal) =
            Raster(spec.width, spec.height, ByteArray(spec.width * spec.height * 4))
        override fun close() = Unit
    }
    override fun extractText(index: Int) = TextPage(emptyList(), TextSource.NATIVE_PDF)
    override fun outline(): List<OutlineEntry> = emptyList()
    override fun close() = Unit
}

private fun textLoader(index: TextPageIndex, document: PdfDocument) = TextPageLoader(
    document, 1, deliver = { it() }, index = index,
    indexKey = { nativeKey }, ocrKey = { ocrKey }
)

private fun preparedIndex(reason: OcrCancellationReason, active: Boolean): TransientTextPageIndex =
    TransientTextPageIndex().also { index ->
        index.prepareDocument(nativeKey.bookId, nativeKey.documentVersion)
        index.prepareSource(
            nativeKey.bookId, nativeKey.documentVersion, nativeKey.source,
            nativeKey.textSchemaVersion, nativeKey.engineVersion
        )
        index.prepareOcr(ocrKey)
        index.completeNativeAndReconcile(nativeKey, TextPage(emptyList(), TextSource.NATIVE_PDF), ocrKey)
        if (active) {
            val attempt = requireNotNull(index.claimOcr(ocrKey).attempt)
            index.cancelOcr(attempt, reason)
        }
    }

private val nativeKey = TextPageIndexKey(
    BookId("book"), DocumentContentVersion("ab".repeat(32)), 0,
    TextSource.NATIVE_PDF, 2, TextEngineVersion("native-v1")
)

private val ocrKey = OcrPageKey(
    nativeKey.bookId, nativeKey.documentVersion, 0, nativeKey.textSchemaVersion,
    nativeKey.engineVersion, NATIVE_TEXT_USABILITY_POLICY_VERSION, TextEngineVersion("ocr-v1")
)

private fun ocrPage(text: String) = TextPage(
    listOf(TextBlock(listOf(TextLine(listOf(
        TextWord(text, PageSpaceRect(0f, 0f, 1f, 1f), 0)
    ), 0)), 0)),
    TextSource.OCR
)
