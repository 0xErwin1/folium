package com.folium.reader.reader

import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.CachedPage
import com.folium.reader.core.pdf.PageCacheKey
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.pdf.ViewportRenderRequest
import com.folium.reader.core.pdf.ViewportScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * [ThumbnailPipeline] wired against a real [ByteBoundedPageCache] and a real [ViewportScheduler],
 * through a fake renderer standing in for [ThumbnailRenderer] — this module's local unit tests
 * cannot construct a real [android.graphics.Bitmap], exactly the reason [ReaderPresenterTest]'s own
 * leak sweep drives [ReaderPresenter] through a fake renderer rather than [PdfPageRenderer].
 */
class ThumbnailPipelineTest {

    /** Mirrors [BorrowedThumbnail]: the only handle a consumer holds a cached value through. */
    private class LeakSweepThumbnail(private val borrow: CachedPage<String>) {
        fun release() = borrow.release()
    }

    private fun spec() = RenderSpec(10, 10)

    /** Mirrors [ThumbnailRenderer]'s own acquire-or-rasterize-then-acquire shape against a real cache. */
    private fun leakSweepRender(
        cache: ByteBoundedPageCache<String>,
        documentId: String,
        renderFailure: Boolean,
        request: ViewportRenderRequest
    ): RenderCandidate<LeakSweepThumbnail> {
        val key = PageCacheKey(documentId, request.pageIndex, 0L, request.spec)
        cache.acquire(key)?.let { return RenderCandidate(LeakSweepThumbnail(it)) { it.release() } }

        if (renderFailure) throw PdfException(PdfFailure.Corrupt)

        cache.put(key, RenderCandidate("page-${request.pageIndex}") {}, sizeBytes = 1_024L)
        val borrow = cache.acquire(key) ?: throw PdfException(PdfFailure.Resource(retryable = true))
        return RenderCandidate(LeakSweepThumbnail(borrow)) { borrow.release() }
    }

    private fun buildPipeline(
        cache: ByteBoundedPageCache<String>,
        documentId: String,
        posted: CopyOnWriteArrayList<() -> Unit>,
        renderFailure: Boolean = false,
        onChanged: (ThumbnailGridState<LeakSweepThumbnail>) -> Unit = {}
    ): ThumbnailPipeline<LeakSweepThumbnail> {
        lateinit var pipeline: ThumbnailPipeline<LeakSweepThumbnail>
        pipeline = ThumbnailPipeline(
            releaseValue = LeakSweepThumbnail::release,
            deliverToPresenter = { action -> posted += action },
            onChanged = onChanged
        ) { onOutcome ->
            ViewportScheduler(1, { request, _ -> leakSweepRender(cache, documentId, renderFailure, request) }, onOutcome = onOutcome)
        }
        return pipeline
    }

    /**
     * [ThumbnailPipeline] hands outcomes back through `deliverToPresenter` rather than processing
     * them inline — exactly like [ReaderHostController]'s `mainPost` — so a test has to run whatever
     * lands here itself, instead of the scheduler's own worker thread.
     */
    private fun drainUntil(posted: CopyOnWriteArrayList<() -> Unit>, condition: () -> Boolean) {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadlineNanos) {
            val next = posted.removeFirstOrNull()
            if (next != null) next() else Thread.sleep(2)
        }
        assertTrue("thumbnail pipeline outcome never arrived", condition())
    }

    private fun drainAll(posted: CopyOnWriteArrayList<() -> Unit>) {
        var next = posted.removeFirstOrNull()
        while (next != null) {
            next()
            next = posted.removeFirstOrNull()
        }
    }

    @Test fun `a wanted page eventually shows a thumbnail`() {
        val cache = ByteBoundedPageCache<String>(64L * 1024 * 1024)
        val posted = CopyOnWriteArrayList<() -> Unit>()
        var latest = ThumbnailGridState<LeakSweepThumbnail>()

        val pipeline = buildPipeline(cache, "doc-1", posted) { state -> latest = state }

        try {
            pipeline.setWanted(listOf(3)) { spec() }
            drainUntil(posted) { latest.thumbnails.containsKey(3) }

            assertEquals(emptySet<Int>(), latest.failed)
        } finally {
            pipeline.close()
            pipeline.shutdown()
            drainAll(posted)
            cache.invalidateDocument("doc-1")
        }

        assertEquals(0L, cache.totalBytesTracked())
    }

    /**
     * Closing releases every thumbnail on screen, so whoever draws the grid has to hear about it:
     * a state left pointing at released rasters is a use-after-release waiting for the next frame.
     */
    @Test fun `closing publishes an empty grid rather than leaving released thumbnails on screen`() {
        val cache = ByteBoundedPageCache<String>(64L * 1024 * 1024)
        val posted = CopyOnWriteArrayList<() -> Unit>()
        var latest = ThumbnailGridState<LeakSweepThumbnail>()

        val pipeline = buildPipeline(cache, "doc-close", posted) { state -> latest = state }

        try {
            pipeline.setWanted(listOf(3)) { spec() }
            drainUntil(posted) { latest.thumbnails.containsKey(3) }

            pipeline.close()

            assertEquals(emptyMap<Int, LeakSweepThumbnail>(), latest.thumbnails)
            assertEquals(emptySet<Int>(), latest.failed)
        } finally {
            pipeline.close()
            pipeline.shutdown()
            drainAll(posted)
            cache.invalidateDocument("doc-close")
        }

        assertEquals(0L, cache.totalBytesTracked())
    }

    @Test fun `a render failure marks the page failed instead of throwing`() {
        val cache = ByteBoundedPageCache<String>(64L * 1024 * 1024)
        val posted = CopyOnWriteArrayList<() -> Unit>()
        var latest = ThumbnailGridState<LeakSweepThumbnail>()

        val pipeline = buildPipeline(cache, "doc-2", posted, renderFailure = true) { state -> latest = state }

        try {
            pipeline.setWanted(listOf(2)) { spec() }
            drainUntil(posted) { latest.failed.contains(2) }

            assertEquals(emptyMap<Int, LeakSweepThumbnail>(), latest.thumbnails)
        } finally {
            pipeline.close()
            pipeline.shutdown()
            drainAll(posted)
        }
    }

    @Test fun `a page dropped from the wanted list is released once its render lands late`() {
        val cache = ByteBoundedPageCache<String>(64L * 1024 * 1024)
        val posted = CopyOnWriteArrayList<() -> Unit>()
        var latest = ThumbnailGridState<LeakSweepThumbnail>()

        val pipeline = buildPipeline(cache, "doc-3", posted) { state -> latest = state }

        try {
            pipeline.setWanted(listOf(1)) { spec() }
            // The cell scrolls away before its render ever lands: the coordinator cancels it, and a
            // rendered outcome that still turns up late must be released rather than shown.
            pipeline.setWanted(listOf(9)) { spec() }
            drainUntil(posted) { latest.thumbnails.containsKey(9) }

            assertTrue("a page no longer wanted must never appear in the grid state", 1 !in latest.thumbnails)
        } finally {
            pipeline.close()
            pipeline.shutdown()
            drainAll(posted)
            cache.invalidateDocument("doc-3")
        }

        assertEquals(0L, cache.totalBytesTracked())
    }

    /**
     * Requirement: every raster borrowed must be released exactly once, including when the sheet
     * closes mid-render and when a cell scrolls away before its render lands. Mirrors
     * [ReaderPresenterTest.manyOpenNavigateZoomCloseCyclesLeaveNoBorrowOutstandingInTheSharedCache].
     */
    @Test fun `many wanted-window changes and closes leave no borrow outstanding in the cache`() {
        val cache = ByteBoundedPageCache<String>(4L * 1024 * 1024)

        repeat(30) { cycle ->
            val documentId = "doc-cycle-$cycle"
            val posted = CopyOnWriteArrayList<() -> Unit>()
            val pipeline = buildPipeline(cache, documentId, posted)

            pipeline.setWanted(listOf(0, 1, 2)) { spec() }
            drainAll(posted)
            pipeline.setWanted(listOf(1, 2, 3)) { spec() }
            drainAll(posted)
            pipeline.setWanted(listOf(5, 6)) { spec() }
            drainAll(posted)

            // Closed before this last batch's own outcomes have necessarily landed.
            pipeline.close()
            pipeline.shutdown()
            drainAll(posted)
            cache.invalidateDocument(documentId)
        }

        assertEquals(0L, cache.totalBytesTracked())
    }
}
