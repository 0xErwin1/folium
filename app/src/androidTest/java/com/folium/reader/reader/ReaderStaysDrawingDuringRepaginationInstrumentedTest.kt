package com.folium.reader.reader

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.library.BookFormat
import com.folium.reader.core.library.ImportOutcome
import com.folium.reader.core.pdf.ReflowFontFamily
import com.folium.reader.library.BookCatalogStore
import com.folium.reader.library.BookImporter
import com.folium.reader.library.LibraryPaths
import com.folium.reader.library.OpenBookRequest
import com.folium.reader.library.PickedSource
import com.folium.reader.ui.FoliumTheme
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A typography edit tears down the outgoing presenter's pages the moment it is dispatched (see
 * [ReaderHostController.repaginate]'s own doc), but the carried preview it hands over is what keeps
 * the reader showing a page rather than a blank surface for however long the re-pagination takes.
 * This drives that path through a real sheet edit rather than by calling `repaginate` directly, so a
 * regression in the sheet's own wiring — not only in [ReaderHostController] — would be caught here.
 */
@RunWith(AndroidJUnit4::class)
class ReaderStaysDrawingDuringRepaginationInstrumentedTest {

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

    @Test fun the_reader_keeps_a_page_on_screen_while_a_typography_edit_re_paginates() {
        val importer = BookImporter(paths, BookCatalogStore(paths))
        val source = PickedSource("Reflowable book.epub") { fixtures.open(REFLOWABLE_LONG_EPUB) }
        val outcome = importer.import(source)
        assertTrue("fixture import must succeed: $outcome", outcome is ImportOutcome.Imported)
        val book = (outcome as ImportOutcome.Imported).book
        val request = OpenBookRequest(book, paths.documentFile(book.id, BookFormat.EPUB), initialPage = 0)

        compose.setContent {
            FoliumTheme {
                ReaderHost(
                    request = request,
                    onPageChanged = {},
                    onBack = {},
                    typographySheetOpen = true,
                    onTypographySheetOpenChange = {}
                )
            }
        }

        compose.waitUntil(RENDER_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(BookSettingsSheetTestTags.SHEET).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(RENDER_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(ReaderTestTags.pageContent(0)).fetchSemanticsNodes().isNotEmpty()
        }

        // A font family this fixture is not already showing, so the edit is genuine rather than a no-op
        // the scheduler would still dispatch, but which would prove nothing about the carried preview.
        compose.onNodeWithTag(BookSettingsSheetTestTags.fontOption(ReflowFontFamily.SERIF)).performClick()

        // A reader left blank stays blank for as long as the layout runs, which is hundreds of
        // milliseconds and so dozens of samples. A single empty sample is the semantics tree being
        // read between two frames, which says nothing about what a person would see — so this fails
        // on a run of them rather than on one, and reports the run it saw.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REPAGINATION_WINDOW_MILLIS)
        var blankRun = 0
        var longestBlankRun = 0
        while (System.nanoTime() < deadline) {
            val hasCarriedOrContent = compose.onAllNodesWithTag(ReaderTestTags.pageCarried(0)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag(ReaderTestTags.pageContent(0)).fetchSemanticsNodes().isNotEmpty()

            blankRun = if (hasCarriedOrContent) 0 else blankRun + 1
            longestBlankRun = maxOf(longestBlankRun, blankRun)
            Thread.sleep(SAMPLE_INTERVAL_MILLIS)
        }

        assertTrue(
            "the reader must never go blank while a typography edit is re-paginating, but it held " +
                "nothing for ${longestBlankRun * SAMPLE_INTERVAL_MILLIS}ms",
            longestBlankRun < BLANK_RUN_LIMIT
        )
    }

    private companion object {
        const val SAMPLE_INTERVAL_MILLIS = 20L

        /** Four samples is 80ms of nothing on screen — past a dropped frame, short of a real gap. */
        const val BLANK_RUN_LIMIT = 4
        const val REFLOWABLE_LONG_EPUB = "reflowable-long.epub"
        const val RENDER_TIMEOUT_MILLIS = 60_000L
        const val REPAGINATION_WINDOW_MILLIS = 3_000L
    }
}
