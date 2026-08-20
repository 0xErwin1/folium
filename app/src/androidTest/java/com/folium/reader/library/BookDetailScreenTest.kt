package com.folium.reader.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.R
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.library.ShelfEntry
import com.folium.reader.core.pdf.OutlineRow
import com.folium.reader.ui.FoliumTheme
import org.junit.Rule
import org.junit.Test

/**
 * A document that declares no contents is the common case for a scan, so the screen has to say what
 * to do instead of listing chapters rather than leaving a sentence and a blank.
 */
class BookDetailScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val entry = ShelfEntry(
        LibraryBook(BookId("bd01"), "Field notes.pdf", pageCount = 97, addedAtMillis = 1_000L),
        0
    )

    @Test fun a_document_without_contents_says_how_to_move_through_it_anyway() {
        render(BookDetail())

        compose.onNodeWithText(string(R.string.detail_contents_none)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.detail_contents_none_hint)).assertIsDisplayed()
    }

    @Test fun a_document_with_contents_lists_them_and_offers_no_substitute() {
        render(BookDetail(contents = listOf(OutlineRow("Chapter one", pageIndex = 0, depth = 0))))

        compose.onNodeWithText("Chapter one").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.detail_contents_none)).assertIsNotDisplayed()
        compose.onNodeWithText(string(R.string.detail_contents_none_hint)).assertIsNotDisplayed()
    }

    private fun render(detail: BookDetail) {
        compose.setContent {
            FoliumTheme {
                BookDetailScreen(
                    entry = entry,
                    detail = detail,
                    thumbnail = null,
                    onBack = {},
                    onOpen = {},
                    onOpenAt = {},
                    onRemove = {}
                )
            }
        }
    }

    private fun string(id: Int): String = context.getString(id)
}
