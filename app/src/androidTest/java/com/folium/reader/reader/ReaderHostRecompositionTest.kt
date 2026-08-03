package com.folium.reader.reader

import android.content.Context
import android.provider.DocumentsContract
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.FixtureDocumentsProvider
import com.folium.reader.R
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.pdf.MIN_ZOOM_SCALE
import com.folium.reader.library.LibraryPaths
import com.folium.reader.library.OpenBookRequest
import com.folium.reader.perf.ProbedComposition
import com.folium.reader.perf.RecompositionProbe
import com.folium.reader.saf.DocumentCopy
import com.folium.reader.ui.FoliumTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

/**
 * Pins the callback identity [ReaderHost] hands [ReaderScreen] down the whole reading session: a
 * gesture callback rebuilt per composition is a changed parameter, and it re-executes both chrome
 * bars on every state the session publishes however little the bars have to say that is new.
 *
 * Nothing below [ReaderHost] can observe this: the chrome recomposition test drives [ReaderScreen]
 * directly and supplies its own stable callbacks, so it renders identically either way. The claim
 * is only reachable through a real session, which is what this test stages — a real document under
 * the app-managed library's own layout, opened by the host itself, rendered by the real engine.
 *
 * Note that removing the host's own `remember` around those references does not break this: the
 * compiler already memoizes a bound method reference under strong skipping, so the explicit
 * `remember` is belt-and-braces rather than the thing holding the identity still. Substituting a
 * callback the compiler cannot memoize does break it, which is the property being guarded here.
 *
 * The change being observed is the viewport's: [ReaderScreen] reports its size once it has laid
 * out, which reconciles how much of the page a fitted viewport reaches and then brings in the page
 * rasters one publication at a time. None of that touches what either bar reads — the zoom scale,
 * the fit mode, the page and the page count are all exactly as they were — so both bars must skip
 * every one of those publications.
 *
 * Both bars are counted over the whole session rather than across a before/after pair, because the
 * probe's counts are cumulative and a pair of readings taken from a live session races the very
 * publications it is trying to bracket. Each bar reads one string that never changes here, so the
 * original executes each exactly once, however many states the session goes on to publish. That the
 * page was a placeholder at some point and is a raster at the end is what proves those publications
 * genuinely reached composition, so a count of one says the bars skipped them rather than that
 * nothing happened. See [RecompositionProbe] for how a body is counted.
 */
@RunWith(AndroidJUnit4::class)
class ReaderHostRecompositionTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val probe = RecompositionProbe(context)

    private val bookId = BookId("reader-host-recomposition")
    private val book = LibraryBook(bookId, TITLE, FixtureDocumentsProvider.FIXTURE_PAGE_COUNT, addedAtMillis = 1_000L)
    private val documentFile: File = LibraryPaths(context.filesDir).documentFile(bookId)
    private val request = OpenBookRequest(book, documentFile, initialPage = 0)

    private val onPageChanged: (Int) -> Unit = {}
    private val onBack: () -> Unit = {}

    @Before fun stageTheBook() {
        FixtureDocumentsProvider.setMode(context, FixtureDocumentsProvider.Mode.Normal)

        documentFile.parentFile?.mkdirs()
        val uri = DocumentsContract.buildDocumentUri(FixtureDocumentsProvider.AUTHORITY, FixtureDocumentsProvider.PDF)
        val failure = DocumentCopy.copyStream(
            { requireNotNull(context.contentResolver.openInputStream(uri)) },
            documentFile
        )
        check(failure == null) { "fixture copy failed: $failure" }
    }

    @After fun removeTheStagedBook() {
        documentFile.parentFile?.deleteRecursively()
    }

    @Test fun the_viewport_and_its_pages_arriving_re_execute_the_page_surface_and_neither_chrome_bar() {
        compose.setContent {
            FoliumTheme {
                ProbedComposition(probe) {
                    ReaderHost(request = request, onPageChanged = onPageChanged, onBack = onBack)
                }
            }
        }

        compose.waitUntil(RENDER_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(ReaderTestTags.pageContent(0)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ReaderTestTags.CHROME_TOP).assertIsDisplayed()

        assertTrue(
            "the page never went through its placeholder, so the surface was only ever composed once",
            pageSurfaceExecutions() > 0
        )
        assertEquals("the top bar re-executed while the viewport and its pages arrived", 1, topChromeExecutions())
        assertEquals("the bottom bar re-executed while the viewport and its pages arrived", 1, bottomChromeExecutions())
    }

    private fun topChromeExecutions(): Int =
        probe.lookups(R.string.reader_zoom_level, (MIN_ZOOM_SCALE * 100).roundToInt())

    private fun bottomChromeExecutions(): Int =
        probe.lookups(R.string.reader_page_position, 1, FixtureDocumentsProvider.FIXTURE_PAGE_COUNT)

    /**
     * Summed over every page the pager keeps composed, since a page stops resolving its placeholder
     * the moment it has a raster to draw and only the pages still waiting keep counting.
     */
    private fun pageSurfaceExecutions(): Int =
        (1..FixtureDocumentsProvider.FIXTURE_PAGE_COUNT).sumOf { probe.lookups(R.string.reader_page_loading, it) }

    private companion object {
        const val TITLE = "Quarterly report.pdf"
        const val RENDER_TIMEOUT_MILLIS = 60_000L
    }
}
