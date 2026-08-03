package com.folium.reader.library

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.R
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.ImportOutcome
import com.folium.reader.core.library.ImportReport
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.library.LibraryHomeState
import com.folium.reader.core.library.ShelfEntry
import com.folium.reader.perf.ProbedComposition
import com.folium.reader.perf.RecompositionProbe
import com.folium.reader.ui.FoliumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the claim the library model's declared stability actually makes: a home state that changes
 * around the shelf re-runs no row at all, and one where a single book moved re-runs only that
 * book's row. Both are invisible to every other test in this suite — a shelf that recomposes
 * wholesale renders identically — so without these the declaration could be dropped silently.
 *
 * Each state published here is a distinct object carrying freshly built entries, since a state
 * equal to the one on screen is dropped by the state holder itself and never reaches composition:
 * what is under test is whether a row can tell it has nothing new to do, not whether the same
 * object arrives twice.
 *
 * Each row's body is counted through the position line it resolves, which carries the book's own
 * page count and is resolved nowhere else. See [RecompositionProbe].
 *
 * Every callback is held once rather than written at the call site: a lambda rebuilt on each
 * recomposition is not equal to the previous one and would stop a row from skipping for reasons
 * that have nothing to do with the model.
 */
@RunWith(AndroidJUnit4::class)
class LibraryRowRecompositionTest {

    @get:Rule val compose = createComposeRule()

    private val probe = RecompositionProbe(InstrumentationRegistry.getInstrumentation().targetContext)

    private val quarterly = book("8fa1", "Quarterly report.pdf", pageCount = 200)
    private val manual = book("2c07", "Field manual.pdf", pageCount = 8)

    private val imported = ImportOutcome.Imported("quarterly.pdf", quarterly)

    private val onAddBooks: () -> Unit = {}
    private val onOpenBook: (BookId) -> Unit = {}
    private val onRemoveBook: (BookId) -> Unit = {}
    private val onDismissReport: () -> Unit = {}

    private val thumbnails: Map<BookId, Bitmap?> = mapOf(quarterly.id to null, manual.id to null)

    private var published by mutableStateOf<LibraryHomeState>(LibraryHomeState.Loading)

    /** An import report is dismissed above a shelf nobody touched, which is a whole new home state. */
    @Test fun a_state_change_that_leaves_every_book_where_it_was_re_executes_no_row() {
        render(shelf(quarterlyPage = 49, manualPage = 0, report = ImportReport(listOf(imported))))

        val quarterlyRow = rowExecutions(quarterly, page = 50, percent = 25)
        val manualRow = rowExecutions(manual, page = 1, percent = 13)
        assertTrue("the probe never saw either row compose", quarterlyRow > 0 && manualRow > 0)

        publish(shelf(quarterlyPage = 49, manualPage = 0, report = null))

        assertEquals(
            "the untouched quarterly row re-executed",
            quarterlyRow,
            rowExecutions(quarterly, page = 50, percent = 25)
        )
        assertEquals("the untouched manual row re-executed", manualRow, rowExecutions(manual, page = 1, percent = 13))
    }

    @Test fun a_shelf_where_one_book_moved_re_executes_only_that_book_s_row() {
        render(shelf(quarterlyPage = 49, manualPage = 0))

        val quarterlyRow = rowExecutions(quarterly, page = 50, percent = 25)
        val manualRow = rowExecutions(manual, page = 1, percent = 13)
        assertTrue("the probe never saw either row compose", quarterlyRow > 0 && manualRow > 0)

        publish(shelf(quarterlyPage = 50, manualPage = 0))

        assertTrue(
            "the row whose progress changed did not re-execute",
            rowExecutions(quarterly, page = 51, percent = 26) > 0
        )
        assertEquals("the row that did not change re-executed", manualRow, rowExecutions(manual, page = 1, percent = 13))
    }

    /**
     * A row's position line is the one formatted resource left in its body, and it carries the row's
     * own page count, so it tells one row from the other. A row that moved resolves it with its new
     * page, which is why the moved row is counted at the position it moved to.
     */
    private fun rowExecutions(book: LibraryBook, page: Int, percent: Int): Int =
        probe.lookups(R.string.library_book_progress, page, book.pageCount, percent)

    private fun shelf(quarterlyPage: Int, manualPage: Int, report: ImportReport? = null) = LibraryHomeState.Shelf(
        entries = listOf(ShelfEntry(quarterly, quarterlyPage), ShelfEntry(manual, manualPage)),
        report = report
    )

    private fun publish(state: LibraryHomeState) {
        compose.runOnIdle { published = state }
        compose.waitForIdle()
    }

    private fun render(state: LibraryHomeState) {
        published = state

        compose.setContent {
            FoliumTheme {
                ProbedComposition(probe) {
                    LibraryScreen(
                        state = published,
                        thumbnails = thumbnails,
                        onAddBooks = onAddBooks,
                        onOpenBook = onOpenBook,
                        onRemoveBook = onRemoveBook,
                        onDismissReport = onDismissReport
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun book(id: String, title: String, pageCount: Int) =
        LibraryBook(BookId(id), title, pageCount, addedAtMillis = 1_000L)
}
