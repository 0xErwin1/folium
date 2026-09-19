package com.folium.reader.reader

import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.Raster
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.text.TextBlock
import com.folium.reader.core.text.TextFont
import com.folium.reader.core.text.TextLine
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextPageMatch
import com.folium.reader.core.text.TextPageMatcher
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextWord
import com.folium.reader.core.library.BookId
import com.folium.reader.core.ocr.OcrCancellationReason
import com.folium.reader.core.ocr.OcrFailureMetadata
import com.folium.reader.core.ocr.OcrPageState
import com.folium.reader.core.ocr.OcrPageStatus
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextSearchSpec
import com.folium.reader.core.text.MAX_TEXT_SEARCH_RESULTS
import com.folium.reader.index.DocumentContentVersion
import com.folium.reader.index.TextPageIndexKey
import com.folium.reader.index.TextPageIndex
import com.folium.reader.index.TextPageIndexState
import com.folium.reader.index.TextPageIndexWriteOutcome
import com.folium.reader.index.TextPagePublicationOutcome
import com.folium.reader.index.TextPageSearchHit
import com.folium.reader.index.TransientTextPageIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * How long a wait for something that must happen is allowed to take.
 *
 * Deliberately far longer than any of these ever needs. Every one of them waits on a thread pool
 * publishing an outcome, which on an idle machine lands in milliseconds — the budget only matters on
 * a loaded one, where a two second ceiling was being missed by scheduling rather than by anything
 * this suite is about. A generous ceiling costs a correct implementation nothing, because it is
 * never reached, and costs a broken one only the time it was going to fail in anyway.
 */
private const val SETTLE_SECONDS = 30L

/**
 * And how long a wait for something that must NOT happen is given to disprove itself. This one has
 * to stay short: it is paid in full on every run, by every passing test that uses it.
 */
private const val NOT_HAPPENING_MILLIS = 100L

class TextPageLoaderTest {
    private class CapturingThreadFactory : (Runnable) -> Thread {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        @Volatile var thread: Thread? = null

        override fun invoke(runnable: Runnable): Thread = Thread(runnable, "reader-text-test").apply {
            isDaemon = true
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught += error }
            thread = this
        }
    }

    @Test fun terminalPublicationRejectsCapturedRunningWorkUntilNextGeneration() {
        val gate = SearchPublicationGate(initialGeneration = 7)
        val runningClaim = requireNotNull(gate.claim(running = true))
        val runningCaptured = CountDownLatch(1)
        val releaseRunning = CountDownLatch(1)
        val staleFinished = CountDownLatch(1)
        val delivered = CopyOnWriteArrayList<Boolean>()
        val stale = Thread {
            runningCaptured.countDown()
            releaseRunning.awaitIgnoringInterrupts()
            if (gate.canDeliver(runningClaim)) delivered += true
            staleFinished.countDown()
        }
        stale.start()
        assertTrue(runningCaptured.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        val terminalClaim = requireNotNull(gate.claim(running = false))
        if (gate.canDeliver(terminalClaim)) delivered += false
        releaseRunning.countDown()
        assertTrue(staleFinished.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        stale.join()

        assertEquals(listOf(false), delivered)
        assertNull(gate.claim(running = false))
        assertNull(gate.claim(running = true))

        gate.nextGeneration()
        val freshRunning = requireNotNull(gate.claim(running = true))
        if (gate.canDeliver(freshRunning)) delivered += true
        assertEquals(listOf(false, true), delivered)
    }

    @Test fun newerRevisionBeforeTerminalClaimRejectsStaleContextWithoutConsumingTerminal() {
        val request = TextPageLoader.SearchRequest(
            generation = 1,
            spec = TextSearchSpec("needle"),
            callback = {},
            pageCount = 1,
            onResultPageAggregated = {}
        )
        request.recordPageUpdate(0, TextSource.NATIVE_PDF)
        val contextA = request.capturePublicationContext()
        val aCaptured = CountDownLatch(1)
        val bRecorded = CountDownLatch(1)
        val aFinished = CountDownLatch(1)
        val staleClaim = AtomicReference<SearchPublicationClaim?>()
        val stale = Thread {
            aCaptured.countDown()
            bRecorded.awaitIgnoringInterrupts()
            staleClaim.set(request.claimPublication(contextA, false, true, 1L))
            aFinished.countDown()
        }
        stale.start()
        assertTrue(aCaptured.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        val updateB = request.recordPageUpdate(0, TextSource.OCR)
        val contextB = request.capturePublicationContext()
        bRecorded.countDown()
        assertTrue(aFinished.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        stale.join()

        assertNull(staleClaim.get())
        assertEquals(listOf(updateB), contextB.pageUpdates)
        val latestClaim = requireNotNull(request.claimPublication(contextB, false, true, 2L))
        assertTrue(request.canDeliver(contextB, latestClaim))
    }

    @Test fun newerRevisionAfterTerminalClaimSuppressesStaleDeliveryAndPublishesLatest() {
        val request = TextPageLoader.SearchRequest(
            generation = 1,
            spec = TextSearchSpec("needle"),
            callback = {},
            pageCount = 1,
            onResultPageAggregated = {}
        )
        request.recordPageUpdate(0, TextSource.NATIVE_PDF)
        val contextA = request.capturePublicationContext()
        val aClaimed = CountDownLatch(1)
        val bRecorded = CountDownLatch(1)
        val aFinished = CountDownLatch(1)
        val staleDelivered = AtomicBoolean()
        val stale = Thread {
            val claim = requireNotNull(request.claimPublication(contextA, false, true, 1L))
            aClaimed.countDown()
            bRecorded.awaitIgnoringInterrupts()
            staleDelivered.set(request.canDeliver(contextA, claim))
            aFinished.countDown()
        }
        stale.start()
        assertTrue(aClaimed.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        val updateB = request.recordPageUpdate(0, TextSource.OCR)
        val contextB = request.capturePublicationContext()
        bRecorded.countDown()
        assertTrue(aFinished.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        stale.join()

        assertFalse(staleDelivered.get())
        assertEquals(listOf(updateB), contextB.pageUpdates)
        val latestClaim = requireNotNull(request.claimPublication(contextB, false, true, 2L))
        assertTrue(request.canDeliver(contextB, latestClaim))
    }

    private class FakeDocument(
        override val pageCount: Int,
        private val extract: (Int) -> TextPage
    ) : PdfDocument {
        val extracting = AtomicBoolean(false)
        var closedWhileExtracting = false

        override fun pageInfo(index: Int) = PageInfo(index, 1f, 1f, 0)
        override fun buildDisplayList(index: Int): DisplayList = object : DisplayList {
            override fun render(spec: RenderSpec, cancellationSignal: CancellationSignal) = Raster(1, 1, ByteArray(4))
            override fun close() = Unit
        }
        override fun extractText(index: Int): TextPage {
            extracting.set(true)
            return try {
                extract(index)
            } finally {
                extracting.set(false)
            }
        }
        override fun outline(): List<OutlineEntry> = emptyList()
        override fun metadata() = com.folium.reader.core.pdf.DocumentMetadata.NONE
        override fun close() {
            closedWhileExtracting = extracting.get()
        }
    }

    private val emptyPage = TextPage(emptyList(), TextSource.NATIVE_PDF)

    private fun page(text: String): TextPage = TextPage(
        listOf(TextBlock(listOf(TextLine(listOf(
            TextWord(text, PageSpaceRect(.1f, .1f, .9f, .2f), 0)
        ), 0)), 0)),
        TextSource.NATIVE_PDF
    )

    @Test fun sameActivePageDeduplicatesAndKeepsOnlyTheLatestPublicationOwner() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val deliveredOwners = Collections.synchronizedList(mutableListOf<Int>())
        val document = FakeDocument(10) {
            calls.incrementAndGet()
            entered.countDown()
            release.awaitIgnoringInterrupts()
            emptyPage
        }
        val loader = TextPageLoader(document, 10, deliver = { it() })

        loader.load(4) { deliveredOwners += 0 }
        assertTrue(entered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        repeat(100) { owner -> loader.load(4) { deliveredOwners += owner + 1 } }
        assertEquals(0, loader.queuedPageCount())

        release.countDown()
        waitUntil { deliveredOwners.isNotEmpty() }
        assertEquals(1, calls.get())
        assertEquals(listOf(100), deliveredOwners)
        loader.dispose()
    }

    @Test fun massiveNavigationKeepsOneLatestWinsQueuedPage() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val extracted = Collections.synchronizedList(mutableListOf<Int>())
        val delivered = Collections.synchronizedList(mutableListOf<Int>())
        val document = FakeDocument(1_000) { index ->
            extracted += index
            if (index == 0) {
                entered.countDown()
                release.awaitIgnoringInterrupts()
            }
            emptyPage
        }
        val loader = TextPageLoader(document, 1_000, deliver = { it() })

        loader.load(0) { delivered += 0 }
        assertTrue(entered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        (1 until 1_000).forEach { page -> loader.load(page) { delivered += page } }

        assertEquals(1, loader.queuedPageCount())
        release.countDown()
        waitUntil { delivered.isNotEmpty() }
        assertEquals(listOf(0, 999), extracted)
        assertEquals(listOf(999), delivered)
        loader.dispose()
    }

    @Test fun publicationQueuedForAnOlderOwnerIsSuppressedAfterSupersession() {
        val publications = CopyOnWriteArrayList<() -> Unit>()
        val extracted = AtomicInteger()
        val document = FakeDocument(2) { extracted.incrementAndGet(); emptyPage }
        val loader = TextPageLoader(document, 2, deliver = { publications += it })
        val delivered = Collections.synchronizedList(mutableListOf<Int>())

        loader.load(0) { delivered += 0 }
        waitUntil { publications.size == 1 }
        loader.load(1) { delivered += 1 }
        waitUntil { publications.size == 2 }
        publications[0].invoke()
        assertTrue(delivered.isEmpty())

        publications[1].invoke()
        assertEquals(listOf(1), delivered)
        assertEquals(2, extracted.get())
        loader.dispose()
    }

    @Test fun closeCancelsAQueuedPublicationWaitWithoutAbandoningThePostedAction() {
        val publications = CopyOnWriteArrayList<() -> Unit>()
        val callbacks = AtomicInteger()
        val loader = TextPageLoader(FakeDocument(1) { emptyPage }, 1, deliver = { publications += it })

        loader.load(0) { callbacks.incrementAndGet() }
        waitUntil { publications.size == 1 }
        loader.close()

        val disposed = CountDownLatch(1)
        Thread { loader.dispose(); disposed.countDown() }.start()
        assertTrue(disposed.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        publications.single().invoke()
        assertEquals(0, callbacks.get())
    }

    @Test fun interruptingPublicationWaitDoesNotAbandonThePostedCallback() {
        val publications = CopyOnWriteArrayList<() -> Unit>()
        val delivered = CountDownLatch(1)
        val threads = CapturingThreadFactory()
        val loader = TextPageLoader(
            FakeDocument(1) { emptyPage }, 1, deliver = { publications += it }, threadFactory = threads
        )

        loader.load(0) { delivered.countDown() }
        waitUntil { publications.size == 1 }
        requireNotNull(threads.thread).interrupt()
        assertFalse(delivered.await(NOT_HAPPENING_MILLIS, TimeUnit.MILLISECONDS))
        publications.single().invoke()

        assertTrue(delivered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        loader.dispose()
        assertTrue(threads.uncaught.isEmpty())
    }

    @Test fun cacheEvictsByEstimatedBytesInsteadOfEntryCount() {
        val first = page("a".repeat(2_000))
        val second = page("b".repeat(2_000))
        val onePageBudget = estimateTextPageBytes(first) + 64L
        val calls = AtomicInteger()
        val document = FakeDocument(3) { index -> calls.incrementAndGet(); if (index == 0) first else second }
        val loader = TextPageLoader(document, 3, deliver = { it() }, maxCacheBytes = onePageBudget)

        loadAndWait(loader, 0)
        loadAndWait(loader, 1)

        assertEquals(1, loader.cachedPageCount())
        assertTrue(loader.cachedBytes() <= onePageBudget)
        loadAndWait(loader, 0)
        assertEquals(3, calls.get())
        loader.dispose()
    }

    @Test fun oversizedTextPageIsPublishedButNeverAdmittedToCache() {
        val large = page("x".repeat(100_000))
        val calls = AtomicInteger()
        val document = FakeDocument(1) { calls.incrementAndGet(); large }
        val loader = TextPageLoader(document, 1, deliver = { it() }, maxCacheBytes = 1_024L)

        loadAndWait(loader, 0)
        loadAndWait(loader, 0)

        assertEquals(2, calls.get())
        assertEquals(0, loader.cachedPageCount())
        assertEquals(0L, loader.cachedBytes())
        assertTrue(estimateTextPageBytes(large) > 200_000L)
        loader.dispose()
    }

    @Test fun byteEstimateAccountsForHierarchyDerivedStringsFontsAndGeometryOwners() {
        val minimal = page("word")
        val rich = TextPage(
            listOf(
                TextBlock(listOf(TextLine(listOf(
                    TextWord(
                        "word",
                        PageSpaceRect(.1f, .1f, .3f, .2f),
                        0,
                        fonts = listOf(TextFont("Long Font Name", false, false, false, false)),
                        languageTag = "en-US"
                    ),
                    TextWord("second", PageSpaceRect(.4f, .1f, .7f, .2f), 1)
                ), 0)), 0),
                TextBlock(listOf(TextLine(listOf(
                    TextWord("third", PageSpaceRect(.1f, .4f, .4f, .5f), 0)
                ), 0)), 1)
            ),
            TextSource.OCR
        )

        assertTrue(estimateTextPageBytes(rich) > estimateTextPageBytes(minimal))
    }

    @Test fun extractionFailurePublishesFailedWithoutLeavingCachedOrQueuedState() {
        val document = FakeDocument(1) { throw IllegalStateException("broken text layer") }
        val loader = TextPageLoader(document, 1, deliver = { it() })
        val delivered = CountDownLatch(1)
        var result: TextPageLoadResult? = null

        loader.load(0) {
            result = it
            delivered.countDown()
        }

        assertTrue(delivered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertEquals(TextPageLoadResult.Failed, result)
        assertEquals(0, loader.cachedPageCount())
        assertEquals(0, loader.queuedPageCount())
        loader.dispose()
    }

    @Test fun closeSuppressesLatePublicationAndDisposeDrainsBeforeDocumentClose() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val document = FakeDocument(2) {
            entered.countDown()
            release.awaitIgnoringInterrupts()
            emptyPage
        }
        val threads = CapturingThreadFactory()
        val loader = TextPageLoader(document, 2, deliver = { it() }, threadFactory = threads)

        loader.load(0) { callbacks.incrementAndGet() }
        assertTrue(entered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        loader.close()

        val disposed = CountDownLatch(1)
        Thread { loader.dispose(); disposed.countDown() }.start()
        assertFalse(disposed.await(NOT_HAPPENING_MILLIS, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(disposed.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        document.close()

        assertEquals(0, callbacks.get())
        assertFalse(document.closedWhileExtracting)
        assertTrue(threads.uncaught.isEmpty())
    }

    @Test fun closingAnIdleLoaderWakesItsWorkerWithoutAnUncaughtFailure() {
        val threads = CapturingThreadFactory()
        val loader = TextPageLoader(FakeDocument(1) { emptyPage }, 1, deliver = { it() }, threadFactory = threads)
        waitUntil { threads.thread?.state == Thread.State.WAITING }

        loader.dispose()

        assertFalse(requireNotNull(threads.thread).isAlive)
        assertTrue(threads.uncaught.isEmpty())
    }

    @Test fun repeatedOpenAndBackShutdownDoesNotLeakUncaughtWorkerFailures() {
        repeat(50) {
            val threads = CapturingThreadFactory()
            val loader = TextPageLoader(FakeDocument(1) { emptyPage }, 1, deliver = { it() }, threadFactory = threads)
            waitUntil { threads.thread?.state == Thread.State.WAITING }

            loader.dispose()

            assertFalse("cycle $it left its worker alive", requireNotNull(threads.thread).isAlive)
            assertTrue("cycle $it had uncaught ${threads.uncaught}", threads.uncaught.isEmpty())
        }
    }

    @Test fun searchPublishesExistingMatchesImmediatelyThenIndexesMissingPagesSequentially() {
        val index = TransientTextPageIndex()
        val key: (Int) -> TextPageIndexKey = { pageIndex -> searchKey().copy(pageIndex = pageIndex) }
        prepare(index, key(0))
        index.complete(key(0), page("needle-existing"))
        val extracted = Collections.synchronizedList(mutableListOf<Int>())
        val loader = TextPageLoader(
            FakeDocument(3) { pageIndex -> extracted += pageIndex; page("needle-$pageIndex") },
            3,
            deliver = { it() },
            index = index,
            indexKey = key
        )
        val progress = CopyOnWriteArrayList<TextSearchProgress>()

        loader.search("NÉEDLE") { progress += it }
        waitUntil { progress.lastOrNull()?.running == false }

        assertTrue(progress.first().indexedPages in 0..3)
        assertEquals(listOf(1, 2), extracted)
        assertEquals(listOf(0, 1, 2), progress.last().matches.map { it.pageIndex })
        loader.dispose()
        index.close()
    }

    /**
     * A reflowable document's key carries a non-null [TextPageIndexKey.layoutVersion]. This must reach
     * the same terminal, non-running publication a fixed-layout (null layout) search does — see
     * `RoomTextPageIndex.selectedCurrentPage`'s doc for the production bug this guards against: that
     * class's own currency re-check used to consult `ActiveTextSourceEntity`'s own layout (always
     * empty), not the page's, so a reflowable book's search never reached CURRENT and sat at
     * "running=true" forever. [TransientTextPageIndex] keys everything by the whole
     * [TextPageIndexKey] already, so it never had that specific bug; this test instead pins
     * [TextPageLoader]'s own contract — that it plumbs a non-null layout version through search like
     * any other key field — so a future regression on the [TextPageLoader] side would still be caught
     * here even though the RoomTextPageIndex-specific defect needs the index-level test to catch it.
     */
    @Test fun searchWithANonNullLayoutVersionReachesATerminalPublication() {
        val index = TransientTextPageIndex()
        val key: (Int) -> TextPageIndexKey = { pageIndex ->
            searchKey().copy(pageIndex = pageIndex, layoutVersion = "layout-9b7e8b")
        }
        prepare(index, key(0))
        val extracted = Collections.synchronizedList(mutableListOf<Int>())
        val loader = TextPageLoader(
            FakeDocument(3) { pageIndex -> extracted += pageIndex; page("needle-$pageIndex") },
            3,
            deliver = { it() },
            index = index,
            indexKey = key
        )
        val progress = CopyOnWriteArrayList<TextSearchProgress>()

        loader.search("needle") { progress += it }
        waitUntil { progress.lastOrNull()?.running == false }

        assertEquals(listOf(0, 1, 2), progress.last().matches.map { it.pageIndex })
        loader.dispose()
        index.close()
    }

    /**
     * A book fully indexed under layout A, then reopened under layout B, must claim and extract
     * every page again under B, and a search must find only B's text — never A's stale rows. Guards
     * `TransientTextPageIndex.pageStatesIfCurrent`/`searchCoverageIfCurrent` (must scope by layout,
     * exactly like `RoomTextPageIndex.pageStates`/`nativeCoverage` do at the SQL level) and
     * `SearchCoverage.claimNextPage()`'s consumer of that map: without the layout filter, A's COMPLETE
     * rows leak into B's coverage, `claimNextPage()` believes B is already fully indexed, the
     * background loop parks, and search under B only ever finds whatever few pages a foreground
     * `load()` happened to extract directly.
     */
    @Test fun bookIndexedUnderOneLayoutReindexesFullyAndSearchesOnlyTheOtherWhenReopenedUnderIt() {
        val index = TransientTextPageIndex()
        val pageCount = 4
        val layoutAKey: (Int) -> TextPageIndexKey = { pageIndex ->
            searchKey().copy(pageIndex = pageIndex, layoutVersion = "layout-a")
        }
        val layoutBKey: (Int) -> TextPageIndexKey = { pageIndex ->
            searchKey().copy(pageIndex = pageIndex, layoutVersion = "layout-b")
        }
        prepare(index, layoutAKey(0))

        val loaderA = TextPageLoader(
            FakeDocument(pageCount) { pageIndex -> page("alpha-$pageIndex") },
            pageCount,
            deliver = { it() },
            index = index,
            indexKey = layoutAKey
        )
        waitUntil { index.pageStatesIfCurrent(layoutAKey(0))?.size == pageCount }
        loaderA.dispose()

        val extractedUnderB = Collections.synchronizedList(mutableListOf<Int>())
        val loaderB = TextPageLoader(
            FakeDocument(pageCount) { pageIndex -> extractedUnderB += pageIndex; page("beta-$pageIndex") },
            pageCount,
            deliver = { it() },
            index = index,
            indexKey = layoutBKey
        )
        waitUntil { extractedUnderB.size == pageCount }
        assertEquals((0 until pageCount).toList(), extractedUnderB.sorted())

        val progress = CopyOnWriteArrayList<TextSearchProgress>()
        loaderB.search("beta") { progress += it }
        waitUntil { progress.lastOrNull()?.running == false }
        assertEquals((0 until pageCount).toList(), progress.last().matches.map { it.pageIndex }.sorted())

        val alphaProgress = CopyOnWriteArrayList<TextSearchProgress>()
        loaderB.search("alpha") { alphaProgress += it }
        waitUntil { alphaProgress.lastOrNull()?.running == false }
        assertTrue(alphaProgress.last().matches.isEmpty())

        loaderB.dispose()
        index.close()
    }

    @Test fun everyLoaderRetainsItsOwnLayoutOnceOnItsWorkerThreadBeforeExtractionStarts() {
        val delegate = TransientTextPageIndex()
        val recording = RetentionRecordingIndex(delegate)
        val key: (Int) -> TextPageIndexKey = { pageIndex ->
            searchKey().copy(pageIndex = pageIndex, layoutVersion = "layout-a")
        }
        prepare(recording, key(0))

        val loader = TextPageLoader(
            FakeDocument(2) { pageIndex -> page("page-$pageIndex") },
            2,
            deliver = { it() },
            index = recording,
            indexKey = key
        )
        waitUntil { recording.retainedLayouts.isNotEmpty() }

        assertEquals(
            listOf(Triple(key(0).bookId, key(0).documentVersion, "layout-a")),
            recording.retainedLayouts.toList()
        )
        assertEquals(listOf("reader-text"), recording.retainedOnThreads.toList())

        loader.dispose()
        recording.close()
    }

    /**
     * The empty-layout no-op itself is [RoomTextPageIndex.retainRecentLayouts]'s own decision, not
     * this loader's — this only pins that a fixed-layout loader passes that decision the empty
     * string [TextPageIndexKey.layoutVersion] already normalizes to, exactly like every other call
     * site that reads it.
     */
    @Test fun aFixedLayoutLoaderPassesAnEmptyLayoutVersionToRetention() {
        val delegate = TransientTextPageIndex()
        val recording = RetentionRecordingIndex(delegate)
        val key: (Int) -> TextPageIndexKey = { pageIndex -> searchKey().copy(pageIndex = pageIndex) }
        prepare(recording, key(0))

        val loader = TextPageLoader(
            FakeDocument(1) { page("fixed-layout") },
            1,
            deliver = { it() },
            index = recording,
            indexKey = key
        )
        waitUntil { recording.retainCalls.get() > 0 }

        assertEquals(listOf(Triple(key(0).bookId, key(0).documentVersion, "")), recording.retainedLayouts.toList())

        loader.dispose()
        recording.close()
    }

    private class RetentionRecordingIndex(
        private val delegate: TextPageIndex
    ) : TextPageIndex by delegate {
        val retainCalls = AtomicInteger()
        val retainedLayouts = CopyOnWriteArrayList<Triple<BookId, DocumentContentVersion, String>>()
        val retainedOnThreads = CopyOnWriteArrayList<String>()

        override fun retainRecentLayouts(
            bookId: BookId,
            documentVersion: DocumentContentVersion,
            layoutVersion: String
        ) {
            retainCalls.incrementAndGet()
            retainedLayouts += Triple(bookId, documentVersion, layoutVersion)
            retainedOnThreads += Thread.currentThread().name
            delegate.retainRecentLayouts(bookId, documentVersion, layoutVersion)
        }
    }

    @Test fun autonomousIndexingRunsToCompletionWithoutSearch() {
        val harness = searchHarness(3) { index -> page("page-$index") }

        waitUntil { harness.extracted.size == 3 }

        assertEquals(listOf(0, 1, 2), harness.extracted)
        assertEquals(3, harness.index.pageStatesIfCurrent(harness.key(0))?.size)
        harness.close()
    }

    @Test fun backgroundSliceDoesNotExtractWhileForegroundIsOpenAndDoesOnceTheGateGoesQuiet() {
        val clock = MutableClock()
        val gate = DocumentPriorityGate(nowMillis = clock)
        val rendering = CountDownLatch(1)
        val releaseRendering = CountDownLatch(1)
        val renderer = Thread {
            gate.foreground {
                rendering.countDown()
                releaseRendering.awaitIgnoringInterrupts()
            }
        }
        renderer.start()
        assertTrue(rendering.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        val harness = searchHarness(1, priorityGate = gate) { index -> page("page-$index") }

        assertFalse(waitFor(NOT_HAPPENING_MILLIS) { harness.extracted.isNotEmpty() })

        releaseRendering.countDown()
        renderer.join(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS))
        assertFalse(waitFor(NOT_HAPPENING_MILLIS) { harness.extracted.isNotEmpty() })

        clock.advanceBy(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS))
        waitUntil { harness.extracted.size == 1 }
        harness.close()
    }

    @Test fun backgroundSliceWaitsForTheFirstForegroundBoundWhenAskedToAndNothingEverRenders() {
        val clock = MutableClock()
        val gate = DocumentPriorityGate(nowMillis = clock)

        val harness = searchHarness(
            1, priorityGate = gate, awaitFirstForegroundBeforeBackground = true
        ) { index -> page("page-$index") }

        assertFalse(waitFor(NOT_HAPPENING_MILLIS) { harness.extracted.isNotEmpty() })

        clock.advanceBy(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS))
        waitUntil { harness.extracted.size == 1 }
        harness.close()
    }

    @Test fun backgroundSliceStartsRightAfterTheFirstForegroundRenderWhenAskedToWaitForIt() {
        val clock = MutableClock()
        val gate = DocumentPriorityGate(nowMillis = clock)
        val rendering = CountDownLatch(1)
        val releaseRendering = CountDownLatch(1)
        val renderer = Thread {
            gate.foreground {
                rendering.countDown()
                releaseRendering.awaitIgnoringInterrupts()
            }
        }
        renderer.start()
        assertTrue(rendering.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        val harness = searchHarness(
            1, priorityGate = gate, awaitFirstForegroundBeforeBackground = true
        ) { index -> page("page-$index") }
        assertFalse(waitFor(NOT_HAPPENING_MILLIS) { harness.extracted.isNotEmpty() })

        releaseRendering.countDown()
        renderer.join(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS))
        assertFalse(waitFor(NOT_HAPPENING_MILLIS) { harness.extracted.isNotEmpty() })

        clock.advanceBy(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS))
        waitUntil { harness.extracted.size == 1 }
        harness.close()
    }

    @Test fun userDrivenLoadIssuedWhileTheWorkerWaitsForQuietIsServedWithoutWaitingForIt() {
        val clock = MutableClock()
        val gate = DocumentPriorityGate(nowMillis = clock)
        val rendering = CountDownLatch(1)
        val releaseRendering = CountDownLatch(1)
        val renderer = Thread {
            gate.foreground {
                rendering.countDown()
                releaseRendering.awaitIgnoringInterrupts()
            }
        }
        renderer.start()
        assertTrue(rendering.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        val harness = searchHarness(2, priorityGate = gate) { index -> page("page-$index") }
        assertFalse(waitFor(NOT_HAPPENING_MILLIS) { harness.extracted.isNotEmpty() })

        val delivered = CountDownLatch(1)
        var result: TextPageLoadResult? = null
        harness.loader.load(1) {
            result = it
            delivered.countDown()
        }

        assertTrue(
            "user-driven load waited behind the background quiet period",
            delivered.await(SETTLE_SECONDS, TimeUnit.SECONDS)
        )
        assertTrue(result is TextPageLoadResult.Loaded)
        assertEquals(listOf(1), harness.extracted)

        releaseRendering.countDown()
        renderer.join(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS))
        harness.close()
    }

    @Test fun closeWhileWaitingForTheQuietPeriodReturnsPromptly() {
        val clock = MutableClock()
        val gate = DocumentPriorityGate(nowMillis = clock)
        val rendering = CountDownLatch(1)
        val releaseRendering = CountDownLatch(1)
        val renderer = Thread {
            gate.foreground {
                rendering.countDown()
                releaseRendering.awaitIgnoringInterrupts()
            }
        }
        renderer.start()
        assertTrue(rendering.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        val harness = searchHarness(1, priorityGate = gate) { index -> page("page-$index") }
        assertFalse(waitFor(NOT_HAPPENING_MILLIS) { harness.extracted.isNotEmpty() })

        harness.loader.close()
        val disposed = CountDownLatch(1)
        Thread { harness.loader.dispose(); disposed.countDown() }.start()

        assertTrue(disposed.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertTrue(harness.extracted.isEmpty())

        releaseRendering.countDown()
        renderer.join(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS))
        harness.index.close()
    }

    @Test fun queryNeverExtractsNativeText() {
        val index = TransientTextPageIndex()
        val key = searchKey()
        prepare(index, key)
        index.complete(key, page("needle"))
        val extractions = AtomicInteger()
        val loader = TextPageLoader(
            FakeDocument(1) { extractions.incrementAndGet(); page("unexpected") },
            1, deliver = { it() }, index = index, indexKey = { key }
        )
        val completed = CountDownLatch(1)

        loader.search(TextSearchSpec("needle")) { if (!it.running) completed.countDown() }

        assertTrue(completed.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertEquals(0, extractions.get())
        loader.dispose()
        index.close()
    }

    @Test fun queryPublishesWhileNativeExtractionIsBlocked() {
        val index = TransientTextPageIndex()
        val key: (Int) -> TextPageIndexKey = { searchKey().copy(pageIndex = it) }
        prepare(index, key(0))
        index.complete(key(1), page("needle persisted"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loader = TextPageLoader(
            FakeDocument(2) { entered.countDown(); release.awaitIgnoringInterrupts(); page("background") },
            2, deliver = { it() }, index = index, indexKey = key
        )
        assertTrue(entered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        val queried = CountDownLatch(1)

        loader.search(TextSearchSpec("needle")) { if (it.matches.isNotEmpty()) queried.countDown() }

        assertTrue("query lane blocked behind native extraction", queried.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        release.countDown()
        loader.dispose()
        index.close()
    }

    @Test fun foregroundTextPreemptsCoverageBetweenPages() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val matcherCalls = IntArray(3)
        val harness = searchHarness(
            pageCount = 3,
            matchPage = { textPage, query ->
                matcherCalls[textPage.text.substringAfterLast('-').toInt()]++
                TextPageMatcher.find(textPage, query)
            }
        ) { index ->
            if (index == 0) { entered.countDown(); release.awaitIgnoringInterrupts() }
            page("page-$index")
        }
        val foreground = CountDownLatch(1)
        harness.loader.search("page") {}
        assertTrue(entered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        harness.loader.load(2) { foreground.countDown() }
        release.countDown()

        assertTrue(foreground.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        waitUntil { harness.extracted.size == 3 }
        assertEquals(listOf(0, 2, 1), harness.extracted)
        assertTrue(matcherCalls.sum() in 0..3)
        harness.close()
    }

    @Test fun latestSearchWinsAndFailedCoveragePageIsNotRetried() {
        val calls = IntArray(2)
        val firstExtraction = CountDownLatch(1)
        val releaseExtraction = CountDownLatch(1)
        val harness = searchHarness(2) { index ->
            calls[index]++
            if (index == 0) {
                firstExtraction.countDown()
                releaseExtraction.awaitIgnoringInterrupts()
            }
            if (index == 0) throw IllegalStateException("no text")
            page("new query")
        }
        assertTrue(firstExtraction.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        val old = AtomicInteger()
        val latest = CopyOnWriteArrayList<TextSearchProgress>()
        harness.loader.search("old") { old.incrementAndGet() }
        harness.loader.search("new") { latest += it }
        releaseExtraction.countDown()
        waitUntil { latest.lastOrNull()?.running == false }

        assertTrue("failed page retried more than once per query generation", calls[0] in 1..2)
        assertEquals(1, calls[1])
        assertEquals(1, latest.last().failedPages)
        assertEquals(listOf(1), latest.last().matches.map { it.pageIndex })
        harness.close()
    }

    @Test fun closingSearchSuppressesFurtherCoveragePublicationAndDisposeDrains() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deliveryAcknowledged = CountDownLatch(1)
        val delivery = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "search-delivery") }
        val index = TransientTextPageIndex()
        val key = searchKey()
        prepare(index, key)
        val loader = TextPageLoader(
            FakeDocument(2) { pageIndex ->
                entered.countDown()
                release.awaitIgnoringInterrupts()
                page("query-$pageIndex")
            },
            2,
            deliver = { callback -> delivery.execute(callback) },
            index = index,
            indexKey = { pageIndex -> key.copy(pageIndex = pageIndex) }
        )
        val publications = AtomicInteger()
        loader.search("query") {
            publications.incrementAndGet()
            deliveryAcknowledged.countDown()
        }
        assertTrue(entered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertTrue(deliveryAcknowledged.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        val beforeClose = publications.get()
        loader.closeSearch()
        release.countDown()
        loader.dispose()
        val deliveryDrained = CountDownLatch(1)
        delivery.execute(deliveryDrained::countDown)
        assertTrue(deliveryDrained.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertEquals(beforeClose, publications.get())
        delivery.shutdown()
        assertTrue(delivery.awaitTermination(SETTLE_SECONDS, TimeUnit.SECONDS))
        index.close()
    }

    @Test fun coverageFinishesUnusablePagesWithoutTextWhenTheSearchHasNoOcr() {
        val coverage = SearchCoverage(pageCount = 2, snapshot = emptyMap(), hasOcr = false)

        coverage.record(0, TextPageLoadResult.Loaded(emptyPage))
        coverage.record(1, TextPageLoadResult.Loaded(page("readable")))
        coverage.setMaintenancePending(false)

        val snapshot = coverage.snapshot()
        assertEquals(1, snapshot.withoutTextPages)
        assertEquals(1, snapshot.indexedPages)
        assertEquals(0, snapshot.pendingPages)
        assertEquals(0, snapshot.incompletePages)
        assertFalse(snapshot.running)
    }

    @Test fun coverageKeepsUnusablePagesPendingWhileTheSearchIncludesOcr() {
        val coverage = SearchCoverage(pageCount = 1, snapshot = emptyMap(), hasOcr = true)

        coverage.record(0, TextPageLoadResult.Loaded(emptyPage))

        val snapshot = coverage.snapshot()
        assertEquals(0, snapshot.withoutTextPages)
        assertEquals(1, snapshot.pendingPages)
        assertEquals(1, snapshot.incompletePages)
    }

    @Test fun searchCoverageSnapshotsRemainConsistentUnderHighContention() {
        val pageCount = 4_000
        val coverage = SearchCoverage(pageCount, emptyMap())
        val start = CountDownLatch(1)
        val workersDone = CountDownLatch(8)
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newFixedThreadPool(9)

        repeat(8) {
            executor.execute {
                start.awaitIgnoringInterrupts()
                try {
                    while (true) {
                        val pageIndex = coverage.claimNextPage() ?: break
                        coverage.record(pageIndex, TextPageLoadResult.Loaded(page("page-$pageIndex")))
                    }
                } catch (caught: Throwable) {
                    failure.compareAndSet(null, caught)
                } finally {
                    workersDone.countDown()
                }
            }
        }
        val snapshotsDone = CountDownLatch(1)
        executor.execute {
            start.awaitIgnoringInterrupts()
            try {
                var previousCompleted = 0
                var snapshots = 0
                while (workersDone.count > 0L) {
                    val snapshot = coverage.snapshot()
                    val completed = snapshot.indexedPages + snapshot.failedPages
                    check(snapshot.completePages.size == snapshot.indexedPages)
                    check(completed in previousCompleted..pageCount)
                    previousCompleted = completed
                    snapshots++
                    // Each snapshot copies the complete-page set while holding the same monitor the
                    // eight writers need. Spinning without pause starves them, so on a loaded
                    // machine the writers could not finish inside the deadline and the test failed
                    // for scheduling reasons rather than for a consistency violation.
                    Thread.yield()
                }
                check(snapshots > 0)
                val snapshot = coverage.snapshot()
                check(snapshot.completePages.size == pageCount)
                check(snapshot.indexedPages == pageCount)
                check(snapshot.failedPages == 0)
            } catch (caught: Throwable) {
                failure.compareAndSet(null, caught)
            } finally {
                snapshotsDone.countDown()
            }
        }

        start.countDown()
        assertTrue(workersDone.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertTrue(snapshotsDone.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError(it) }
        executor.shutdown()
        assertTrue(executor.awaitTermination(SETTLE_SECONDS, TimeUnit.SECONDS))
    }

    @Test fun selectedCoverageCountsRemainConsistentAcrossOutOfOrderOcrTransitions() {
        val coverage = SearchCoverage(
            4,
            mapOf(
                0 to TextPageIndexState.COMPLETE,
                1 to TextPageIndexState.COMPLETE,
                2 to TextPageIndexState.COMPLETE,
                3 to TextPageIndexState.COMPLETE
            ),
            com.folium.reader.index.TextSearchCoverageSnapshot(
                (0..3).associateWith { com.folium.reader.index.TextSearchPageCoverage.PENDING }
            )
        )
        val ocrPage = page("recognized").copy(source = TextSource.OCR)

        coverage.recordOcr(3, OcrPageStatus(OcrPageState.COMPLETED, 0), ocrPage)
        coverage.recordOcr(
            1,
            OcrPageStatus(OcrPageState.FAILED, 0, failure = OcrFailureMetadata("recognition", true)),
            selected = null
        )
        coverage.recordOcr(
            2,
            OcrPageStatus(OcrPageState.CANCELLED, 0, cancellationReason = OcrCancellationReason.USER),
            selected = null
        )
        val snapshot = coverage.snapshot(ocrCanProgress = true)

        assertEquals(1, snapshot.indexedPages)
        assertEquals(1, snapshot.pendingPages)
        assertEquals(1, snapshot.failedPages)
        assertEquals(1, snapshot.cancelledPages)
        assertEquals(3, snapshot.incompletePages)
        assertTrue(snapshot.running)
    }

    @Test fun denseStreamingUsesLogarithmicFullSnapshotClaims() {
        val request = TextPageLoader.SearchRequest(
            generation = 1,
            spec = TextSearchSpec("needle"),
            callback = {},
            pageCount = 1_024,
            onResultPageAggregated = {}
        )
        var publications = 0

        repeat(1_024) { pageIndex ->
            request.recordPageUpdate(pageIndex, TextSource.OCR)
            val context = request.capturePublicationContext()
            val claim = request.claimPublication(
                context,
                running = true,
                force = false,
                now = TimeUnit.SECONDS.toNanos((pageIndex + 1).toLong())
            )
            if (claim != null) publications++
        }

        assertTrue(publications <= 11)
        val terminalContext = request.capturePublicationContext()
        assertTrue(request.claimPublication(
            terminalContext,
            running = false,
            force = true,
            now = TimeUnit.HOURS.toNanos(1)
        ) != null)
    }

    @Test fun newerPendingPageUpdateSurvivesCapturedStaleRevisionAndPublishesNext() {
        val updates = PendingPageUpdates()
        val updateA = updates.record(7, TextSource.NATIVE_PDF)
        assertEquals(listOf(updateA), updates.capture())

        val updateB = updates.record(7, TextSource.OCR)
        assertFalse(updates.isLatest(updateA))
        assertTrue(updates.hasPending())

        val published = mutableListOf<PendingPageUpdate>()
        if (updates.isLatest(updateA)) published += updateA
        assertTrue(published.isEmpty())

        assertEquals(listOf(updateB), updates.capture())
        assertTrue(updates.isLatest(updateB))
        published += updateB

        assertEquals(listOf(updateB), published)
        assertFalse(updates.hasPending())
        assertTrue(updateB.revision > updateA.revision)
        assertEquals(TextSource.OCR, updateB.source)
    }

    @Test fun thousandPageLatestQueryUsesOneBulkSnapshotPerGenerationAndNoPerPageStateCalls() {
        val firstBulkEntered = CountDownLatch(1)
        val releaseFirstBulk = CountDownLatch(1)
        val index = CountingSearchIndex(firstBulkEntered, releaseFirstBulk)
        val harness = searchHarness(1_000, index) { page("needle") }
        repeat(1_000) { page -> index.complete(harness.key(page), page("needle")) }
        val oldCallbacks = AtomicInteger()
        val latest = CopyOnWriteArrayList<TextSearchProgress>()

        harness.loader.search("old") { oldCallbacks.incrementAndGet() }
        assertTrue(firstBulkEntered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        harness.loader.search("needle") { latest += it }
        releaseFirstBulk.countDown()
        waitUntil { latest.lastOrNull()?.running == false }

        assertTrue(oldCallbacks.get() <= 1)
        assertEquals(1, index.bulkStateCalls.get())
        assertEquals(0, index.singleStateCalls.get())
        assertEquals(1, index.searchCalls.get())
        assertEquals(1_000, latest.last().matches.size)
        assertTrue(latest.size <= 2)
        harness.close()
    }

    @Test fun thousandMissingNoMatchPagesNeverScanEmptyResultSlotsDuringPublication() {
        val index = CountingSearchIndex()
        val matcherUpdates = AtomicInteger()
        val resultPageVisits = AtomicInteger()
        val harness = searchHarness(
            pageCount = 1_000,
            index = index,
            matchPage = { textPage, query ->
                matcherUpdates.incrementAndGet()
                TextPageMatcher.find(textPage, query)
            },
            onResultPageAggregated = resultPageVisits::incrementAndGet
        ) { page("haystack") }
        val completed = CountDownLatch(1)
        var final: TextSearchProgress? = null

        harness.loader.search("needle") { progress ->
            final = progress
            if (!progress.running) completed.countDown()
        }

        assertTrue(completed.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertEquals(1, index.bulkStateCalls.get())
        assertTrue(index.searchCalls.get() in 0..1)
        assertEquals(0, index.singleStateCalls.get())
        assertTrue(matcherUpdates.get() in 1..1_000)
        assertEquals(1_000, harness.extracted.size)
        assertTrue(requireNotNull(final).matches.isEmpty())
        assertEquals(0, resultPageVisits.get())
        harness.close()
    }

    @Test fun denseFifteenHundredPageSearchIsCappedOrderedAndPublishedOnceAtTerminal() {
        val pageCount = 1_500
        val wordsPerPage = 667
        val densePage = TextPage(
            listOf(TextBlock(listOf(TextLine(List(wordsPerPage) { ordinal ->
                TextWord("needle", PageSpaceRect(0f, 0f, 1f, 1f), ordinal)
            }, 0)), 0)),
            TextSource.NATIVE_PDF
        )
        val resultPageVisits = AtomicInteger()
        val callbacks = AtomicInteger()
        val index = TransientTextPageIndex()
        val key: (Int) -> TextPageIndexKey = { pageIndex -> searchKey().copy(pageIndex = pageIndex) }
        prepare(index, key(0))
        repeat(pageCount) { page -> index.complete(key(page), densePage) }
        val loader = TextPageLoader(
            FakeDocument(pageCount) { error("all pages are already indexed") },
            pageCount,
            deliver = { it() },
            index = index,
            indexKey = key,
            onResultPageAggregated = resultPageVisits::incrementAndGet
        )
        val terminal = CountDownLatch(1)
        var final: TextSearchProgress? = null

        loader.search("needle") { progress ->
            callbacks.incrementAndGet()
            final = progress
            if (!progress.running) terminal.countDown()
        }

        assertTrue(terminal.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        val result = requireNotNull(final)
        assertEquals(MAX_TEXT_SEARCH_RESULTS, result.matches.size)
        assertTrue(result.truncated)
        assertFalse(result.running)
        assertEquals(result.matches.sortedWith(compareBy(TextPageSearchHit::pageIndex, TextPageSearchHit::occurrenceIndex)), result.matches)
        assertTrue(resultPageVisits.get() <= pageCount)
        assertTrue(callbacks.get() <= 2)
        loader.dispose()
        index.close()
    }

    @Test fun foregroundQueuedDuringProgressPublicationDoesNotConsumeCoverageClaim() {
        val publications = LinkedBlockingQueue<() -> Unit>()
        val matcherCalls = IntArray(3)
        val index = TransientTextPageIndex()
        val key: (Int) -> TextPageIndexKey = { pageIndex -> searchKey().copy(pageIndex = pageIndex) }
        prepare(index, key(0))
        val extracted = Collections.synchronizedList(mutableListOf<Int>())
        val loader = TextPageLoader(
            FakeDocument(3) { pageIndex -> extracted += pageIndex; page("needle-$pageIndex") },
            3,
            deliver = publications::add,
            index = index,
            indexKey = key,
            matchPage = { textPage, query ->
                matcherCalls[textPage.text.substringAfterLast('-').toInt()]++
                TextPageMatcher.find(textPage, query)
            }
        )
        val progress = CopyOnWriteArrayList<TextSearchProgress>()
        val foreground = CountDownLatch(1)

        loader.search("needle") { progress += it }
        loader.load(2) { foreground.countDown() }
        requireNotNull(publications.poll(SETTLE_SECONDS, TimeUnit.SECONDS)).invoke()
        while (foreground.count > 0L) {
            requireNotNull(publications.poll(SETTLE_SECONDS, TimeUnit.SECONDS)).invoke()
        }

        while (progress.lastOrNull()?.running != false) {
            requireNotNull(publications.poll(SETTLE_SECONDS, TimeUnit.SECONDS)).invoke()
        }

        assertEquals(setOf(0, 1, 2), extracted.toSet())
        assertTrue(matcherCalls.sum() in 0..3)
        assertEquals(3, progress.last().indexedPages)
        assertEquals(0, progress.last().failedPages)
        assertTrue(progress.count { !it.running } <= 2)
        assertFalse(progress.last().running)
        loader.dispose()
        index.close()
    }

    @Test fun searchLifecycleNeverCancelsQueuedForegroundDelivery() {
        val publications = CopyOnWriteArrayList<() -> Unit>()
        val index = TransientTextPageIndex()
        val key = searchKey()
        prepare(index, key)
        val loader = TextPageLoader(
            FakeDocument(1) { page("foreground") },
            1,
            deliver = { publications += it },
            index = index,
            indexKey = { key }
        )
        val delivered = CountDownLatch(1)
        var hostText: ReaderTextState = ReaderTextState.Loading(0)
        loader.load(0) { result -> hostText = result.toReaderTextState(0); delivered.countDown() }
        waitUntil { publications.size == 1 }

        loader.search("first") {}
        loader.closeSearch()
        loader.search("replacement") {}
        publications[0]()

        assertTrue(delivered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertTrue(hostText is ReaderTextState.Loaded)
        loader.closeSearch()
        loader.dispose()
        index.close()
    }

    @Test fun staleOldSearchFailureCannotFailOrStopReplacementQuery() {
        val searchEntered = CountDownLatch(1)
        val releaseSearch = CountDownLatch(1)
        val index = CountingSearchIndex(
            searchEntered = searchEntered,
            releaseSearch = releaseSearch
        ).apply { failSearchCall = 1 }
        val harness = searchHarness(1, index) { page("new") }
        index.complete(harness.key(0), page("new"))
        val oldCallbacks = AtomicInteger()
        val latest = CopyOnWriteArrayList<TextSearchProgress>()

        harness.loader.search("old") { oldCallbacks.incrementAndGet() }
        assertTrue(searchEntered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        harness.loader.search("new") { latest += it }
        releaseSearch.countDown()
        waitUntil { latest.lastOrNull()?.running == false }

        assertTrue(oldCallbacks.get() <= 1)
        assertEquals(2, index.searchCalls.get())
        assertFalse(latest.last().error)
        assertEquals(listOf(0), latest.last().matches.map { it.pageIndex })
        harness.close()
    }

    @Test fun bulkStateFailureStopsSearchButWorkerStillServesForegroundText() {
        val index = CountingSearchIndex().apply { failBulk = true }
        val harness = searchHarness(2, index) { page("foreground") }
        val failed = CountDownLatch(1)
        var progress: TextSearchProgress? = null
        harness.loader.search("query") {
            progress = it
            failed.countDown()
        }
        assertTrue(failed.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertTrue(requireNotNull(progress).error)
        assertFalse(requireNotNull(progress).running)

        index.failBulk = false
        val loaded = CountDownLatch(1)
        var foreground: TextPageLoadResult? = null
        harness.loader.load(1) { foreground = it; loaded.countDown() }
        assertTrue(loaded.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertTrue(foreground is TextPageLoadResult.Loaded)
        harness.close()
    }

    @Test fun incrementalIndexFailureDoesNotStopForegroundText() {
        val index = CountingSearchIndex().apply { failPublishCall = 1 }
        val extractionStarted = CountDownLatch(1)
        val releaseExtraction = CountDownLatch(1)
        val harness = searchHarness(3, index) {
            extractionStarted.countDown()
            releaseExtraction.awaitIgnoringInterrupts()
            page("needle")
        }
        assertTrue(extractionStarted.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        val searchFailed = CountDownLatch(1)
        harness.loader.search("needle") { if (it.error) searchFailed.countDown() }
        releaseExtraction.countDown()
        assertTrue(searchFailed.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        val loaded = CountDownLatch(1)
        harness.loader.load(2) { if (it is TextPageLoadResult.Loaded) loaded.countDown() }
        assertTrue(loaded.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        harness.close()
    }

    @Test fun maintenanceRefreshesUnknownCoverageAndHitsInBoundedPublications() {
        val pageCount = 1_024
        val maintenanceEntered = CountDownLatch(1)
        val releaseMaintenance = CountDownLatch(1)
        val index = MaintenanceRefreshIndex(
            pageCount,
            maintenanceEntered,
            releaseMaintenance,
            maintenanceBatchSize = 32
        )
        val snapshots = AtomicInteger()
        val harness = searchHarness(
            pageCount,
            index,
            onFullResultSnapshot = snapshots::incrementAndGet
        ) { error("legacy COMPLETE pages must not be extracted") }
        val publications = CopyOnWriteArrayList<TextSearchProgress>()
        val initial = CountDownLatch(1)
        val terminal = CountDownLatch(1)

        harness.loader.search("needle") { progress ->
            publications += progress
            if (progress.running && progress.indexedPages == 0) initial.countDown()
            if (!progress.running) terminal.countDown()
        }
        assertTrue(maintenanceEntered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertTrue(initial.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertEquals(1, publications.size)
        assertTrue(publications.single().running)
        assertEquals(0, publications.single().indexedPages)
        assertEquals(pageCount, publications.single().incompletePages)
        assertTrue(publications.single().matches.isEmpty())
        releaseMaintenance.countDown()
        assertTrue(terminal.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        val result = publications.last()
        assertEquals(pageCount, result.indexedPages)
        assertEquals(0, result.incompletePages)
        assertEquals((0 until pageCount).toList(), result.matches.map { it.pageIndex })
        assertTrue(publications.size in 2..7)
        assertEquals(publications.size, snapshots.get())
        assertEquals(33, index.coverageCalls.get())
        assertTrue(index.searchCalls.get() in 2..8)
        harness.close()
    }

    @Test fun maintenanceFinishingBeforeInitialClaimPublishesCurrentAtomicRevision() {
        val initialSearchEntered = CountDownLatch(1)
        val releaseInitialSearch = CountDownLatch(1)
        val maintenanceEntered = CountDownLatch(1)
        val releaseMaintenance = CountDownLatch(0)
        val index = MaintenanceRefreshIndex(
            pageCount = 1,
            maintenanceEntered = maintenanceEntered,
            releaseMaintenance = releaseMaintenance,
            searchEntered = initialSearchEntered,
            releaseSearch = releaseInitialSearch
        )
        val harness = searchHarness(1, index) { error("legacy COMPLETE page must not be extracted") }
        val initial = CountDownLatch(1)
        val publications = CopyOnWriteArrayList<TextSearchProgress>()

        harness.loader.search("needle") { progress ->
            publications += progress
            initial.countDown()
        }
        assertTrue(initialSearchEntered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertTrue(maintenanceEntered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        waitUntil { index.processedPageCount() == 1 }
        releaseInitialSearch.countDown()
        assertTrue(initial.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        val result = publications.single()
        assertFalse(result.running)
        assertEquals(1, result.indexedPages)
        assertEquals(1, result.coverageRevision)
        assertEquals(listOf(0), result.matches.map(TextPageSearchHit::pageIndex))
        harness.close()
    }

    @Test fun lateMaintenanceRefreshPublishesOnlyThroughCurrentQueryGeneration() {
        val maintenanceEntered = CountDownLatch(1)
        val releaseMaintenance = CountDownLatch(1)
        val index = MaintenanceRefreshIndex(1, maintenanceEntered, releaseMaintenance)
        val harness = searchHarness(1, index) { error("legacy COMPLETE page must not be extracted") }
        val oldPublications = AtomicInteger()
        val oldInitial = CountDownLatch(1)
        val currentInitial = CountDownLatch(1)
        val currentTerminal = CountDownLatch(1)
        val current = CopyOnWriteArrayList<TextSearchProgress>()

        harness.loader.search("old") {
            oldPublications.incrementAndGet()
            oldInitial.countDown()
        }
        assertTrue(maintenanceEntered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        assertTrue(oldInitial.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        harness.loader.search("needle") { progress ->
            current += progress
            if (progress.running) currentInitial.countDown() else currentTerminal.countDown()
        }
        assertTrue(currentInitial.await(SETTLE_SECONDS, TimeUnit.SECONDS))
        releaseMaintenance.countDown()
        assertTrue(currentTerminal.await(SETTLE_SECONDS, TimeUnit.SECONDS))

        assertEquals(1, oldPublications.get())
        assertEquals("needle", current.last().query)
        assertEquals(1, current.last().indexedPages)
        assertEquals(1, current.last().matches.size)
        assertFalse(current.last().running)
        harness.close()
    }

    private fun loadAndWait(loader: TextPageLoader, pageIndex: Int) {
        val delivered = CountDownLatch(1)
        loader.load(pageIndex) { delivered.countDown() }
        assertTrue(delivered.await(SETTLE_SECONDS, TimeUnit.SECONDS))
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETTLE_SECONDS)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }

    /** Like [waitUntil], but for a [condition] that is expected to stay false for [timeoutMillis]. */
    private fun waitFor(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        return condition()
    }

    private class MutableClock(startMillis: Long = 0L) : () -> Long {
        private val millis = AtomicLong(startMillis)
        override fun invoke(): Long = millis.get()
        fun advanceBy(deltaMillis: Long) { millis.addAndGet(deltaMillis) }
    }

    private fun searchHarness(
        pageCount: Int,
        index: TextPageIndex = TransientTextPageIndex(),
        matchPage: (TextPage, String) -> List<TextPageMatch> = TextPageMatcher::find,
        onResultPageAggregated: () -> Unit = {},
        onFullResultSnapshot: () -> Unit = {},
        priorityGate: DocumentPriorityGate = DocumentPriorityGate(),
        awaitFirstForegroundBeforeBackground: Boolean = false,
        extraction: (Int) -> TextPage
    ): SearchHarness {
        val key: (Int) -> TextPageIndexKey = { pageIndex ->
            TextPageIndexKey(
                BookId("search-book"), DocumentContentVersion("ab".repeat(32)), pageIndex,
                TextSource.NATIVE_PDF, 2, TextEngineVersion("native-v1")
            )
        }
        index.prepareDocument(key(0).bookId, key(0).documentVersion)
        assertEquals(
            TextPageIndexWriteOutcome.APPLIED,
            index.prepareSource(
                key(0).bookId, key(0).documentVersion, key(0).source,
                key(0).textSchemaVersion, key(0).engineVersion
            )
        )
        val extracted = Collections.synchronizedList(mutableListOf<Int>())
        val loader = TextPageLoader(
            FakeDocument(pageCount) { page -> extracted += page; extraction(page) },
            pageCount,
            deliver = { it() },
            index = index,
            indexKey = key,
            matchPage = matchPage,
            onResultPageAggregated = onResultPageAggregated,
            onFullResultSnapshot = onFullResultSnapshot,
            priorityGate = priorityGate,
            awaitFirstForegroundBeforeBackground = awaitFirstForegroundBeforeBackground
        )
        return SearchHarness(index, loader, key, extracted)
    }

    private data class SearchHarness(
        val index: TextPageIndex,
        val loader: TextPageLoader,
        val key: (Int) -> TextPageIndexKey,
        val extracted: MutableList<Int>
    ) {
        fun close() { loader.dispose(); index.close() }
    }

    private class CountingSearchIndex(
        private val firstBulkEntered: CountDownLatch? = null,
        private val releaseFirstBulk: CountDownLatch? = null,
        private val searchEntered: CountDownLatch? = null,
        private val releaseSearch: CountDownLatch? = null,
        private val delegate: TransientTextPageIndex = TransientTextPageIndex()
    ) : TextPageIndex by delegate {
        val singleStateCalls = AtomicInteger()
        val bulkStateCalls = AtomicInteger()
        val searchCalls = AtomicInteger()
        val publishCalls = AtomicInteger()
        @Volatile var failBulk = false
        @Volatile var failSearchCall: Int? = null
        @Volatile var failPublishCall: Int? = null

        override fun state(key: TextPageIndexKey): TextPageIndexState? {
            singleStateCalls.incrementAndGet()
            return delegate.state(key)
        }

        override fun pageStatesIfCurrent(key: TextPageIndexKey): Map<Int, TextPageIndexState>? {
            val call = bulkStateCalls.incrementAndGet()
            if (call == 1) {
                firstBulkEntered?.countDown()
                releaseFirstBulk?.awaitIgnoringInterrupts()
            }
            if (failBulk) throw IllegalStateException("bulk state failure")
            return delegate.pageStatesIfCurrent(key)
        }

        override fun searchIfCurrent(
            bookId: BookId,
            documentVersion: DocumentContentVersion,
            query: String,
            includeOcr: Boolean,
            limit: Int,
            layoutVersion: String?,
            publication: (com.folium.reader.index.TextPageSearchResult) -> Unit
        ): TextPagePublicationOutcome {
            val call = searchCalls.incrementAndGet()
            if (call == 1) {
                searchEntered?.countDown()
                releaseSearch?.awaitIgnoringInterrupts()
            }
            if (failSearchCall == call) throw IllegalStateException("search failure")
            return delegate.searchIfCurrent(bookId, documentVersion, query, includeOcr, limit, layoutVersion, publication)
        }

        override fun searchIfCurrent(
            bookId: BookId,
            documentVersion: DocumentContentVersion,
            spec: TextSearchSpec,
            includeOcr: Boolean,
            limit: Int,
            layoutVersion: String?,
            publication: (com.folium.reader.index.TextPageSearchResult) -> Unit
        ): TextPagePublicationOutcome = searchIfCurrent(
            bookId, documentVersion, spec.query, includeOcr, limit, layoutVersion, publication
        )

        override fun publishIfCurrent(
            key: TextPageIndexKey,
            publication: () -> Unit
        ): TextPagePublicationOutcome {
            val call = publishCalls.incrementAndGet()
            if (failPublishCall == call) throw IllegalStateException("publication failure")
            return delegate.publishIfCurrent(key, publication)
        }
    }

    private class MaintenanceRefreshIndex(
        private val pageCount: Int,
        private val maintenanceEntered: CountDownLatch,
        private val releaseMaintenance: CountDownLatch,
        private val maintenanceBatchSize: Int = pageCount,
        private val searchEntered: CountDownLatch? = null,
        private val releaseSearch: CountDownLatch? = null,
        private val delegate: TransientTextPageIndex = TransientTextPageIndex()
    ) : TextPageIndex by delegate {
        val coverageCalls = AtomicInteger()
        val searchCalls = AtomicInteger()
        private val maintenanceStarted = AtomicBoolean()
        private val processedPages = AtomicInteger()

        override fun pageStatesIfCurrent(key: TextPageIndexKey): Map<Int, TextPageIndexState> =
            (0 until pageCount).associateWith { TextPageIndexState.COMPLETE }

        override fun searchCoverageIfCurrent(
            nativeKey: TextPageIndexKey,
            ocrKey: com.folium.reader.index.OcrPageKey?
        ): com.folium.reader.index.TextSearchCoverageSnapshot {
            coverageCalls.incrementAndGet()
            return coverageSnapshot()
        }

        fun processedPageCount(): Int = processedPages.get()

        private fun coverageSnapshot(): com.folium.reader.index.TextSearchCoverageSnapshot {
            val processed = processedPages.get()
            return com.folium.reader.index.TextSearchCoverageSnapshot(
                (0 until pageCount).associateWith { pageIndex ->
                    if (pageIndex < processed) {
                        com.folium.reader.index.TextSearchPageCoverage.PROCESSED
                    } else {
                        com.folium.reader.index.TextSearchPageCoverage.PENDING
                    }
                },
                revision = processed.toLong()
            )
        }

        override fun maintainDerivedDataBatch(
            nativeKey: TextPageIndexKey,
            ocrKey: com.folium.reader.index.OcrPageKey?
        ): com.folium.reader.index.DerivedMaintenanceResult {
            if (processedPages.get() >= pageCount) {
                return com.folium.reader.index.DerivedMaintenanceResult(false)
            }
            if (maintenanceStarted.compareAndSet(false, true)) {
                maintenanceEntered.countDown()
                releaseMaintenance.awaitIgnoringInterrupts()
            }
            val processed = processedPages.addAndGet(maintenanceBatchSize).coerceAtMost(pageCount)
            processedPages.set(processed)
            return com.folium.reader.index.DerivedMaintenanceResult(
                morePending = processed < pageCount,
                coverage = searchCoverageIfCurrent(nativeKey, ocrKey)
            )
        }

        override fun searchIfCurrent(
            bookId: BookId,
            documentVersion: DocumentContentVersion,
            spec: TextSearchSpec,
            includeOcr: Boolean,
            limit: Int,
            layoutVersion: String?,
            publication: (com.folium.reader.index.TextPageSearchResult) -> Unit
        ): TextPagePublicationOutcome {
            val call = searchCalls.incrementAndGet()
            if (call == 1) {
                searchEntered?.countDown()
                releaseSearch?.awaitIgnoringInterrupts()
            }
            val processed = processedPages.get()
            val hits = if (spec.query != "needle") emptyList() else {
                (0 until processed).map { pageIndex ->
                    TextPageSearchHit(
                        pageIndex,
                        TextSource.NATIVE_PDF,
                        0,
                        0..0,
                        listOf(PageSpaceRect(0f, 0f, 1f, 1f)),
                        "needle"
                    )
                }
            }
            publication(com.folium.reader.index.TextPageSearchResult(
                hits = hits,
                maintenancePending = processed < pageCount,
                coverage = coverageSnapshot()
            ))
            return TextPagePublicationOutcome.CURRENT
        }
    }

    private fun searchKey() = TextPageIndexKey(
        BookId("search-book"), DocumentContentVersion("ab".repeat(32)), 0,
        TextSource.NATIVE_PDF, 2, TextEngineVersion("native-v1")
    )

    private fun prepare(index: TextPageIndex, key: TextPageIndexKey) {
        index.prepareDocument(key.bookId, key.documentVersion)
        index.prepareSource(
            key.bookId,
            key.documentVersion,
            key.source,
            key.textSchemaVersion,
            key.engineVersion
        )
    }
}

private fun CountDownLatch.awaitIgnoringInterrupts() {
    while (count > 0L) {
        try {
            await()
        } catch (_: InterruptedException) {
            // The native extraction analogue does not stop until its owner releases it.
        }
    }
}
