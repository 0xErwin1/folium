package com.folium.reader.library

import android.graphics.Bitmap
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.R
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.ImportFailure
import com.folium.reader.core.library.ImportOutcome
import com.folium.reader.core.library.ImportProgress
import com.folium.reader.core.library.ImportReport
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.library.LibraryHomeState
import com.folium.reader.core.library.RecoveryReason
import com.folium.reader.core.library.ShelfEntry
import com.folium.reader.ui.FoliumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A behavioural smoke over the rewritten home screen, covering each state it can render. The full
 * row-anatomy and layout coverage the design asks for belongs to T26, which runs on the emulator
 * with the rest of the instrumented bulk.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val report = book("8fa1", "Quarterly report.pdf", pageCount = 200)
    private val manual = book("2c07", "Field manual.pdf", pageCount = 8)

    private val opened = mutableListOf<BookId>()
    private val removed = mutableListOf<BookId>()
    private var addCalls = 0
    private var dismissCalls = 0

    @Test fun an_empty_shelf_invites_a_first_book() {
        render(LibraryHomeState.Shelf(emptyList()))

        compose.onNodeWithTag(LibraryTestTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_home_empty_title)).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.BOOKS).assertDoesNotExist()

        compose.onNodeWithTag(LibraryTestTags.ADD).assertIsDisplayed().performClick()
        assertEquals(1, addCalls)
    }

    @Test fun a_row_shows_its_title_and_position_and_opens_by_book_id() {
        render(LibraryHomeState.Shelf(listOf(ShelfEntry(report, 49), ShelfEntry(manual, 0))))

        compose.onNodeWithTag(LibraryTestTags.BOOKS).assertIsDisplayed()
        compose.onNodeWithText(report.title).assertIsDisplayed()
        compose.onNodeWithText(progress(50, 200, 25)).assertIsDisplayed()
        compose.onNodeWithText(progress(1, 8, 13)).assertIsDisplayed()

        compose.onNodeWithTag(LibraryTestTags.book(report.id)).performClick()
        assertEquals(listOf(report.id), opened)
    }

    @Test fun removing_a_book_is_gated_by_a_confirmation() {
        render(LibraryHomeState.Shelf(listOf(ShelfEntry(report, 49))))

        compose.onNodeWithTag(LibraryTestTags.removeBook(report.id)).performClick()
        compose.onNodeWithTag(LibraryTestTags.REMOVE_CONFIRM).assertIsDisplayed()
        assertEquals(emptyList<BookId>(), removed)

        compose.onNodeWithText(string(R.string.library_remove_confirm_action)).performClick()
        assertEquals(listOf(report.id), removed)
    }

    @Test fun an_import_in_flight_names_its_progress_and_holds_back_every_open() {
        render(LibraryHomeState.Shelf(listOf(ShelfEntry(report, 49)), importing = ImportProgress(0, 2)))

        compose.onNodeWithTag(LibraryTestTags.IMPORTING).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_importing, 0, 2)).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.ADD).assertIsNotEnabled()

        compose.onNodeWithTag(LibraryTestTags.book(report.id)).performClick()
        assertEquals(emptyList<BookId>(), opened)
    }

    @Test fun a_batch_report_names_every_file_that_failed_and_why() {
        val failure = ImportOutcome.Failed("broken.pdf", ImportFailure.SourceUnavailable(RecoveryReason.SourceMissing))
        val state = LibraryHomeState.Shelf(
            entries = listOf(ShelfEntry(report, 0)),
            report = ImportReport(listOf(ImportOutcome.Imported("ok.pdf", report), failure))
        )
        render(state)

        compose.onNodeWithTag(LibraryTestTags.IMPORT_REPORT).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_import_summary_mixed, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText("broken.pdf").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.import_failure_source_missing)).assertIsDisplayed()

        compose.onNodeWithTag(LibraryTestTags.IMPORT_REPORT_DISMISS).performClick()
        assertEquals(1, dismissCalls)
    }

    @Test fun loading_shows_no_stale_shelf() {
        render(LibraryHomeState.Loading)

        compose.onNodeWithTag(LibraryTestTags.LOADING).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.BOOKS).assertDoesNotExist()
        compose.onNodeWithTag(LibraryTestTags.EMPTY).assertDoesNotExist()
    }

    @Test fun the_add_entry_point_stays_reachable_while_books_are_listed() {
        render(LibraryHomeState.Shelf(listOf(ShelfEntry(report, 0))))

        compose.onNodeWithTag(LibraryTestTags.ADD).assertIsEnabled().assertHasClickAction().performClick()
        assertEquals(1, addCalls)
    }

    private fun render(state: LibraryHomeState, thumbnailFor: (BookId) -> Bitmap? = { null }) {
        compose.setContent {
            FoliumTheme {
                LibraryScreen(
                    state = state,
                    thumbnailFor = thumbnailFor,
                    onAddBooks = { addCalls++ },
                    onOpenBook = { opened += it },
                    onRemoveBook = { removed += it },
                    onDismissReport = { dismissCalls++ }
                )
            }
        }
    }

    private fun progress(page: Int, of: Int, percent: Int): String =
        context.getString(R.string.library_book_progress, page, of, percent)

    private fun string(id: Int): String = context.getString(id)

    private fun book(id: String, title: String, pageCount: Int) =
        LibraryBook(BookId(id), title, pageCount, addedAtMillis = 1_000L)
}
