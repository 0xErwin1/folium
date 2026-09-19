package com.folium.reader.reader

import android.content.Context
import android.provider.DocumentsContract
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.FixtureDocumentsProvider
import com.folium.reader.core.library.BookFormat
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.ImportOutcome
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.library.BookCatalogStore
import com.folium.reader.library.BookImporter
import com.folium.reader.library.LibraryPaths
import com.folium.reader.library.OpenBookRequest
import com.folium.reader.library.PickedSource
import com.folium.reader.saf.DocumentCopy
import com.folium.reader.ui.FoliumTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Book settings" is offered for every document, reflowable or not, since a fixed-layout document
 * still carries its own "Two pages" row. The fit-mode items are the ones that depend on the document:
 * they answer a question only a fixed layout has, so they disappear once the document is reflowable.
 */
@RunWith(AndroidJUnit4::class)
class TypographyOverflowMenuInstrumentedTest {

    @get:Rule val compose = createComposeRule()

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

    @Test fun book_settings_is_offered_and_fit_controls_are_hidden_for_an_epub() {
        val importer = BookImporter(paths, BookCatalogStore(paths))
        val source = PickedSource("Reflowable book.epub") { fixtures.open(REFLOWABLE_EPUB) }
        val outcome = importer.import(source)
        assertTrue("fixture import must succeed: $outcome", outcome is ImportOutcome.Imported)
        val book = (outcome as ImportOutcome.Imported).book
        val request = OpenBookRequest(book, paths.documentFile(book.id, BookFormat.EPUB), initialPage = 0)

        compose.setContent {
            FoliumTheme { ReaderHost(request = request, onPageChanged = {}, onBack = {}) }
        }

        compose.waitUntil(RENDER_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(ReaderTestTags.CHROME_TOP).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ReaderTestTags.OVERFLOW).performClick()
        compose.onNodeWithTag(ReaderTestTags.BOOK_SETTINGS).assertExists()
        compose.onNodeWithTag(ReaderTestTags.FIT_WIDTH).assertDoesNotExist()
        compose.onNodeWithTag(ReaderTestTags.FIT_PAGE).assertDoesNotExist()
    }

    @Test fun book_settings_and_fit_controls_are_both_offered_for_a_pdf() {
        FixtureDocumentsProvider.setMode(context, FixtureDocumentsProvider.Mode.Normal)
        val book = LibraryBook(
            BookId("typography-overflow-pdf"),
            "Quarterly report.pdf",
            FixtureDocumentsProvider.FIXTURE_PAGE_COUNT,
            addedAtMillis = 1_000L
        )
        val documentFile = paths.documentFile(book.id, BookFormat.PDF)
        documentFile.parentFile?.mkdirs()
        val uri = DocumentsContract.buildDocumentUri(FixtureDocumentsProvider.AUTHORITY, FixtureDocumentsProvider.PDF)
        val failure = DocumentCopy.copyStream(
            { requireNotNull(context.contentResolver.openInputStream(uri)) },
            documentFile
        )
        check(failure == null) { "fixture copy failed: $failure" }
        val request = OpenBookRequest(book, documentFile, initialPage = 0)

        compose.setContent {
            FoliumTheme { ReaderHost(request = request, onPageChanged = {}, onBack = {}) }
        }

        compose.waitUntil(RENDER_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(ReaderTestTags.CHROME_TOP).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ReaderTestTags.OVERFLOW).performClick()
        compose.onNodeWithTag(ReaderTestTags.BOOK_SETTINGS).assertExists()
        compose.onNodeWithTag(ReaderTestTags.FIT_WIDTH).assertExists()
        compose.onNodeWithTag(ReaderTestTags.FIT_PAGE).assertExists()
    }

    private companion object {
        const val REFLOWABLE_EPUB = "reflowable.epub"
        const val RENDER_TIMEOUT_MILLIS = 60_000L
    }
}
