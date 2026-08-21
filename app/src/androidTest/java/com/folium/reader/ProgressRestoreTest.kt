package com.folium.reader

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.library.BookFormat
import com.folium.reader.core.library.ImportOutcome
import com.folium.reader.library.BookCatalogStore
import com.folium.reader.library.BookImporter
import com.folium.reader.library.LibraryPaths
import com.folium.reader.library.OpenBookRequest
import com.folium.reader.library.PickedSource
import com.folium.reader.library.ProgressStore
import com.folium.reader.reader.ReaderHost
import com.folium.reader.reader.ReaderTestTags
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A book with a seeded progress record opens the reader on that page directly, through the same
 * storage and open path the app itself uses: a real import, a real progress row, a real engine
 * open. The reader-core seam that makes the far page's own shape known before its first frame —
 * rather than borrowed from page one and corrected afterward — is unit-tested in `ReaderDocumentTest`;
 * this pins the integration around it, which nothing has exercised on a device since Phase C.
 */
@RunWith(AndroidJUnit4::class)
class ProgressRestoreTest {

    private companion object {
        const val PAGE_COUNT = 10
        const val FAR_PAGE_INDEX = 7
        const val REFLOWABLE_LONG_EPUB = "reflowable-long.epub"
        const val FAR_EPUB_PAGE_INDEX = 5
    }

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Fixture bytes live in the test APK, not in the app under test, so they are read through the
     * instrumentation's own context. [context] stays the app's, because that is whose `filesDir`
     * the library is written into.
     */
    private val fixtures = InstrumentationRegistry.getInstrumentation().context.assets
    private val libraryRoot = File(context.filesDir, "library")

    private val paths = LibraryPaths(context.filesDir)
    private val catalog = BookCatalogStore(paths)
    private val progress = ProgressStore(paths)
    private val importer = BookImporter(paths, catalog)

    @Before fun clearLibrary() {
        libraryRoot.deleteRecursively()
    }

    @After fun tearDown() {
        libraryRoot.deleteRecursively()
    }

    @Test fun a_seeded_progress_record_opens_the_reader_directly_on_that_page() {
        val source = PickedSource("Progress book.pdf") { ByteArrayInputStream(realPdfBytes(PAGE_COUNT)) }
        val outcome = importer.import(source)
        assertTrue("fixture import must succeed: $outcome", outcome is ImportOutcome.Imported)
        val book = (outcome as ImportOutcome.Imported).book

        assertTrue("seeding the progress row must succeed", progress.put(book.id, FAR_PAGE_INDEX))

        val storedPage = progress.read().single { it.bookId == book.id }.pageIndex
        val request = OpenBookRequest(book, paths.documentFile(book.id, BookFormat.PDF), storedPage)

        compose.setContent {
            ReaderHost(request = request, onPageChanged = {}, onBack = {})
        }

        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(ReaderTestTags.pageContent(FAR_PAGE_INDEX)).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag(ReaderTestTags.pageContent(FAR_PAGE_INDEX)).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.pageContent(0)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.reader_page_indicator, FAR_PAGE_INDEX + 1, PAGE_COUNT))
            .assertIsDisplayed()
    }

    @Test fun a_seeded_progress_record_in_an_imported_epub_opens_the_reader_directly_on_that_page() {
        val source = PickedSource("Reflowable book.epub") { fixtures.open(REFLOWABLE_LONG_EPUB) }
        val outcome = importer.import(source)
        assertTrue("fixture import must succeed: $outcome", outcome is ImportOutcome.Imported)
        val book = (outcome as ImportOutcome.Imported).book

        assertTrue("seeding the progress row must succeed", progress.put(book.id, FAR_EPUB_PAGE_INDEX))

        val storedPage = progress.read().single { it.bookId == book.id }.pageIndex
        val request = OpenBookRequest(book, paths.documentFile(book.id, BookFormat.EPUB), storedPage)

        compose.setContent {
            ReaderHost(request = request, onPageChanged = {}, onBack = {})
        }

        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(ReaderTestTags.pageContent(FAR_EPUB_PAGE_INDEX)).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag(ReaderTestTags.pageContent(FAR_EPUB_PAGE_INDEX)).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.pageContent(0)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.reader_page_indicator, FAR_EPUB_PAGE_INDEX + 1, book.pageCount))
            .assertIsDisplayed()
    }

    /** Writes a genuinely renderable, throwaway PDF and returns its bytes. */
    private fun realPdfBytes(pages: Int): ByteArray {
        val document = PdfDocument()
        return try {
            repeat(pages) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("Page ${index + 1}", 90f, 300f, android.graphics.Paint().apply {
                    color = Color.BLACK
                    textSize = 48f
                })
                document.finishPage(page)
            }
            val out = ByteArrayOutputStream()
            document.writeTo(out)
            out.toByteArray()
        } finally {
            document.close()
        }
    }
}
