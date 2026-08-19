package com.folium.reader.library

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.R
import com.folium.reader.core.library.AppearanceMode
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.ImportFailure
import com.folium.reader.core.library.ImportOutcome
import com.folium.reader.core.library.ImportProgress
import com.folium.reader.core.library.ImportReport
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.library.LibraryHomeState
import com.folium.reader.core.library.LibraryViewMode
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
    private var viewMode by mutableStateOf(LibraryViewMode.LIST)
    private var appearanceMode by mutableStateOf(AppearanceMode.SYSTEM)

    @Test fun an_empty_shelf_invites_a_first_book() {
        render(LibraryHomeState.Shelf(emptyList()))

        compose.onNodeWithTag(LibraryTestTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_home_empty_title)).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.BOOKS).assertDoesNotExist()

        compose.onNodeWithTag(LibraryTestTags.ADD).assertIsDisplayed().performClick()
        assertEquals(1, addCalls)
    }

    /**
     * The empty state's own invitation, which is a second entry point rather than a restatement of
     * the header's: it is the one a reader with nothing on the shelf actually aims at, and the
     * header button being wired says nothing about it.
     */
    @Test fun the_empty_shelf_offers_its_own_way_to_add_a_first_book() {
        render(LibraryHomeState.Shelf(emptyList()))

        compose.onNodeWithTag(LibraryTestTags.EMPTY_ADD).assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals(1, addCalls)
    }

    @Test fun a_row_shows_its_title_and_position_and_opens_by_book_id() {
        render(LibraryHomeState.Shelf(listOf(ShelfEntry(report, 49), ShelfEntry(manual, 0))))

        compose.onNodeWithTag(LibraryTestTags.BOOKS).assertIsDisplayed()
        compose.onNodeWithText(report.title).assertIsDisplayed()
        compose.onNodeWithText(progress(50, 200, 25)).assertIsDisplayed()
        compose.onNodeWithText(progress(1, 8, 13)).assertIsDisplayed()

        compose.onNodeWithTag(LibraryTestTags.bookProgress(report.id), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.bookThumbnail(report.id), useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithTag(LibraryTestTags.book(report.id)).assert(namesItsOpenAction(report.title))

        compose.onNodeWithTag(LibraryTestTags.book(report.id)).performClick()
        assertEquals(listOf(report.id), opened)
    }

    /**
     * The only render that reaches the image branch of a row's thumbnail: every other state here
     * carries no bitmap, so without this one a row that actually has a cover is never composed.
     *
     * A row's own semantics are merged by the click action that opens it, so the thumbnail and the
     * progress bar inside it are addressable only in the unmerged tree.
     */
    @Test fun a_row_with_a_decoded_cover_shows_it_beside_its_progress() {
        val cover = Bitmap.createBitmap(56, 76, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

        render(
            state = LibraryHomeState.Shelf(listOf(ShelfEntry(report, 49), ShelfEntry(manual, 0))),
            thumbnails = mapOf(report.id to cover, manual.id to null)
        )

        compose.onNodeWithTag(LibraryTestTags.bookThumbnail(report.id), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.bookProgress(report.id), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.bookThumbnail(manual.id), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(LibraryTestTags.bookProgress(manual.id), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun the_overflow_menu_switches_the_shelf_between_a_grid_and_a_list() {
        render(LibraryHomeState.Shelf(listOf(ShelfEntry(report, 49), ShelfEntry(manual, 0))))

        compose.onNodeWithTag(LibraryTestTags.VIEW_MENU).performClick()
        compose.onNodeWithTag(LibraryTestTags.VIEW_GRID).performClick()

        compose.onNodeWithTag(LibraryTestTags.BOOKS_GRID).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.BOOKS).assertDoesNotExist()
        compose.onNodeWithTag(LibraryTestTags.gridBook(report.id)).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.book(report.id)).assertDoesNotExist()

        compose.onNodeWithTag(LibraryTestTags.VIEW_MENU).performClick()
        compose.onNodeWithTag(LibraryTestTags.VIEW_LIST).performClick()

        compose.onNodeWithTag(LibraryTestTags.BOOKS).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.BOOKS_GRID).assertDoesNotExist()
        compose.onNodeWithTag(LibraryTestTags.book(report.id)).assertIsDisplayed()
    }

    @Test fun the_overflow_groups_layout_and_appearance_and_marks_the_active_options() {
        render(LibraryHomeState.Shelf(listOf(ShelfEntry(report, 49))))

        compose.onNodeWithTag(LibraryTestTags.VIEW_MENU).performClick()

        compose.onNodeWithText(string(R.string.library_layout)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_appearance)).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.VIEW_LIST).assertIsSelected()
        compose.onNodeWithTag(LibraryTestTags.VIEW_GRID).assertIsNotSelected()
        compose.onNodeWithTag(LibraryTestTags.APPEARANCE_SYSTEM).assertIsSelected()
        compose.onNodeWithTag(LibraryTestTags.APPEARANCE_LIGHT).assertIsNotSelected()
        compose.onNodeWithTag(LibraryTestTags.APPEARANCE_DARK).assertIsNotSelected()
        compose.onNodeWithTag(LibraryTestTags.APPEARANCE_E_INK_LIGHT).assertIsNotSelected()
        compose.onNodeWithTag(LibraryTestTags.APPEARANCE_E_INK_DARK).assertIsNotSelected()
        compose.onNodeWithText(string(R.string.library_appearance_e_ink_light)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_appearance_e_ink_dark)).assertIsDisplayed()
    }

    @Test fun choosing_an_appearance_updates_the_active_menu_option() {
        render(LibraryHomeState.Shelf(listOf(ShelfEntry(report, 49))))

        compose.onNodeWithTag(LibraryTestTags.VIEW_MENU).performClick()
        compose.onNodeWithTag(LibraryTestTags.APPEARANCE_DARK).performClick()

        assertEquals(AppearanceMode.DARK, appearanceMode)
        compose.onNodeWithTag(LibraryTestTags.VIEW_MENU).performClick()
        compose.onNodeWithTag(LibraryTestTags.APPEARANCE_DARK).assertIsSelected()
        compose.onNodeWithTag(LibraryTestTags.APPEARANCE_SYSTEM).assertIsNotSelected()
    }

    @Test fun choosing_e_ink_light_updates_the_active_menu_option_and_semantics() {
        render(LibraryHomeState.Shelf(listOf(ShelfEntry(report, 49))))

        compose.onNodeWithTag(LibraryTestTags.VIEW_MENU).performClick()
        compose.onNodeWithTag(LibraryTestTags.APPEARANCE_E_INK_LIGHT).performClick()

        assertEquals(AppearanceMode.E_INK_LIGHT, appearanceMode)
        compose.onNodeWithTag(LibraryTestTags.VIEW_MENU).performClick()
        compose.onNodeWithTag(LibraryTestTags.APPEARANCE_E_INK_LIGHT).assertIsSelected()
        compose.onNodeWithTag(LibraryTestTags.APPEARANCE_E_INK_DARK).assertIsNotSelected()
    }

    @Test fun choosing_e_ink_dark_updates_the_active_menu_option_and_semantics() {
        render(LibraryHomeState.Shelf(listOf(ShelfEntry(report, 49))))

        compose.onNodeWithTag(LibraryTestTags.VIEW_MENU).performClick()
        compose.onNodeWithTag(LibraryTestTags.APPEARANCE_E_INK_DARK).performClick()

        assertEquals(AppearanceMode.E_INK_DARK, appearanceMode)
        compose.onNodeWithTag(LibraryTestTags.VIEW_MENU).performClick()
        compose.onNodeWithTag(LibraryTestTags.APPEARANCE_E_INK_DARK).assertIsSelected()
        compose.onNodeWithTag(LibraryTestTags.APPEARANCE_E_INK_LIGHT).assertIsNotSelected()
    }

    /**
     * A cell's own click action merges everything inside it, so the cover it draws is addressable
     * only in the unmerged tree — the same shape the rows have.
     */
    @Test fun a_grid_cell_shows_its_cover_its_title_and_where_the_reader_is() {
        val cover = Bitmap.createBitmap(56, 76, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

        render(
            state = LibraryHomeState.Shelf(listOf(ShelfEntry(report, 49), ShelfEntry(manual, 0))),
            thumbnails = mapOf(report.id to cover, manual.id to null),
            initialViewMode = LibraryViewMode.GRID
        )

        compose.onNodeWithText(report.title).assertIsDisplayed()
        compose.onNodeWithText(progress(50, 200, 25)).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.bookThumbnail(report.id), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.bookProgress(report.id), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.bookThumbnail(manual.id), useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithTag(LibraryTestTags.gridBook(report.id)).assert(namesItsOpenAction(report.title))

        compose.onNodeWithTag(LibraryTestTags.gridBook(report.id)).performClick()
        assertEquals(listOf(report.id), opened)
    }

    /**
     * The grid cell carries no remove control of its own: an always-visible destructive button sat
     * inside every hit target on the screen. A long press reaches the same confirmation.
     */
    @Test fun a_book_is_removed_from_the_grid_by_a_long_press_on_its_cover() {
        render(
            state = LibraryHomeState.Shelf(listOf(ShelfEntry(report, 49))),
            initialViewMode = LibraryViewMode.GRID
        )

        compose.onNodeWithTag(LibraryTestTags.removeBook(report.id)).assertDoesNotExist()
        compose.onNodeWithTag(LibraryTestTags.gridBook(report.id)).performTouchInput { longClick() }
        compose.onNodeWithTag(LibraryTestTags.REMOVE_CONFIRM).assertIsDisplayed()
        assertEquals(emptyList<BookId>(), removed)

        compose.onNodeWithText(string(R.string.library_remove_confirm_action)).performClick()
        assertEquals(listOf(report.id), removed)
    }

    /**
     * Everything around the shelf is hoisted above the layout choice, so the grid must show the same
     * empty invitation, the same import strip and the same report the list does.
     */
    @Test fun the_states_around_the_shelf_are_the_same_in_the_grid() {
        render(
            state = LibraryHomeState.Shelf(emptyList(), importing = ImportProgress(1, 3)),
            initialViewMode = LibraryViewMode.GRID
        )

        compose.onNodeWithTag(LibraryTestTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.IMPORTING).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.BOOKS_GRID).assertDoesNotExist()
        compose.onNodeWithTag(LibraryTestTags.ADD).assertIsNotEnabled()
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
        compose.onNodeWithTag(LibraryTestTags.VIEW_MENU).assertIsNotEnabled().performClick()
        compose.onNodeWithTag(LibraryTestTags.VIEW_LIST).assertDoesNotExist()
        compose.onNodeWithTag(LibraryTestTags.APPEARANCE_SYSTEM).assertDoesNotExist()

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

    /**
     * The label in the report comes from the picker, so it is outside data on a surface that draws
     * it verbatim. A name carrying control characters must be reported under the same sanitized
     * form the import itself would have titled the book with, rather than tearing the row it is
     * drawn in or naming a file that reads as something else.
     */
    @Test fun a_failed_file_is_named_without_the_control_characters_its_label_carried() {
        val hostile = " brok\u0000en\nreport.pdf  "
        val state = LibraryHomeState.Shelf(
            entries = emptyList(),
            report = ImportReport(listOf(ImportOutcome.Failed(hostile, ImportFailure.StorageUnavailable)))
        )
        render(state)

        compose.onNodeWithTag(LibraryTestTags.IMPORT_REPORT).assertIsDisplayed()
        compose.onNodeWithText("brokenreport.pdf").assertIsDisplayed()
        compose.onNodeWithText(hostile).assertDoesNotExist()
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

    /**
     * The view mode is held here rather than passed in, because what the menu is for is changing it:
     * the screen is stateless about the choice, so nothing switches layout unless the test state the
     * menu writes into is the one the screen reads back.
     */
    private fun render(
        state: LibraryHomeState,
        thumbnails: Map<BookId, Bitmap?> = emptyMap(),
        initialViewMode: LibraryViewMode = LibraryViewMode.LIST
    ) {
        viewMode = initialViewMode
        appearanceMode = AppearanceMode.SYSTEM

        compose.setContent {
            FoliumTheme(appearanceMode = appearanceMode) {
                LibraryScreen(
                    state = state,
                    thumbnails = thumbnails,
                    viewMode = viewMode,
                    appearanceMode = appearanceMode,
                    onAddBooks = { addCalls++ },
                    onOpenBook = { opened += it },
                onShowDetail = {},
                    onRemoveBook = { removed += it },
                    onDismissReport = { dismissCalls++ },
                    onViewModeChange = { viewMode = it },
                    onAppearanceModeChange = { appearanceMode = it }
                )
            }
        }
    }

    /**
     * The label a screen reader offers for the row's click action. It is carried by a semantics
     * modifier of the row's own rather than by the clickable that performs the open, so nothing but
     * this says it survived the merge.
     */
    private fun namesItsOpenAction(title: String) = SemanticsMatcher("names its open action") { node ->
        node.config.getOrNull(SemanticsActions.OnClick)?.label ==
            context.getString(R.string.library_open_book, title)
    }

    private fun progress(page: Int, of: Int, percent: Int): String =
        context.getString(R.string.library_book_progress, page, of, percent)

    private fun string(id: Int): String = context.getString(id)

    private fun book(id: String, title: String, pageCount: Int) =
        LibraryBook(BookId(id), title, pageCount, addedAtMillis = 1_000L)
}
