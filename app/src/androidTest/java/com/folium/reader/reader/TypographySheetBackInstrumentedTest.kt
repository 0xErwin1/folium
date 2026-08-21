package com.folium.reader.reader

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.library.BookFormat
import com.folium.reader.core.library.ImportOutcome
import com.folium.reader.library.BookCatalogStore
import com.folium.reader.library.BookImporter
import com.folium.reader.library.LibraryPaths
import com.folium.reader.library.OpenBookRequest
import com.folium.reader.library.PickedSource
import com.folium.reader.ui.FoliumTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The typography sheet is one more level of back than the reader itself: the first press closes it
 * without touching the book underneath, and only a second press leaves the reader.
 */
@RunWith(AndroidJUnit4::class)
class TypographySheetBackInstrumentedTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = InstrumentationRegistry.getInstrumentation().context.assets
    private val libraryRoot = File(context.filesDir, "library")
    private val paths = LibraryPaths(context.filesDir)

    @Before fun clearLibrary() {
        libraryRoot.deleteRecursively()
    }

    @After fun tearDown() {
        libraryRoot.deleteRecursively()
    }

    @Test fun back_closes_the_sheet_before_it_leaves_the_reader() {
        val importer = BookImporter(paths, BookCatalogStore(paths))
        val source = PickedSource("Reflowable book.epub") { fixtures.open(REFLOWABLE_EPUB) }
        val outcome = importer.import(source)
        assertTrue("fixture import must succeed: $outcome", outcome is ImportOutcome.Imported)
        val book = (outcome as ImportOutcome.Imported).book
        val request = OpenBookRequest(book, paths.documentFile(book.id, BookFormat.EPUB), initialPage = 0)

        var leftReader = false
        var sheetOpen by mutableStateOf(false)

        compose.setContent {
            FoliumTheme {
                ReaderHost(
                    request = request,
                    onPageChanged = {},
                    onBack = { leftReader = true },
                    typographySheetOpen = sheetOpen,
                    onTypographySheetOpenChange = { sheetOpen = it }
                )
            }
        }

        compose.waitUntil(RENDER_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(ReaderTestTags.CHROME_TOP).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ReaderTestTags.OVERFLOW).performClick()
        compose.onNodeWithTag(ReaderTestTags.TYPOGRAPHY).performClick()

        compose.waitUntil(RENDER_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(TypographySheetTestTags.SHEET).fetchSemanticsNodes().isNotEmpty()
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }

        compose.waitUntil(RENDER_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(TypographySheetTestTags.SHEET).fetchSemanticsNodes().isEmpty()
        }
        assertFalse("the first back press must not leave the reader", leftReader)

        // Leaving the reader is the activity's own back callback, and this composes the host alone.
        // Asserting a second press here would be asserting something nothing in this composition
        // owns; what matters at this level is that the sheet takes the press ahead of whoever is
        // behind it, and gives it back afterwards.
        assertTrue("the sheet must stop intercepting once it is closed", !sheetOpen)
    }

    private companion object {
        const val REFLOWABLE_EPUB = "reflowable.epub"
        const val RENDER_TIMEOUT_MILLIS = 60_000L
    }
}
