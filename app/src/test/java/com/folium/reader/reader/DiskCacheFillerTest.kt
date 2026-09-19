package com.folium.reader.reader

import com.folium.reader.core.diskcache.DiskPageCacheKey
import com.folium.reader.core.diskcache.DiskPageCacheEntry
import com.folium.reader.core.diskcache.DiskPageCacheStore
import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.DocumentMetadata
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.PdfEngine
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.Raster
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.pdf.ViewportRenderRequest
import com.folium.reader.core.pdf.ViewportScheduler
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextEngineVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val ENGINE_ID = "test-engine"
private const val CONTENT_ID = "test-content"
private const val SETTLE_SECONDS = 30L
private const val NOT_HAPPENING_MILLIS = 200L
private const val QUIET_MILLIS = DISK_CACHE_FILL_IDLE_QUIET_MILLIS

private class FillFakeClock(startMillis: Long = 0L) : () -> Long {
    private val millis = AtomicLong(startMillis)
    override fun invoke(): Long = millis.get()
    fun advanceBy(deltaMillis: Long) { millis.addAndGet(deltaMillis) }
}

/**
 * A page's real aspect is its own [width]`/`[height]; [FillFakeDocument] never assumes every page
 * looks like page 0, so a test can make one page's real shape disagree with what
 * [ReaderDocument.aspect] would assume for it before it is measured.
 */
private class FillFakeDocument(
    override val pageCount: Int,
    private val width: (Int) -> Float = { 100f },
    private val height: (Int) -> Float = { 200f }
) : PdfDocument {
    val renderCalls = CopyOnWriteArrayList<Pair<Int, RenderSpec>>()
    val concurrentRenders = AtomicInteger(0)
    val maxConcurrentRenders = AtomicInteger(0)

    @Volatile var renderGate: CountDownLatch? = null
    @Volatile var failNextRenderWith: RuntimeException? = null

    override fun pageInfo(index: Int): PageInfo = PageInfo(index, width(index), height(index), 0)
    override fun buildDisplayList(index: Int): DisplayList = throw AssertionError("must not build a display list directly")
    override fun extractText(index: Int): TextPage = throw UnsupportedOperationException()
    override fun outline(): List<OutlineEntry> = emptyList()
    override fun metadata() = DocumentMetadata.NONE
    override fun close() = Unit

    override fun renderPage(
        index: Int,
        spec: RenderSpec,
        cancellationSignal: CancellationSignal,
        beforeRender: () -> Unit
    ): Raster {
        val concurrent = concurrentRenders.incrementAndGet()
        maxConcurrentRenders.updateAndGet { current -> maxOf(current, concurrent) }
        try {
            beforeRender()
            renderGate?.await(SETTLE_SECONDS, TimeUnit.SECONDS)

            failNextRenderWith?.let {
                failNextRenderWith = null
                throw it
            }

            renderCalls += index to spec
            return Raster(spec.width, spec.height, ByteArray(spec.width * spec.height * 4))
        } finally {
            concurrentRenders.decrementAndGet()
        }
    }
}

private class FillFakeEngine(private val document: PdfDocument) : PdfEngine {
    override val textEngineVersion = TextEngineVersion("test-pdf")
    override fun open(source: PdfSource): PdfDocument = document
}

private class FillFakeStore(
    @Volatile var capacity: Boolean = true,
    private val writesLandImmediately: Boolean = true
) : DiskPageCacheStore {
    val present = java.util.concurrent.ConcurrentHashMap.newKeySet<DiskPageCacheKey>()
    val writes = CopyOnWriteArrayList<Triple<DiskPageCacheKey, ByteArray, Float>>()

    override fun markOpen(contentId: String) = Unit
    override fun markClosed(contentId: String) = Unit
    override fun containsKey(key: DiskPageCacheKey): Boolean = key in present
    override fun read(key: DiskPageCacheKey): DiskPageCacheEntry? = null
    override fun enqueueWrite(key: DiskPageCacheKey, rgba: ByteArray, pageAspect: Float) {
        writes += Triple(key, rgba, pageAspect)
        if (writesLandImmediately) present += key
    }
    override fun hasWriteCapacity(): Boolean = capacity
}

class DiskCacheFillerTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun openDocument(document: FillFakeDocument): ReaderDocument {
        val file = temporaryFolder.newFile()
        return when (val result = ReaderDocument.open(file, BookId("book"), initialPage = 0, engine = FillFakeEngine(document))) {
            is ReaderDocumentResult.Opened -> result.document
            else -> error("expected the fake document to open")
        }
    }

    private fun awaitTrue(timeoutMillis: Long = TimeUnit.SECONDS.toMillis(SETTLE_SECONDS), condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() >= deadline) assertTrue("condition never became true", condition())
            Thread.sleep(5)
        }
    }

    private fun filler(
        document: ReaderDocument,
        pdf: PdfDocument,
        gate: DocumentPriorityGate,
        store: DiskPageCacheStore,
        sleep: (Long) -> Unit = { Thread.sleep(1) },
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
        target: () -> DiskCacheFillTarget?
    ) = DiskCacheFiller(
        document, pdf, gate, store, ENGINE_ID, CONTENT_ID, layoutVersion = null,
        target = target, sleep = sleep, nowMillis = nowMillis
    )

    private fun uniformTarget(currentPage: Int, pageCount: Int, longestEdgePx: Int, aspectOf: (Int) -> Float): DiskCacheFillTarget =
        DiskCacheFillTarget(currentPage, pageCount) { pageIndex -> ReaderGeometry.baseTierSpec(aspectOf(pageIndex), longestEdgePx) }

    @Test fun aPageWhoseWriteHasNotLandedYetIsNotRenderedAgain() {
        val gate = DocumentPriorityGate(nowMillis = FillFakeClock())
        val document = FillFakeDocument(pageCount = 3)
        val readerDocument = openDocument(document)
        val store = FillFakeStore(writesLandImmediately = false)

        val filler = filler(readerDocument, document, gate, store) {
            uniformTarget(currentPage = 1, pageCount = 3, longestEdgePx = 100) { 0.5f }
        }
        filler.start()

        awaitTrue { store.writes.size >= 3 }
        Thread.sleep(NOT_HAPPENING_MILLIS)
        filler.dispose()

        assertEquals(listOf(0, 1, 2), store.writes.map { it.first.pageIndex }.sorted())
        assertEquals(3, document.renderCalls.size)
    }

    @Test fun aPageThatFailsWithoutBeingPreemptedIsNotRetriedForever() {
        val gate = DocumentPriorityGate(nowMillis = FillFakeClock())
        val document = FillFakeDocument(pageCount = 1)
        document.failNextRenderWith = IllegalStateException("engine failure")
        val readerDocument = openDocument(document)
        val store = FillFakeStore()

        val filler = filler(readerDocument, document, gate, store) {
            uniformTarget(currentPage = 0, pageCount = 1, longestEdgePx = 100) { 0.5f }
        }
        filler.start()

        Thread.sleep(NOT_HAPPENING_MILLIS)
        filler.dispose()

        assertTrue(store.writes.isEmpty())
        assertTrue(document.renderCalls.isEmpty())
    }

    @Test fun doesNotRenderBeforeTheIdlePermit() {
        val clock = FillFakeClock()
        val gate = DocumentPriorityGate(nowMillis = clock)
        val document = FillFakeDocument(pageCount = 3)
        val readerDocument = openDocument(document)
        val store = FillFakeStore()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        val foreground = Thread {
            gate.foreground {
                entered.countDown()
                release.awaitIgnoringInterrupts()
            }
        }
        foreground.start()
        assertTrue(entered.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        val filler = filler(readerDocument, document, gate, store) {
            uniformTarget(currentPage = 1, pageCount = 3, longestEdgePx = 100) { 0.5f }
        }
        filler.start()

        Thread.sleep(NOT_HAPPENING_MILLIS)
        assertTrue(document.renderCalls.isEmpty())

        release.countDown()
        foreground.join(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS))
        clock.advanceBy(QUIET_MILLIS)

        awaitTrue { document.renderCalls.isNotEmpty() }
        filler.dispose()
    }

    @Test fun abortsAndRetriesAPageWhenAForegroundBlockOpensMidRender() {
        val clock = FillFakeClock()
        val gate = DocumentPriorityGate(nowMillis = clock)
        val document = FillFakeDocument(pageCount = 3)
        val readerDocument = openDocument(document)
        val store = FillFakeStore()
        val firstAttemptGate = CountDownLatch(1)
        document.renderGate = firstAttemptGate

        val filler = filler(readerDocument, document, gate, store) {
            uniformTarget(currentPage = 1, pageCount = 3, longestEdgePx = 100) { 0.5f }
        }
        filler.start()

        awaitTrue { document.concurrentRenders.get() == 1 }
        gate.foreground {}
        clock.advanceBy(QUIET_MILLIS)

        // Swapped in before the first attempt is released, so the retry it triggers is caught here
        // too, and never races ahead of the assertion just below.
        val retryGate = CountDownLatch(1)
        document.renderGate = retryGate
        firstAttemptGate.countDown()

        awaitTrue { document.renderCalls.size >= 1 }
        assertTrue("the aborted attempt must never reach the store", store.writes.isEmpty())

        retryGate.countDown()
        awaitTrue { store.writes.isNotEmpty() }
        filler.dispose()
    }

    @Test fun neverRunsTwoRendersAtOnce() {
        val gate = DocumentPriorityGate(nowMillis = FillFakeClock())
        val document = FillFakeDocument(pageCount = 20)
        val readerDocument = openDocument(document)
        val store = FillFakeStore()

        val filler = filler(readerDocument, document, gate, store) {
            uniformTarget(currentPage = 10, pageCount = 20, longestEdgePx = 64) { 0.5f }
        }
        filler.start()

        awaitTrue { store.writes.size >= 5 }
        filler.dispose()

        assertEquals(1, document.maxConcurrentRenders.get())
    }

    @Test fun backsOffWhenTheWriteQueueIsFull() {
        val gate = DocumentPriorityGate(nowMillis = FillFakeClock())
        val document = FillFakeDocument(pageCount = 3)
        val readerDocument = openDocument(document)
        val store = FillFakeStore(capacity = false)
        val backoffSleeps = CountDownLatch(3)
        val observedMillis = CopyOnWriteArrayList<Long>()

        val filler = filler(
            readerDocument, document, gate, store,
            sleep = { millis -> observedMillis += millis; backoffSleeps.countDown() }
        ) { uniformTarget(currentPage = 1, pageCount = 3, longestEdgePx = 100) { 0.5f } }
        filler.start()

        assertTrue(backoffSleeps.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        filler.dispose()

        assertTrue(document.renderCalls.isEmpty())
        assertTrue(observedMillis.all { it == DISK_CACHE_FILL_QUEUE_BACKOFF_MILLIS })
    }

    @Test fun stopsPromptlyOnCloseEvenWhileWaitingForIdle() {
        val gate = DocumentPriorityGate(nowMillis = FillFakeClock())
        val document = FillFakeDocument(pageCount = 3)
        val readerDocument = openDocument(document)
        val store = FillFakeStore()
        val entered = CountDownLatch(1)
        val neverReleased = CountDownLatch(1)

        val foreground = Thread {
            gate.foreground {
                entered.countDown()
                neverReleased.awaitIgnoringInterrupts()
            }
        }
        foreground.start()
        assertTrue(entered.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        val filler = filler(readerDocument, document, gate, store) {
            uniformTarget(currentPage = 1, pageCount = 3, longestEdgePx = 100) { 0.5f }
        }
        filler.start()

        val disposed = CountDownLatch(1)
        Thread { filler.dispose(); disposed.countDown() }.start()
        assertTrue(disposed.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        neverReleased.countDown()
        foreground.join(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS))
    }

    @Test fun rendersWithTheSpecThePresentersBaseTierCodeYieldsForTheMeasuredAspect() {
        val aspects = mapOf(0 to 0.5f, 1 to 0.5f, 2 to 2.5f)
        val gate = DocumentPriorityGate(nowMillis = FillFakeClock())
        val document = FillFakeDocument(pageCount = 3, width = { aspects.getValue(it) }, height = { 1f })
        val readerDocument = openDocument(document)
        val store = FillFakeStore()

        val presenter = ReaderPresenter(
            pageCount = 3,
            cacheBudgetBytes = 64L * 1024 * 1024,
            releaseValue = {},
            pageAspect = readerDocument::aspect,
            scheduleRetry = { _, _ -> },
            deliverToPresenter = { it() },
            onChanged = {},
            baseSchedulerFactory = { onOutcome -> ViewportScheduler(1, { request, _ -> RenderCandidate(Unit) {} }, onOutcome = onOutcome) }
        ) { onOutcome -> ViewportScheduler(1, { request, _ -> RenderCandidate(Unit) {} }, onOutcome = onOutcome) }
        presenter.setViewport(ReaderViewport(600, 900))

        val filler = filler(readerDocument, document, gate, store) {
            DiskCacheFillTarget(currentPage = 2, pageCount = 3, specForPage = presenter::currentBaseTierSpec)
        }
        filler.start()

        awaitTrue { store.writes.any { it.first.pageIndex == 2 } }
        filler.dispose()
        presenter.close()
        presenter.shutdown()

        val expectedSpec = requireNotNull(presenter.currentBaseTierSpec(2))
        val writtenKey = store.writes.first { it.first.pageIndex == 2 }.first
        assertEquals(expectedSpec.width, writtenKey.width)
        assertEquals(expectedSpec.height, writtenKey.height)
    }

    @Test fun writesTheEngineRasterBytesDirectlyWithoutEverBuildingABitmap() {
        val gate = DocumentPriorityGate(nowMillis = FillFakeClock())
        val document = FillFakeDocument(pageCount = 2)
        val readerDocument = openDocument(document)
        val store = FillFakeStore()

        val filler = filler(readerDocument, document, gate, store) {
            uniformTarget(currentPage = 0, pageCount = 2, longestEdgePx = 32) { 0.5f }
        }
        filler.start()

        awaitTrue { store.writes.size >= 2 }
        filler.dispose()

        store.writes.forEach { (key, rgba, _) -> assertEquals(key.width * key.height * 4, rgba.size) }
    }

    @Test fun restartsOnAChangedViewport() {
        val gate = DocumentPriorityGate(nowMillis = FillFakeClock())
        val document = FillFakeDocument(pageCount = 1)
        val readerDocument = openDocument(document)
        val store = FillFakeStore()
        val longestEdge = AtomicInteger(100)

        val filler = filler(readerDocument, document, gate, store) {
            uniformTarget(currentPage = 0, pageCount = 1, longestEdgePx = longestEdge.get()) { 0.5f }
        }
        filler.start()

        awaitTrue { store.writes.size >= 1 }
        val firstKey = store.writes.single().first

        longestEdge.set(200)
        awaitTrue { store.writes.size >= 2 }
        filler.dispose()

        val secondKey = store.writes[1].first
        assertTrue("a changed viewport must produce a different disk key", firstKey != secondKey)
    }

    /** Reports whether page 1's assumed shape ever disagreed with what [FillFakeDocument] measures it as. */
    @Test fun nearPagesAreFilledBackToBackWithoutPacing() {
        val clock = FillFakeClock()
        val gate = DocumentPriorityGate(nowMillis = clock)
        val document = FillFakeDocument(pageCount = 10)
        val readerDocument = openDocument(document)
        val store = FillFakeStore()
        val sleeps = CopyOnWriteArrayList<Long>()

        val filler = filler(readerDocument, document, gate, store, sleep = { sleeps += it }, nowMillis = clock) {
            uniformTarget(currentPage = 5, pageCount = 10, longestEdgePx = 32) { 0.5f }
        }
        filler.start()

        awaitTrue { store.writes.size >= 10 }
        filler.dispose()

        assertTrue("no far-page pacing sleep expected within the window", sleeps.none { it == DISK_CACHE_FILL_FAR_WAIT_POLL_MILLIS })
    }

    @Test fun farPagesAreSpacedByTheInterval() {
        val clock = FillFakeClock()
        val gate = DocumentPriorityGate(nowMillis = clock)
        val document = FillFakeDocument(pageCount = 200)
        val readerDocument = openDocument(document)
        val store = FillFakeStore()
        val longestEdge = 32
        val aspect = 0.5f
        val spec = ReaderGeometry.baseTierSpec(aspect, longestEdge)
        (0..DISK_CACHE_FILL_WINDOW_RADIUS_PAGES).forEach { page ->
            store.present += requireNotNull(DiskPageCacheKey.forWholePageSpec(ENGINE_ID, CONTENT_ID, null, page, spec))
        }

        val filler = filler(
            readerDocument, document, gate, store,
            sleep = { clock.advanceBy(it) },
            nowMillis = clock
        ) { uniformTarget(currentPage = 0, pageCount = 200, longestEdgePx = longestEdge) { aspect } }
        filler.start()

        awaitTrue { store.writes.size >= 3 }
        filler.dispose()

        val farWrites = store.writes.filter { it.first.pageIndex > DISK_CACHE_FILL_WINDOW_RADIUS_PAGES }
        assertTrue("expected at least two paced far writes", farWrites.size >= 2)
        assertTrue(
            "two paced far writes after the free first one must cost at least two intervals",
            clock.invoke() >= 2 * DISK_CACHE_FILL_FAR_PAGE_INTERVAL_MILLIS
        )
    }

    @Test fun aPageChangeDuringTheFarPageWaitLetsTheNewNearWindowThroughImmediately() {
        val clock = FillFakeClock()
        val gate = DocumentPriorityGate(nowMillis = clock)
        val document = FillFakeDocument(pageCount = 200)
        val readerDocument = openDocument(document)
        val store = FillFakeStore()
        val longestEdge = 32
        val aspect = 0.5f
        val spec = ReaderGeometry.baseTierSpec(aspect, longestEdge)
        (0..DISK_CACHE_FILL_WINDOW_RADIUS_PAGES).forEach { page ->
            store.present += requireNotNull(DiskPageCacheKey.forWholePageSpec(ENGINE_ID, CONTENT_ID, null, page, spec))
        }
        val currentPage = AtomicInteger(0)

        val filler = filler(
            readerDocument, document, gate, store,
            sleep = { /* no-op: simulates a wait that never elapses on its own */ },
            nowMillis = clock
        ) { uniformTarget(currentPage = currentPage.get(), pageCount = 200, longestEdgePx = longestEdge) { aspect } }
        filler.start()

        // The first far page fills immediately (nothing paces the very first far write), then the
        // second is stuck waiting out the interval since the fake sleep never advances the clock.
        awaitTrue { store.writes.size >= 1 }
        currentPage.set(150)

        awaitTrue { store.writes.any { it.first.pageIndex == 150 } }
        filler.dispose()

        val page150WriteIndex = store.writes.indexOfFirst { it.first.pageIndex == 150 }
        assertTrue("page 150 must land before any further far page beyond the stalled wait", page150WriteIndex >= 1)
    }

    @Test fun closeDuringTheFarPageWaitReturnsPromptly() {
        val clock = FillFakeClock()
        val gate = DocumentPriorityGate(nowMillis = clock)
        val document = FillFakeDocument(pageCount = 200)
        val readerDocument = openDocument(document)
        val store = FillFakeStore()
        val longestEdge = 32
        val aspect = 0.5f
        val spec = ReaderGeometry.baseTierSpec(aspect, longestEdge)
        (0..DISK_CACHE_FILL_WINDOW_RADIUS_PAGES).forEach { page ->
            store.present += requireNotNull(DiskPageCacheKey.forWholePageSpec(ENGINE_ID, CONTENT_ID, null, page, spec))
        }

        val filler = filler(
            readerDocument, document, gate, store,
            sleep = { /* never advances the clock, so the far-page wait never elapses on its own */ },
            nowMillis = clock
        ) { uniformTarget(currentPage = 0, pageCount = 200, longestEdgePx = longestEdge) { aspect } }
        filler.start()

        awaitTrue { store.writes.size >= 1 }

        val disposed = CountDownLatch(1)
        Thread { filler.dispose(); disposed.countDown() }.start()
        assertTrue(disposed.await(SETTLE_SECONDS, TimeUnit.SECONDS))
    }

    @Test fun discardsARenderTakenUnderTheWrongAssumedAspectAndPersistsOnlyTheCorrectedOne() {
        val gate = DocumentPriorityGate(nowMillis = FillFakeClock())
        // Page 0 is square; page 1 is not, so the reader's assumption for an unmeasured page 1 is wrong.
        val document = FillFakeDocument(pageCount = 2, width = { index -> if (index == 0) 1f else 3f }, height = { 1f })
        val readerDocument = openDocument(document)
        val store = FillFakeStore()

        val filler = filler(readerDocument, document, gate, store) {
            uniformTarget(currentPage = 1, pageCount = 2, longestEdgePx = 90) { readerDocument.aspect(it) }
        }
        filler.start()

        awaitTrue { store.writes.any { it.first.pageIndex == 1 } }
        filler.dispose()

        val correctSpec = ReaderGeometry.baseTierSpec(3f, 90)
        val written = store.writes.first { it.first.pageIndex == 1 }
        assertEquals(correctSpec.width, written.first.width)
        assertEquals(correctSpec.height, written.first.height)
        assertEquals(3f, readerDocument.aspect(1))
    }
}

private fun CountDownLatch.awaitIgnoringInterrupts() {
    while (count > 0) {
        try {
            await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
