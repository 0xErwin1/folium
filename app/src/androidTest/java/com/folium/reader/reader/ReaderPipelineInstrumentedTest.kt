package com.folium.reader.reader

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.FixtureDocumentsProvider
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.LibraryRootIdentity
import com.folium.reader.core.library.ProviderDocumentIdentity
import com.folium.reader.core.library.RootVersion
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.MIN_ZOOM_SCALE
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.saf.SafDocumentResult
import com.folium.reader.saf.SafDocumentSource
import com.folium.reader.saf.SharedPreferencesSafRootStorage
import com.folium.reader.saf.StoredSafRoot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the whole reading path on a device: a real PDF served through the Storage Access
 * Framework, copied under the persisted root, parsed by the real engine, scheduled, cached and
 * borrowed back out as bitmaps.
 *
 * The system picker is deliberately not involved. The fixture provider belongs to this app, so its
 * documents are reachable without a URI grant, which lets the pipeline below the picker be tested
 * without depending on the Documents UI — that boundary is already covered on its own.
 */
@RunWith(AndroidJUnit4::class)
class ReaderPipelineInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewport = ReaderViewport(720, 1280)

    private val identity = ProviderDocumentIdentity(FixtureDocumentsProvider.AUTHORITY, FixtureDocumentsProvider.PDF)

    private var session: ReaderSession? = null

    @Before fun selectFixtureRoot() {
        FixtureDocumentsProvider.setMode(context, FixtureDocumentsProvider.Mode.Normal)
        SharedPreferencesSafRootStorage(context).write(
            StoredSafRoot(
                treeUri = "content://${FixtureDocumentsProvider.AUTHORITY}/tree/${FixtureDocumentsProvider.ROOT}",
                identity = LibraryRootIdentity(FixtureDocumentsProvider.AUTHORITY, FixtureDocumentsProvider.ROOT),
                version = RootVersion("1")
            )
        )
    }

    @After fun tearDown() {
        session?.let { open ->
            runOnMain { open.close() }
            open.dispose()
        }
        session = null
        SharedPreferencesSafRootStorage(context).clear()
    }

    @Test fun a_real_pdf_opens_and_renders_and_its_file_survives_the_session() {
        val states = openSession()
        val opened = requireNotNull(session)
        val file = copiedDocument(identity)

        assertEquals(FixtureDocumentsProvider.FIXTURE_PAGE_COUNT, opened.pageCount)
        assertTrue("the copy must live in private storage", file.exists())

        states.awaitPagesRendered(setOf(0, 1, 2)) { runOnMain { opened.presenter.setViewport(viewport) } }

        val rendered = opened.presenter.uiState
        assertEquals(setOf(0, 1, 2), rendered.pages.keys)
        assertEquals(emptySet<Int>(), rendered.failedPages)
        rendered.pages.forEach { (index, page) ->
            assertTrue("page $index must have real pixels", page.bitmap.width > 0 && page.bitmap.height > 0)
            assertTrue(page.bitmap.width <= viewport.widthPx && page.bitmap.height <= viewport.heightPx)
        }

        runOnMain { opened.close() }
        opened.dispose()
        session = null

        assertTrue("the reader owns no scratch file and deletes nothing on close", file.exists())
        file.delete()
    }

    @Test fun navigating_forward_and_back_keeps_every_page_under_its_own_index() {
        val states = openSession()
        val opened = requireNotNull(session)
        states.awaitPagesRendered(setOf(0, 1, 2)) { runOnMain { opened.presenter.setViewport(viewport) } }

        val signatures = opened.presenter.uiState.pages.mapValues { it.value.bitmap.sampleSignature() }
        assertEquals(
            "the fixture's pages must be distinguishable, or this proves nothing",
            signatures.size,
            signatures.values.distinct().size
        )

        states.await("page 2 is current", { it.state.currentPage == 2 && it.pages.containsKey(2) }) {
            runOnMain { opened.presenter.dispatch(GestureIntent.FlingToPage(2)) }
        }
        states.await("page 0 is current again", { it.state.currentPage == 0 && it.pages.containsKey(0) }) {
            runOnMain { opened.presenter.dispatch(GestureIntent.FlingToPage(0)) }
        }

        opened.presenter.uiState.pages.forEach { (index, page) ->
            assertEquals(
                "page $index must show its own content, not whatever was rendered most recently",
                signatures.getValue(index),
                page.bitmap.sampleSignature()
            )
        }
    }

    @Test fun zooming_in_renders_the_same_page_at_a_higher_resolution_over_a_smaller_region() {
        val states = openSession()
        val opened = requireNotNull(session)
        states.awaitPagesRendered(setOf(0)) { runOnMain { opened.presenter.setViewport(viewport) } }

        val unzoomed = opened.presenter.uiState.pages.getValue(0)
        val unzoomedRegion = unzoomed.region
        val unzoomedPixelsPerPage = unzoomed.bitmap.width / (unzoomedRegion.right - unzoomedRegion.left)

        states.await(
            description = "page 0 re-rendered over a narrower region",
            condition = { ui -> ui.pages[0]?.let { it.region != unzoomedRegion } == true }
        ) {
            runOnMain { opened.presenter.dispatch(GestureIntent.ZoomBy(3f, PageSpacePoint(0.5f, 0.5f))) }
        }

        val zoomed = opened.presenter.uiState.pages.getValue(0)
        val zoomedPixelsPerPage = zoomed.bitmap.width / (zoomed.region.right - zoomed.region.left)

        assertTrue("a zoom must narrow the rendered region", zoomed.region.right - zoomed.region.left < unzoomedRegion.right - unzoomedRegion.left)
        assertTrue("a zoom must raise the resolution, not stretch the raster", zoomedPixelsPerPage > unzoomedPixelsPerPage * 2f)
        assertTrue(zoomed.bitmap.width <= viewport.widthPx)

        states.await(
            description = "page 0 restored to the whole page",
            condition = { ui -> ui.pages[0]?.region == unzoomedRegion }
        ) {
            runOnMain { opened.presenter.dispatch(GestureIntent.ResetZoom) }
        }
        assertEquals(MIN_ZOOM_SCALE, opened.presenter.uiState.state.zoom.scale)
    }

    @Test fun rapid_navigation_settles_on_the_last_requested_page_without_stranding_a_render() {
        val states = openSession()
        val opened = requireNotNull(session)
        states.awaitPagesRendered(setOf(0)) { runOnMain { opened.presenter.setViewport(viewport) } }

        states.await(
            description = "the window settles back on page 0 with every page intact",
            condition = { ui ->
                ui.state.currentPage == 0 && ui.pages.keys == setOf(0, 1, 2) && ui.failedPages.isEmpty()
            }
        ) {
            repeat(12) { attempt ->
                runOnMain {
                    opened.presenter.dispatch(
                        if (attempt % 2 == 0) GestureIntent.PageForward else GestureIntent.PageBack
                    )
                }
            }
        }

        val settled = opened.presenter.uiState
        assertEquals(0, settled.state.currentPage)
        assertEquals(emptySet<Int>(), settled.failedPages)
        assertEquals(setOf(0, 1, 2), settled.pages.keys)
    }

    @Test fun a_second_document_can_be_opened_once_the_first_has_been_disposed() {
        openSession()
        val first = requireNotNull(session)
        runOnMain { first.close() }
        first.dispose()
        session = null

        val states = openSession(ProviderDocumentIdentity(FixtureDocumentsProvider.AUTHORITY, FixtureDocumentsProvider.ODD_NAME_PDF))
        val second = requireNotNull(session)
        assertEquals(1, second.pageCount)
        states.awaitPagesRendered(setOf(0)) { runOnMain { second.presenter.setViewport(viewport) } }
        assertNotNull(second.presenter.uiState.pages[0])
    }

    @Test fun a_document_that_is_not_there_reports_a_typed_recovery_instead_of_opening() {
        val missingFile = File(context.cacheDir, "reader-test/does-not-exist.pdf")
        missingFile.delete()

        val result = ReaderSession.open(context, missingFile, BookId("missing"), 0) {}

        assertTrue(result is ReaderSessionResult.Missing)
        assertNull(session)
    }

    private fun openSession(target: ProviderDocumentIdentity = identity): RenderedPages {
        val states = RenderedPages()
        val file = copyIntoScratch(target)
        val result = ReaderSession.open(context, file, BookId(target.documentId), 0) { states.record(it) }
        assertTrue("open failed: $result", result is ReaderSessionResult.Opened)
        session = (result as ReaderSessionResult.Opened).session
        return states
    }

    /**
     * Brings a fixture document into private storage the same way the app-managed library's own
     * import pipeline does — a plain stream copy — since the reader itself no longer resolves a SAF
     * identity on its own path (design §5).
     */
    private fun copyIntoScratch(target: ProviderDocumentIdentity): File {
        val destination = copiedDocument(target)
        destination.parentFile?.mkdirs()

        val copied = SafDocumentSource(context.contentResolver, SharedPreferencesSafRootStorage(context))
            .copyTo(target, destination)
        check(copied is SafDocumentResult.Copied) { "fixture copy failed: $copied" }
        return destination
    }

    private fun copiedDocument(target: ProviderDocumentIdentity) =
        File(context.cacheDir, "reader-test/${target.documentId}.pdf")

    private fun runOnMain(action: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    /**
     * Waits on what the presenter has actually published rather than on elapsed time.
     *
     * The condition is armed before the trigger runs and is evaluated against every subsequent
     * publication, never against the state that was already there — a page keeps its previous
     * raster while a sharper one is pending, so "page 0 is present" is true again the instant a
     * zoom is dispatched and would settle a test on the raster it was meant to watch replaced.
     */
    private class RenderedPages {
        private val lock = Any()
        private var latest: ReaderUiState<BorrowedPage>? = null
        private var condition: ((ReaderUiState<BorrowedPage>) -> Boolean)? = null
        private var latch = CountDownLatch(0)

        fun record(ui: ReaderUiState<BorrowedPage>) = synchronized(lock) {
            latest = ui
            if (condition?.invoke(ui) == true) latch.countDown()
        }

        fun awaitPagesRendered(pages: Set<Int>, trigger: () -> Unit) =
            await("pages $pages rendered", { ui -> ui.pages.keys.containsAll(pages) }, trigger)

        fun await(
            description: String,
            condition: (ReaderUiState<BorrowedPage>) -> Boolean,
            trigger: () -> Unit
        ) {
            synchronized(lock) {
                this.condition = condition
                latch = CountDownLatch(1)
            }

            trigger()

            val settled = latch.await(60, TimeUnit.SECONDS)
            val last = synchronized(lock) { this.condition = null; latest }
            assertTrue(
                "timed out waiting for $description, last saw pages=${last?.pages?.keys} " +
                    "failed=${last?.failedPages} page=${last?.state?.currentPage}",
                settled
            )
        }
    }
}

/** A cheap content fingerprint, enough to tell one rendered page from another. */
private fun android.graphics.Bitmap.sampleSignature(): Int {
    var signature = 17
    for (y in 0 until height step (height / 16).coerceAtLeast(1)) {
        for (x in 0 until width step (width / 16).coerceAtLeast(1)) {
            signature = signature * 31 + getPixel(x, y)
        }
    }
    return signature
}
