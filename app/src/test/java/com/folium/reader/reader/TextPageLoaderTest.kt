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
import java.util.concurrent.atomic.AtomicReference

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
        assertTrue(runningCaptured.await(2, TimeUnit.SECONDS))

        val terminalClaim = requireNotNull(gate.claim(running = false))
        if (gate.canDeliver(terminalClaim)) delivered += false
        releaseRunning.countDown()
        assertTrue(staleFinished.await(2, TimeUnit.SECONDS))
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
        assertTrue(aCaptured.await(2, TimeUnit.SECONDS))

        val updateB = request.recordPageUpdate(0, TextSource.OCR)
        val contextB = request.capturePublicationContext()
        bRecorded.countDown()
        assertTrue(aFinished.await(2, TimeUnit.SECONDS))
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
        assertTrue(aClaimed.await(2, TimeUnit.SECONDS))

        val updateB = request.recordPageUpdate(0, TextSource.OCR)
        val contextB = request.capturePublicationContext()
        bRecorded.countDown()
        assertTrue(aFinished.await(2, TimeUnit.SECONDS))
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
        assertTrue(entered.await(2, TimeUnit.SECONDS))
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
        assertTrue(entered.await(2, TimeUnit.SECONDS))
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
        assertTrue(disposed.await(2, TimeUnit.SECONDS))
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
        assertFalse(delivered.await(100, TimeUnit.MILLISECONDS))
        publications.single().invoke()

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
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

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
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
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        loader.close()

        val disposed = CountDownLatch(1)
        Thread { loader.dispose(); disposed.countDown() }.start()
        assertFalse(disposed.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(disposed.await(2, TimeUnit.SECONDS))
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

    @Test fun autonomousIndexingRunsToCompletionWithoutSearch() {
        val harness = searchHarness(3) { index -> page("page-$index") }

        waitUntil { harness.extracted.size == 3 }

        assertEquals(listOf(0, 1, 2), harness.extracted)
        assertEquals(3, harness.index.pageStatesIfCurrent(harness.key(0))?.size)
        harness.close()
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

        assertTrue(completed.await(2, TimeUnit.SECONDS))
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
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val queried = CountDownLatch(1)

        loader.search(TextSearchSpec("needle")) { if (it.matches.isNotEmpty()) queried.countDown() }

        assertTrue("query lane blocked behind native extraction", queried.await(2, TimeUnit.SECONDS))
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
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        harness.loader.load(2) { foreground.countDown() }
        release.countDown()

        assertTrue(foreground.await(2, TimeUnit.SECONDS))
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
        assertTrue(firstExtraction.await(2, TimeUnit.SECONDS))
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
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertTrue(deliveryAcknowledged.await(2, TimeUnit.SECONDS))
        val beforeClose = publications.get()
        loader.closeSearch()
        release.countDown()
        loader.dispose()
        val deliveryDrained = CountDownLatch(1)
        delivery.execute(deliveryDrained::countDown)
        assertTrue(deliveryDrained.await(2, TimeUnit.SECONDS))
        assertEquals(beforeClose, publications.get())
        delivery.shutdown()
        assertTrue(delivery.awaitTermination(2, TimeUnit.SECONDS))
        index.close()
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
                while (workersDone.count > 0L) {
                    val snapshot = coverage.snapshot()
                    val completed = snapshot.indexedPages + snapshot.failedPages
                    check(snapshot.completePages.size == snapshot.indexedPages)
                    check(completed in previousCompleted..pageCount)
                    previousCompleted = completed
                }
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
        assertTrue(workersDone.await(5, TimeUnit.SECONDS))
        assertTrue(snapshotsDone.await(5, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError(it) }
        executor.shutdown()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
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
        assertTrue(firstBulkEntered.await(2, TimeUnit.SECONDS))
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

        assertTrue(completed.await(10, TimeUnit.SECONDS))
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

        assertTrue(terminal.await(10, TimeUnit.SECONDS))
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
        requireNotNull(publications.poll(2, TimeUnit.SECONDS)).invoke()
        while (foreground.count > 0L) {
            requireNotNull(publications.poll(2, TimeUnit.SECONDS)).invoke()
        }

        while (progress.lastOrNull()?.running != false) {
            requireNotNull(publications.poll(2, TimeUnit.SECONDS)).invoke()
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

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
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
        assertTrue(searchEntered.await(2, TimeUnit.SECONDS))
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
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertTrue(requireNotNull(progress).error)
        assertFalse(requireNotNull(progress).running)

        index.failBulk = false
        val loaded = CountDownLatch(1)
        var foreground: TextPageLoadResult? = null
        harness.loader.load(1) { foreground = it; loaded.countDown() }
        assertTrue(loaded.await(2, TimeUnit.SECONDS))
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
        assertTrue(extractionStarted.await(2, TimeUnit.SECONDS))
        val searchFailed = CountDownLatch(1)
        harness.loader.search("needle") { if (it.error) searchFailed.countDown() }
        releaseExtraction.countDown()
        assertTrue(searchFailed.await(2, TimeUnit.SECONDS))

        val loaded = CountDownLatch(1)
        harness.loader.load(2) { if (it is TextPageLoadResult.Loaded) loaded.countDown() }
        assertTrue(loaded.await(2, TimeUnit.SECONDS))
        harness.close()
    }

    private fun loadAndWait(loader: TextPageLoader, pageIndex: Int) {
        val delivered = CountDownLatch(1)
        loader.load(pageIndex) { delivered.countDown() }
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }

    private fun searchHarness(
        pageCount: Int,
        index: TextPageIndex = TransientTextPageIndex(),
        matchPage: (TextPage, String) -> List<TextPageMatch> = TextPageMatcher::find,
        onResultPageAggregated: () -> Unit = {},
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
            onResultPageAggregated = onResultPageAggregated
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
            publication: (com.folium.reader.index.TextPageSearchResult) -> Unit
        ): TextPagePublicationOutcome {
            val call = searchCalls.incrementAndGet()
            if (call == 1) {
                searchEntered?.countDown()
                releaseSearch?.awaitIgnoringInterrupts()
            }
            if (failSearchCall == call) throw IllegalStateException("search failure")
            return delegate.searchIfCurrent(bookId, documentVersion, query, includeOcr, limit, publication)
        }

        override fun searchIfCurrent(
            bookId: BookId,
            documentVersion: DocumentContentVersion,
            spec: TextSearchSpec,
            includeOcr: Boolean,
            limit: Int,
            publication: (com.folium.reader.index.TextPageSearchResult) -> Unit
        ): TextPagePublicationOutcome = searchIfCurrent(
            bookId, documentVersion, spec.query, includeOcr, limit, publication
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
