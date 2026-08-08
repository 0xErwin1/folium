package com.folium.reader.reader

import android.content.Context
import android.content.ContextWrapper
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.pdf.HorizontalViewportState
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
import com.folium.reader.library.OpenBookRequest
import org.junit.Assert.assertEquals
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
}
