package com.folium.reader.reader

import android.content.Context
import android.content.ContextWrapper
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.library.TwoPageSpreadPreferences
import com.folium.reader.core.pdf.HorizontalViewportState
import com.folium.reader.library.OpenBookRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

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
}
