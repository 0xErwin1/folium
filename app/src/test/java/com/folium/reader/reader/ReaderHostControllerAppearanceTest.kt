package com.folium.reader.reader

import android.content.Context
import android.content.ContextWrapper
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.DocumentMetadata
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.ReadingPosition
import com.folium.reader.core.pdf.ReadingPositionToken
import com.folium.reader.core.pdf.ReadingPositionTokens
import com.folium.reader.core.pdf.ReflowPageColors
import com.folium.reader.core.pdf.ReflowSettings
import com.folium.reader.core.pdf.SchedulerOutcome
import com.folium.reader.core.pdf.ViewportRenderRequest
import com.folium.reader.core.pdf.ViewportRenderer
import com.folium.reader.core.pdf.ViewportScheduler
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
import com.folium.reader.index.DocumentContentVersion
import com.folium.reader.index.TransientTextPageIndex
import com.folium.reader.library.OpenBookRequest
import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class AppearanceDirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

private val darkColors = ReflowPageColors(foregroundHex = "F2F2F2", backgroundHex = "0B0B0B", accentHex = "D9543C")

/**
 * Exercises [ReaderHostController]'s appearance-mode wiring: the stylesheet the controller builds
 * for a re-pagination or for an open carries the colours a resolved appearance mode supplies, is
 * skipped entirely for a fixed-layout document, and re-triggers when the appearance colours change
 * under an already-open reflowable document.
 */
class ReaderHostControllerAppearanceTest {
    private val context: Context = ContextWrapper(null)

    private fun request(reflowable: Boolean = true) = OpenBookRequest(
        book = LibraryBook(BookId("book-1"), "Title", pageCount = 3, addedAtMillis = 0L),
        file = File(if (reflowable) "/does/not/matter.epub" else "/does/not/matter.pdf"),
        initialPage = 0
    )

    @Test fun `an open with resolved appearance colours carries them into the stylesheet`() {
        val document = AppearanceFakeDocument(reflowable = true)
        val controller = ReaderHostController(
            context, request(), {}, {}, worker = AppearanceDirectExecutor(), mainPost = { it() },
            openSession = { _, _, onChanged -> ReaderSessionResult.Opened(fakeSession(document, onChanged)) },
            initialPageColors = darkColors
        )

        controller.start()

        val settings = document.relayoutCalls.single()
        assertTrue(settings.userCss.contains("#0b0b0b"))
        assertTrue(settings.userCss.contains("#f2f2f2"))
    }

    @Test fun `an open with no appearance colours carries no colours into the stylesheet`() {
        val document = AppearanceFakeDocument(reflowable = true)
        val controller = ReaderHostController(
            context, request(), {}, {}, worker = AppearanceDirectExecutor(), mainPost = { it() },
            openSession = { _, _, onChanged -> ReaderSessionResult.Opened(fakeSession(document, onChanged)) },
            initialPageColors = null
        )

        controller.start()

        assertTrue(document.relayoutCalls.isEmpty())
    }

    @Test fun `a fixed-layout document never sees a colour or a re-pagination request`() {
        val document = AppearanceFakeDocument(reflowable = false)
        val controller = ReaderHostController(
            context, request(reflowable = false), {}, {}, worker = AppearanceDirectExecutor(), mainPost = { it() },
            openSession = { _, _, onChanged -> ReaderSessionResult.Opened(fakeSession(document, onChanged)) },
            initialPageColors = darkColors
        )

        controller.start()

        assertTrue(document.relayoutCalls.isEmpty())
    }

    @Test fun `a changed appearance re-paginates an already open reflowable document`() {
        val document = AppearanceFakeDocument(reflowable = true)
        val controller = ReaderHostController(
            context, request(), {}, {}, worker = AppearanceDirectExecutor(), mainPost = { it() },
            openSession = { _, _, onChanged -> ReaderSessionResult.Opened(fakeSession(document, onChanged)) },
            initialPageColors = null
        )

        controller.start()
        assertTrue(document.relayoutCalls.isEmpty())

        controller.setAppearanceColors(darkColors)

        val settings = document.relayoutCalls.single()
        assertTrue(settings.userCss.contains("#0b0b0b"))
    }

    @Test fun `re-supplying the same appearance colours does not re-paginate again`() {
        val document = AppearanceFakeDocument(reflowable = true)
        val controller = ReaderHostController(
            context, request(), {}, {}, worker = AppearanceDirectExecutor(), mainPost = { it() },
            openSession = { _, _, onChanged -> ReaderSessionResult.Opened(fakeSession(document, onChanged)) },
            initialPageColors = darkColors
        )

        controller.start()
        assertEquals(1, document.relayoutCalls.size)

        controller.setAppearanceColors(darkColors)

        assertEquals(1, document.relayoutCalls.size)
    }

    private fun fakeSession(
        document: AppearanceFakeDocument,
        onChanged: (ReaderUiState<BorrowedPage>) -> Unit
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
        fun scheduler(onOutcome: (SchedulerOutcome<BorrowedPage>) -> Unit) =
            ViewportScheduler(1, AppearanceEmptyRenderer(), onOutcome = onOutcome)
        val presenter = ReaderPresenter(
            pageCount = document.pageCount, cacheBudgetBytes = rig.cacheBudgetBytes,
            releaseValue = BorrowedPage::release, pageAspect = { 1f }, scheduleRetry = rig.scheduleRetry,
            deliverToPresenter = rig.mainPost, onChanged = onChanged, initialPage = 0,
            baseSchedulerFactory = ::scheduler, schedulerFactory = ::scheduler
        )
        val lifecycle = ReaderSessionLifecycle(
            unregisterCallbacks = {}, closeTextLoader = {}, closePresenter = {}, shutdownPresenter = {},
            disposeTextLoader = {}, closeTextIndex = textIndex::close, clearPageCache = cache::clear,
            closeDocument = readerDocument::close
        )
        return ReaderSession(
            readerDocument, AppearanceSilentTextLoader, lifecycle, null, OcrPipelineDispatch(),
            OcrStatusDispatch(), SearchOcrStatusDispatch(), DocumentPriorityGate(), presenter,
            "aaaaaaaaaaaaaaaa", if (document.reflowable) rig else null
        )
    }
}

private object AppearanceSilentTextLoader : SessionTextLoader {
    override fun load(pageIndex: Int, callback: (TextPageLoadResult) -> Unit) = Unit
    override fun close() = Unit
    override fun dispose() = Unit
}

private class AppearanceEmptyRenderer : ViewportRenderer<BorrowedPage> {
    override fun render(
        request: ViewportRenderRequest,
        cancellationSignal: CancellationSignal
    ): RenderCandidate<BorrowedPage> = error("no render expected")
}

private class AppearanceFakeDocument(override val reflowable: Boolean) : PdfDocument {
    val relayoutCalls = mutableListOf<ReflowSettings>()
    override val pageCount: Int = 3

    override fun pageInfo(index: Int) = PageInfo(index, 1f, 1f, 0)
    override fun buildDisplayList(index: Int): DisplayList = error("no display list expected")
    override fun extractText(index: Int) = TextPage(emptyList(), TextSource.NATIVE_PDF)
    override fun outline(): List<OutlineEntry> = emptyList()
    override fun metadata() = DocumentMetadata.NONE

    override fun makePositionToken(pageIndex: Int): ReadingPositionToken =
        ReadingPositionTokens.mintPosition(ReadingPosition(pageIndex, 0))

    override fun resolvePositionToken(token: ReadingPositionToken): Int? = null

    override fun relayout(settings: ReflowSettings): Boolean {
        relayoutCalls += settings
        return true
    }

    override fun close() = Unit
}
