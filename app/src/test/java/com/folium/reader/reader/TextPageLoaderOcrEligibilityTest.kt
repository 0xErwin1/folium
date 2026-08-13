package com.folium.reader.reader

import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.Raster
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.text.NATIVE_TEXT_USABILITY_POLICY_VERSION
import com.folium.reader.core.text.TextBlock
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextLine
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextWord
import com.folium.reader.core.text.MAX_TEXT_SEARCH_RESULTS
import com.folium.reader.index.DocumentContentVersion
import com.folium.reader.index.OcrPageKey
import com.folium.reader.index.OcrPageState
import com.folium.reader.index.OcrTransitionOutcome
import com.folium.reader.index.TextPageIndexKey
import com.folium.reader.index.TransientTextPageIndex
import com.folium.reader.index.TextPageIndex
import com.folium.reader.index.TextPageIndexState
import com.folium.reader.index.TextPagePublicationOutcome
import com.folium.reader.index.OcrTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.Collections

class TextPageLoaderOcrEligibilityTest {
    private val nativeKey = TextPageIndexKey(BookId("book"), DocumentContentVersion("ab".repeat(32)),
        0, TextSource.NATIVE_PDF, 2, TextEngineVersion("native-v1"))
    private val ocrKey = OcrPageKey(nativeKey.bookId, nativeKey.documentVersion, 0,
        nativeKey.textSchemaVersion, nativeKey.engineVersion, NATIVE_TEXT_USABILITY_POLICY_VERSION,
        TextEngineVersion("ocr-v1|eng:data"))

    @Test fun foregroundCompletionQueuesOnlyUnusableNativeAndUsableNativeCancels() {
        val index = preparedIndex()
        val loader = loader(index) { wordPage("!?", TextSource.NATIVE_PDF) }
        assertTrue(load(loader) is TextPageLoadResult.Loaded)
        assertEquals(OcrPageState.QUEUED, index.ocrStatus(ocrKey)?.state)
        loader.dispose()
        index.close()

        val usableIndex = preparedIndex()
        val replacement = loader(usableIndex) { wordPage("123", TextSource.NATIVE_PDF) }
        assertTrue(load(replacement) is TextPageLoadResult.Loaded)
        assertEquals(OcrPageState.CANCELLED, usableIndex.ocrStatus(ocrKey)?.state)
        replacement.dispose()
        usableIndex.close()
    }

    @Test fun onlyQueuedUnusableNativePagesNotifyTheProductionPipeline() {
        val eligible = CountDownLatch(1)
        val index = preparedIndex()
        val loader = loader(
            index,
            onOcrEligible = {
                assertEquals(0, it)
                eligible.countDown()
            }
        ) { TextPage(emptyList(), TextSource.NATIVE_PDF) }

        assertTrue(load(loader) is TextPageLoadResult.Loaded)
        assertTrue(eligible.await(2, TimeUnit.SECONDS))
        loader.dispose()
        index.close()

        val usableNotification = CountDownLatch(1)
        val usableIndex = preparedIndex()
        val usableLoader = loader(
            usableIndex,
            onOcrEligible = { usableNotification.countDown() }
        ) { wordPage("native", TextSource.NATIVE_PDF) }

        assertTrue(load(usableLoader) is TextPageLoadResult.Loaded)
        assertFalse(usableNotification.await(100, TimeUnit.MILLISECONDS))
        usableLoader.dispose()
        usableIndex.close()
    }

    @Test fun extractionFailureDoesNotQueueOrHotLoopDuringCoverage() {
        val calls = AtomicInteger()
        val index = preparedIndex()
        val loader = loader(index) { calls.incrementAndGet(); error("native failed") }
        val finished = CountDownLatch(1)
        loader.search("query") { if (!it.running) finished.countDown() }

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
        assertNull(index.ocrStatus(ocrKey))
        loader.dispose()
        index.close()
    }

    @Test fun progressiveCoverageCompletionReconcilesEligibilityOnce() {
        val calls = AtomicInteger()
        val index = preparedIndex()
        val loader = loader(index) { calls.incrementAndGet(); TextPage(emptyList(), TextSource.NATIVE_PDF) }
        val finished = CountDownLatch(1)
        loader.search("missing") { if (!it.running) finished.countDown() }

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
        assertEquals(OcrPageState.QUEUED, index.ocrStatus(ocrKey)?.state)
        loader.dispose()
        index.close()
    }

    @Test fun completedCurrentOcrIsSelectedWithoutRepeatingNativeExtraction() {
        val index = preparedIndex()
        index.completeNativeAndReconcile(nativeKey, TextPage(emptyList(), TextSource.NATIVE_PDF), ocrKey)
        val attempt = requireNotNull(index.claimOcr(ocrKey).attempt)
        assertEquals(OcrTransitionOutcome.APPLIED,
            index.completeOcr(attempt, wordPage("recognized", TextSource.OCR)).outcome)
        val calls = AtomicInteger()
        val loader = loader(index) { calls.incrementAndGet(); error("must not extract") }

        assertEquals(TextSource.OCR, (load(loader) as TextPageLoadResult.Loaded).page.source)
        assertEquals(0, calls.get())
        loader.dispose()
        index.close()
    }

    @Test fun completedEmptyOcrRemainsProcessedAcrossLoadAndSearchReopen() {
        val index = preparedIndex()
        val emptyNative = TextPage(emptyList(), TextSource.NATIVE_PDF)
        index.completeNativeAndReconcile(nativeKey, emptyNative, ocrKey)
        val attempt = requireNotNull(index.claimOcr(ocrKey).attempt)
        index.completeOcr(attempt, TextPage(emptyList(), TextSource.OCR))
        val extractions = AtomicInteger()
        val loader = loader(index) {
            extractions.incrementAndGet()
            error("completed OCR must be reused")
        }

        assertEquals(TextSource.OCR, (load(loader) as TextPageLoadResult.Loaded).page.source)
        repeat(2) {
            val terminal = CountDownLatch(1)
            val publications = CopyOnWriteArrayList<TextSearchProgress>()
            loader.search("missing") { progress ->
                publications += progress
                if (!progress.running) terminal.countDown()
            }
            assertTrue(terminal.await(2, TimeUnit.SECONDS))
            assertEquals(1, publications.last().indexedPages)
            assertEquals(0, publications.last().incompletePages)
            assertFalse(publications.last().running)
            assertTrue(publications.dropWhile(TextSearchProgress::running).none(TextSearchProgress::running))
            loader.closeSearch()
        }

        assertEquals(0, extractions.get())
        assertTrue(index.planOcr(ocrKey, 0, -1, 1, 1).pageIndexes.isEmpty())
        loader.dispose()
        index.close()
    }

    @Test fun realQueryLoopStreamsOneThousandTwentyFourOcrPagesWithBoundedSnapshots() {
        val pageCount = 1_024
        val index = preparedIndex()
        val emptyNative = TextPage(emptyList(), TextSource.NATIVE_PDF)
        repeat(pageCount) { pageIndex ->
            index.completeNativeAndReconcile(
                nativeKey.copy(pageIndex = pageIndex),
                emptyNative,
                ocrKey.copy(pageIndex = pageIndex)
            )
        }
        val fullSnapshots = AtomicInteger()
        val callbacks = AtomicInteger()
        val initial = CountDownLatch(1)
        val firstSparseUpdate = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val finalProgress = AtomicReference<TextSearchProgress>()
        val loader = TextPageLoader(
            IndexedTestDocument(List(pageCount) { emptyNative }),
            pageCount,
            deliver = { it() },
            index = index,
            indexKey = { nativeKey.copy(pageIndex = it) },
            ocrKey = { ocrKey.copy(pageIndex = it) },
            onFullResultSnapshot = fullSnapshots::incrementAndGet
        )
        loader.setProgressiveOcrActive(true)
        loader.search("needle") { progress ->
            val count = callbacks.incrementAndGet()
            finalProgress.set(progress)
            if (count == 1 && progress.running) initial.countDown()
            if (count == 2 && progress.running) firstSparseUpdate.countDown()
            if (!progress.running) terminal.countDown()
        }
        assertTrue(initial.await(2, TimeUnit.SECONDS))
        assertEquals(1, callbacks.get())

        val first = claim(loader, 0)
        assertTrue(firstSparseUpdate.await(2, TimeUnit.SECONDS))
        complete(loader, first, wordPage("needle", TextSource.OCR))
        for (pageIndex in 1 until pageCount) {
            complete(loader, claim(loader, pageIndex), wordPage("needle", TextSource.OCR))
        }

        assertTrue(terminal.await(10, TimeUnit.SECONDS))
        val result = requireNotNull(finalProgress.get())
        assertFalse(result.running)
        assertEquals(pageCount, result.indexedPages)
        assertEquals(0, result.incompletePages)
        assertEquals(pageCount, result.matches.size)
        assertEquals((0 until pageCount).toList(), result.matches.map { it.pageIndex })
        assertTrue(result.matches.all { it.occurrenceIndex == 0 && it.source == TextSource.OCR })
        assertFalse(result.truncated)
        assertTrue(callbacks.get() <= 14)
        assertEquals(callbacks.get(), fullSnapshots.get())
        loader.dispose()
        index.close()
    }

    @Test fun cachedOcrTakeoverEvictsAndResolvesUsableNativeInsteadOfFailing() {
        val index = preparedIndex()
        index.completeNativeAndReconcile(nativeKey, TextPage(emptyList(), TextSource.NATIVE_PDF), ocrKey)
        val attempt = requireNotNull(index.claimOcr(ocrKey).attempt)
        index.completeOcr(attempt, wordPage("recognized", TextSource.OCR))
        val calls = AtomicInteger()
        val loader = loader(index) { calls.incrementAndGet(); error("must not extract") }
        assertEquals(TextSource.OCR, (load(loader) as TextPageLoadResult.Loaded).page.source)

        index.completeNativeAndReconcile(nativeKey, wordPage("native", TextSource.NATIVE_PDF), ocrKey)
        val takeover = load(loader)

        assertTrue(takeover is TextPageLoadResult.Loaded)
        assertEquals("native", (takeover as TextPageLoadResult.Loaded).page.text)
        assertEquals(0, calls.get())
        loader.dispose()
        index.close()
    }

    @Test fun cachedUnusableNativeTakeoverEvictsAndResolvesCompletedOcr() {
        val index = preparedIndex()
        val loader = loader(index) { TextPage(emptyList(), TextSource.NATIVE_PDF) }
        assertEquals(TextSource.NATIVE_PDF,
            (load(loader) as TextPageLoadResult.Loaded).page.source)
        assertEquals(1, loader.cachedPageCount())
        val attempt = requireNotNull(index.claimOcr(ocrKey).attempt)
        index.completeOcr(attempt, wordPage("recognized", TextSource.OCR))

        val takeover = load(loader)

        assertTrue(takeover is TextPageLoadResult.Loaded)
        assertEquals(TextSource.OCR, (takeover as TextPageLoadResult.Loaded).page.source)
        assertEquals("recognized", takeover.page.text)
        assertEquals(1, loader.cachedPageCount())
        loader.dispose()
        index.close()
    }

    @Test fun activeSearchRecomputesForOcrNonmatchThenUsableNativeTakeover() {
        val index = preparedIndex()
        index.completeNativeAndReconcile(nativeKey, wordPage("§", TextSource.NATIVE_PDF), ocrKey)
        val loader = loader(index) { error("persisted page must be used") }
        val fallbackSeen = CountDownLatch(1)
        val ocrNonmatchSeen = CountDownLatch(1)
        val nativeTakeoverSeen = CountDownLatch(1)
        val stage = AtomicInteger(0)
        loader.search("§") { progress ->
            when (stage.get()) {
                0 -> if (progress.matches.singleOrNull()?.source == TextSource.NATIVE_PDF) fallbackSeen.countDown()
                1 -> if (progress.matches.isEmpty()) ocrNonmatchSeen.countDown()
                2 -> if (progress.matches.singleOrNull()?.source == TextSource.NATIVE_PDF) nativeTakeoverSeen.countDown()
            }
        }
        assertTrue(fallbackSeen.await(2, TimeUnit.SECONDS))

        val claimed = CountDownLatch(1)
        val attempt = AtomicReference<com.folium.reader.index.OcrAttempt>()
        loader.claimOcr(0) { result ->
            attempt.set(requireNotNull((result as OcrCommandResult.Success<OcrTransition>).value.attempt))
            claimed.countDown()
        }
        assertTrue(claimed.await(2, TimeUnit.SECONDS))
        stage.set(1)
        val completed = CountDownLatch(1)
        loader.completeOcr(requireNotNull(attempt.get()), wordPage("recognized", TextSource.OCR)) {
            completed.countDown()
        }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(ocrNonmatchSeen.await(5, TimeUnit.SECONDS))

        stage.set(2)
        index.completeNativeAndReconcile(nativeKey, wordPage("§native", TextSource.NATIVE_PDF), ocrKey)
        assertTrue(load(loader) is TextPageLoadResult.Loaded)
        assertTrue(nativeTakeoverSeen.await(2, TimeUnit.SECONDS))
        loader.dispose()
        index.close()
    }

    @Test fun activeSearchReplacesNativeFallbackWithMatchingCompletedOcr() {
        val index = preparedIndex()
        index.completeNativeAndReconcile(nativeKey, wordPage("§", TextSource.NATIVE_PDF), ocrKey)
        val loader = loader(index) { error("persisted page must be used") }
        val fallbackSeen = CountDownLatch(1)
        val ocrSeen = CountDownLatch(1)
        val completing = AtomicBoolean(false)
        loader.search("§") { progress ->
            val source = progress.matches.singleOrNull()?.source
            if (!completing.get() && source == TextSource.NATIVE_PDF) fallbackSeen.countDown()
            if (completing.get() && source == TextSource.OCR) ocrSeen.countDown()
        }
        assertTrue(fallbackSeen.await(2, TimeUnit.SECONDS))

        val claimed = CountDownLatch(1)
        val attempt = AtomicReference<com.folium.reader.index.OcrAttempt>()
        loader.claimOcr(0) { result ->
            attempt.set(requireNotNull((result as OcrCommandResult.Success<OcrTransition>).value.attempt))
            claimed.countDown()
        }
        assertTrue(claimed.await(2, TimeUnit.SECONDS))
        completing.set(true)
        val completed = CountDownLatch(1)
        loader.completeOcr(requireNotNull(attempt.get()), wordPage("§ recognized", TextSource.OCR)) {
            completed.countDown()
        }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(ocrSeen.await(2, TimeUnit.SECONDS))
        loader.dispose()
        index.close()
    }

    @Test fun cappedSearchRefreshRemovesNativeHitsBeforeNonmatchingOrMatchingOcrReplacement() {
        assertCappedWinnerRefresh(wordPage("miss", TextSource.OCR), expectedCount = 9_338, truncated = false)
        assertCappedWinnerRefresh(denseSymbolPage(TextSource.OCR), expectedCount = MAX_TEXT_SEARCH_RESULTS, truncated = true)
    }

    @Test fun capturedNativeUpdateCannotClearNewerOcrUpdateBeforePublication() {
        val delegate = preparedIndex()
        delegate.completeNativeAndReconcile(nativeKey, denseSymbolPage(TextSource.NATIVE_PDF), ocrKey)
        val nativePublishEntered = CountDownLatch(1)
        val releaseNativePublish = CountDownLatch(1)
        val index = BlockingSelectedPublicationIndex(
            delegate,
            nativePublishEntered,
            releaseNativePublish
        )
        val loader = TextPageLoader(
            IndexedTestDocument(listOf(denseSymbolPage(TextSource.NATIVE_PDF))),
            1,
            deliver = { it() },
            index = index,
            indexKey = { nativeKey },
            ocrKey = { ocrKey }
        )
        val initial = CountDownLatch(1)
        val replacement = CountDownLatch(1)
        val callbacksAfterOcr = Collections.synchronizedList(mutableListOf<TextSearchProgress>())
        val ocrCompleted = AtomicBoolean()
        loader.search("§") { progress ->
            if (!progress.running && progress.matches.isNotEmpty() &&
                progress.matches.all { it.source == TextSource.NATIVE_PDF }) {
                initial.countDown()
            }
            if (ocrCompleted.get()) {
                callbacksAfterOcr += progress
                if (!progress.running && progress.matches.isNotEmpty() &&
                    progress.matches.all { it.source == TextSource.OCR }) {
                    replacement.countDown()
                }
            }
        }
        assertTrue(initial.await(2, TimeUnit.SECONDS))

        val claimed = CountDownLatch(1)
        val attempt = AtomicReference<com.folium.reader.index.OcrAttempt>()
        loader.claimOcr(0) { result ->
            attempt.set(requireNotNull((result as OcrCommandResult.Success<OcrTransition>).value.attempt))
            claimed.countDown()
        }
        assertTrue(claimed.await(2, TimeUnit.SECONDS))
        assertTrue(nativePublishEntered.await(2, TimeUnit.SECONDS))

        val completed = CountDownLatch(1)
        loader.completeOcr(
            requireNotNull(attempt.get()),
            wordPage("§", TextSource.OCR)
        ) { completed.countDown() }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        ocrCompleted.set(true)
        releaseNativePublish.countDown()

        assertTrue(replacement.await(2, TimeUnit.SECONDS))
        assertTrue(callbacksAfterOcr.none { progress ->
            progress.matches.any { it.pageIndex == 0 && it.source == TextSource.NATIVE_PDF }
        })
        assertEquals(
            listOf(TextSource.NATIVE_PDF, TextSource.OCR),
            index.fencedSources.toList()
        )
        loader.dispose()
        index.close()
    }

    @Test fun ocrCommandsRunOnReaderTextDeliverOffWorkerAndSurvivePersistenceFailure() {
        val delegate = preparedIndex()
        delegate.completeNativeAndReconcile(nativeKey, TextPage(emptyList(), TextSource.NATIVE_PDF), ocrKey)
        val commandThread = AtomicReference<String>()
        val preparationThread = AtomicReference<String>()
        val index = object : TextPageIndex by delegate {
            var failClaim = true
            override fun prepareOcr(key: OcrPageKey): OcrTransitionOutcome {
                preparationThread.set(Thread.currentThread().name)
                return delegate.prepareOcr(key)
            }
            override fun claimOcr(key: OcrPageKey): OcrTransition {
                commandThread.set(Thread.currentThread().name)
                if (failClaim) {
                    failClaim = false
                    error("persistence unavailable")
                }
                return delegate.claimOcr(key)
            }
        }
        val delivery = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ocr-delivery") }
        val statusEvents = CopyOnWriteArrayList<OcrPageState>()
        val statusThreads = CopyOnWriteArrayList<String>()
        val loader = TextPageLoader(TestDocument { error("unused") }, 1,
            deliver = { action -> delivery.execute { action() } },
            index = index, indexKey = { nativeKey }, ocrKey = { ocrKey },
            onOcrStatusChanged = { _, status ->
                statusEvents += status.state
                statusThreads += Thread.currentThread().name
            })
        val failed = CountDownLatch(1)
        val failure = AtomicReference<OcrCommandResult<OcrTransition>>()
        loader.claimOcr(0) { failure.set(it); failed.countDown() }
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertEquals(OcrCommandError.PERSISTENCE, (failure.get() as OcrCommandResult.Failure).error)
        assertEquals("reader-text", preparationThread.get())
        assertEquals("reader-text", commandThread.get())

        val claimed = CountDownLatch(1)
        val success = AtomicReference<OcrCommandResult<OcrTransition>>()
        val callbackThread = AtomicReference<String>()
        loader.claimOcr(0) {
            callbackThread.set(Thread.currentThread().name)
            success.set(it)
            claimed.countDown()
        }
        assertTrue(claimed.await(2, TimeUnit.SECONDS))
        assertTrue(success.get() is OcrCommandResult.Success)
        assertEquals("ocr-delivery", callbackThread.get())
        val attempt = requireNotNull(
            (success.get() as OcrCommandResult.Success<OcrTransition>).value.attempt
        )
        val cancelled = CountDownLatch(1)
        loader.cancelOcr(attempt) { cancelled.countDown() }
        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        val retried = CountDownLatch(1)
        loader.retryOcr(0) { retried.countDown() }
        assertTrue(retried.await(2, TimeUnit.SECONDS))
        val reclaimed = CountDownLatch(1)
        val reclaimedResult = AtomicReference<OcrCommandResult<OcrTransition>>()
        loader.claimOcr(0) { reclaimedResult.set(it); reclaimed.countDown() }
        assertTrue(reclaimed.await(2, TimeUnit.SECONDS))
        val retryAttempt = requireNotNull(
            (reclaimedResult.get() as OcrCommandResult.Success<OcrTransition>).value.attempt
        )
        val failedReport = CountDownLatch(1)
        loader.failOcr(retryAttempt, "recognition", true) { failedReport.countDown() }
        assertTrue(failedReport.await(2, TimeUnit.SECONDS))
        val invalid = CountDownLatch(1)
        val invalidResult = AtomicReference<OcrCommandResult<OcrTransition>>()
        loader.claimOcr(2) { invalidResult.set(it); invalid.countDown() }
        assertTrue(invalid.await(2, TimeUnit.SECONDS))
        assertEquals(OcrCommandError.INVALID_PAGE,
            (invalidResult.get() as OcrCommandResult.Failure).error)

        loader.close()
        val closed = CountDownLatch(1)
        val closedResult = AtomicReference<OcrCommandResult<OcrTransition>>()
        loader.retryOcr(0) { closedResult.set(it); closed.countDown() }
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertEquals(OcrCommandError.CLOSED, (closedResult.get() as OcrCommandResult.Failure).error)
        assertEquals(
            listOf(
                OcrPageState.RUNNING,
                OcrPageState.CANCELLED,
                OcrPageState.QUEUED,
                OcrPageState.RUNNING,
                OcrPageState.FAILED
            ),
            statusEvents
        )
        assertTrue(statusThreads.all { it == "ocr-delivery" })
        loader.dispose()
        delegate.close()
        delivery.shutdownNow()
    }

    @Test fun failedOcrPreparationDegradesToNativeOnlyAndWorkerContinues() {
        val delegate = TransientTextPageIndex().also { index ->
            index.prepareDocument(nativeKey.bookId, nativeKey.documentVersion)
            index.prepareSource(nativeKey.bookId, nativeKey.documentVersion, nativeKey.source,
                nativeKey.textSchemaVersion, nativeKey.engineVersion)
        }
        val index = object : TextPageIndex by delegate {
            override fun prepareOcr(key: OcrPageKey): OcrTransitionOutcome =
                error("ownership unavailable")
        }
        val pages = listOf(
            wordPage("native searchable", TextSource.NATIVE_PDF),
            TextPage(emptyList(), TextSource.NATIVE_PDF)
        )
        val loader = TextPageLoader(
            IndexedTestDocument(pages), pages.size, deliver = { it() }, index = index,
            indexKey = { nativeKey.copy(pageIndex = it) }, ocrKey = { ocrKey.copy(pageIndex = it) }
        )

        assertEquals(pages[0], (load(loader, 0) as TextPageLoadResult.Loaded).page)
        val unavailable = CountDownLatch(1)
        val unavailableResult = AtomicReference<OcrCommandResult<OcrTransition>>()
        loader.claimOcr(0) { unavailableResult.set(it); unavailable.countDown() }
        assertTrue(unavailable.await(2, TimeUnit.SECONDS))
        assertEquals(OcrCommandError.UNAVAILABLE,
            (unavailableResult.get() as OcrCommandResult.Failure).error)
        assertEquals(pages[1], (load(loader, 1) as TextPageLoadResult.Loaded).page)
        assertEquals(TextPageIndexState.COMPLETE, delegate.state(nativeKey.copy(pageIndex = 0)))
        assertEquals(TextPageIndexState.COMPLETE, delegate.state(nativeKey.copy(pageIndex = 1)))
        assertNull(delegate.ocrStatus(ocrKey))

        val searched = CountDownLatch(1)
        val matches = AtomicInteger()
        loader.search("searchable") { progress ->
            matches.set(progress.matches.size)
            if (!progress.running) searched.countDown()
        }
        assertTrue(searched.await(2, TimeUnit.SECONDS))
        assertEquals(1, matches.get())
        loader.dispose()
        delegate.close()
    }

    @Test fun boundedOcrFloodReportsOverflowAndForegroundPreemptsAcceptedCommands() {
        val delegate = preparedIndex()
        delegate.completeNativeAndReconcile(nativeKey, TextPage(emptyList(), TextSource.NATIVE_PDF), ocrKey)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val index = object : TextPageIndex by delegate {
            override fun claimOcr(key: OcrPageKey): OcrTransition {
                if (firstStarted.count > 0L) {
                    firstStarted.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                }
                return delegate.claimOcr(key)
            }
        }
        val loader = TextPageLoader(
            TestDocument { wordPage("foreground", TextSource.NATIVE_PDF) }, 1,
            deliver = { it() }, index = index, indexKey = { nativeKey }, ocrKey = { ocrKey }
        )
        val accepted = CountDownLatch(MAX_OCR_COMMAND_QUEUE + 1)
        loader.claimOcr(0) { accepted.countDown() }
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        val foregroundServed = AtomicBoolean()
        val queuedBeforeForeground = AtomicBoolean()
        val callbackOrder = Collections.synchronizedList(mutableListOf<Int>())
        repeat(MAX_OCR_COMMAND_QUEUE) { commandId ->
            loader.claimOcr(0) {
                if (!foregroundServed.get()) queuedBeforeForeground.set(true)
                callbackOrder += commandId
                accepted.countDown()
            }
        }
        val overflow = CountDownLatch(1)
        val overflowResult = AtomicReference<OcrCommandResult<OcrTransition>>()
        loader.claimOcr(0) { overflowResult.set(it); overflow.countDown() }
        assertTrue(overflow.await(2, TimeUnit.SECONDS))
        assertEquals(OcrCommandError.OVERFLOW,
            (overflowResult.get() as OcrCommandResult.Failure).error)
        val foreground = CountDownLatch(1)
        loader.load(0) {
            foregroundServed.set(true)
            foreground.countDown()
        }

        releaseFirst.countDown()

        assertTrue(foreground.await(2, TimeUnit.SECONDS))
        assertTrue(accepted.await(2, TimeUnit.SECONDS))
        assertFalse(queuedBeforeForeground.get())
        assertEquals((0 until MAX_OCR_COMMAND_QUEUE).toList(), callbackOrder)
        loader.dispose()
        delegate.close()
    }

    @Test fun planningOverflowUsesOneDeferredSlotAndRunsWhenCommandCapacityReturns() {
        val delegate = preparedIndex()
        delegate.completeNativeAndReconcile(nativeKey, TextPage(emptyList(), TextSource.NATIVE_PDF), ocrKey)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val index = object : TextPageIndex by delegate {
            override fun claimOcr(key: OcrPageKey): OcrTransition {
                if (firstStarted.count > 0L) {
                    firstStarted.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                }
                return delegate.claimOcr(key)
            }
        }
        val loader = TextPageLoader(
            TestDocument { TextPage(emptyList(), TextSource.NATIVE_PDF) },
            1,
            deliver = { it() },
            index = index,
            indexKey = { nativeKey },
            ocrKey = { ocrKey }
        )
        loader.claimOcr(0) {}
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        repeat(MAX_OCR_COMMAND_QUEUE) { loader.claimOcr(0) {} }
        val planned = CountDownLatch(1)
        val result = AtomicReference<OcrCommandResult<com.folium.reader.index.OcrPlanningBatch>>()

        loader.planOcr(0, -1, 1, 16) {
            result.set(it)
            planned.countDown()
        }
        releaseFirst.countDown()

        assertTrue(planned.await(2, TimeUnit.SECONDS))
        assertTrue(result.get() is OcrCommandResult.Success)
        loader.dispose()
        delegate.close()
    }

    @Test fun continuouslyReplenishedOcrQueueCannotStarveSearchSlices() {
        val index = preparedIndex()
        val pages = List(6) { pageIndex -> wordPage("target-$pageIndex", TextSource.NATIVE_PDF) }
        val firstExtraction = CountDownLatch(1)
        val releaseExtraction = CountDownLatch(1)
        val document = IndexedTestDocument(pages) { pageIndex ->
            if (pageIndex == 0 && firstExtraction.count > 0L) {
                firstExtraction.countDown()
                releaseExtraction.await(2, TimeUnit.SECONDS)
            }
        }
        val loader = TextPageLoader(
            document, pages.size, deliver = { it() }, index = index,
            indexKey = { nativeKey.copy(pageIndex = it) }, ocrKey = { ocrKey.copy(pageIndex = it) }
        )
        val searchComplete = CountDownLatch(1)
        val finalProgress = AtomicReference<TextSearchProgress>()
        loader.search("target") { progress ->
            finalProgress.set(progress)
            if (!progress.running) searchComplete.countDown()
        }
        assertTrue(firstExtraction.await(2, TimeUnit.SECONDS))
        val producing = AtomicBoolean(true)
        val overflowSeen = CountDownLatch(1)
        val producer = Thread({
            while (producing.get()) {
                loader.claimOcr(0) { result ->
                    if (result is OcrCommandResult.Failure && result.error == OcrCommandError.OVERFLOW) {
                        overflowSeen.countDown()
                    }
                }
                Thread.yield()
            }
        }, "ocr-command-producer").apply { isDaemon = true }
        producer.start()
        assertTrue(overflowSeen.await(2, TimeUnit.SECONDS))
        releaseExtraction.countDown()

        assertTrue(searchComplete.await(5, TimeUnit.SECONDS))
        producing.set(false)
        producer.join(2_000)
        assertEquals(pages.size, finalProgress.get().indexedPages)
        assertEquals(pages.size, finalProgress.get().matches.size)
        loader.dispose()
        index.close()
    }

    private fun preparedIndex() = TransientTextPageIndex().also { index ->
        index.prepareDocument(nativeKey.bookId, nativeKey.documentVersion)
        index.prepareSource(nativeKey.bookId, nativeKey.documentVersion, nativeKey.source,
            nativeKey.textSchemaVersion, nativeKey.engineVersion)
        index.prepareOcr(ocrKey)
    }

    private class BlockingSelectedPublicationIndex(
        private val delegate: TransientTextPageIndex,
        private val nativePublishEntered: CountDownLatch,
        private val releaseNativePublish: CountDownLatch
    ) : TextPageIndex by delegate {
        val fencedSources = Collections.synchronizedList(mutableListOf<TextSource>())
        private val blockNative = AtomicBoolean(true)

        override fun publishIfSelected(
            key: TextPageIndexKey,
            publication: () -> Unit
        ): TextPagePublicationOutcome {
            fencedSources += key.source
            if (key.source == TextSource.NATIVE_PDF && blockNative.compareAndSet(true, false)) {
                nativePublishEntered.countDown()
                releaseNativePublish.await()
            }
            return delegate.publishIfSelected(key, publication)
        }
    }

    private fun assertCappedWinnerRefresh(
        ocrPage: TextPage,
        expectedCount: Int,
        truncated: Boolean
    ) {
        val pageCount = 15
        val index = preparedIndex()
        val nativePage = denseSymbolPage(TextSource.NATIVE_PDF)
        index.completeNativeAndReconcile(nativeKey, nativePage, ocrKey)
        for (pageIndex in 1 until pageCount) {
            index.complete(nativeKey.copy(pageIndex = pageIndex), nativePage)
        }
        val loader = TextPageLoader(
            IndexedTestDocument(List(pageCount) { nativePage }),
            pageCount,
            deliver = { it() },
            index = index,
            indexKey = { nativeKey.copy(pageIndex = it) },
            ocrKey = { ocrKey.copy(pageIndex = it) }
        )
        val initial = CountDownLatch(1)
        val replacement = CountDownLatch(1)
        val latest = AtomicReference<TextSearchProgress>()
        loader.search("§") { progress ->
            latest.set(progress)
            if (!progress.running && progress.matches.size == MAX_TEXT_SEARCH_RESULTS) initial.countDown()
            if (!progress.running && progress.matches.size == expectedCount &&
                progress.truncated == truncated &&
                (ocrPage.text == "miss" || progress.matches.any { it.pageIndex == 0 && it.source == TextSource.OCR })
            ) replacement.countDown()
        }
        assertTrue(initial.await(5, TimeUnit.SECONDS))

        val claimed = CountDownLatch(1)
        val attempt = AtomicReference<com.folium.reader.index.OcrAttempt>()
        loader.claimOcr(0) { result ->
            attempt.set(requireNotNull((result as OcrCommandResult.Success<OcrTransition>).value.attempt))
            claimed.countDown()
        }
        assertTrue(claimed.await(2, TimeUnit.SECONDS))
        val completed = CountDownLatch(1)
        loader.completeOcr(requireNotNull(attempt.get()), ocrPage) { completed.countDown() }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(replacement.await(5, TimeUnit.SECONDS))

        val final = latest.get()
        assertEquals(expectedCount, final.matches.size)
        assertEquals(truncated, final.truncated)
        if (ocrPage.text == "miss") assertTrue(final.matches.none { it.pageIndex == 0 })
        loader.dispose()
        index.close()
    }

    private fun denseSymbolPage(source: TextSource) = TextPage(
        listOf(TextBlock(listOf(TextLine(List(667) { ordinal ->
            TextWord("§", PageSpaceRect(0f, 0f, 1f, 1f), ordinal)
        }, 0)), 0)),
        source
    )

    private fun loader(
        index: TransientTextPageIndex,
        onOcrEligible: (Int) -> Unit = {},
        extract: () -> TextPage
    ) = TextPageLoader(
        TestDocument(extract), 1, deliver = { it() }, index = index,
        indexKey = { nativeKey }, ocrKey = { ocrKey }, onOcrEligible = onOcrEligible
    )

    private fun load(loader: TextPageLoader, pageIndex: Int = 0): TextPageLoadResult {
        val delivered = CountDownLatch(1)
        lateinit var result: TextPageLoadResult
        loader.load(pageIndex) { result = it; delivered.countDown() }
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        return result
    }

    private fun claim(loader: TextPageLoader, pageIndex: Int): com.folium.reader.index.OcrAttempt {
        val completed = CountDownLatch(1)
        val attempt = AtomicReference<com.folium.reader.index.OcrAttempt>()
        loader.claimOcr(pageIndex) { result ->
            attempt.set(requireNotNull((result as OcrCommandResult.Success<OcrTransition>).value.attempt))
            completed.countDown()
        }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        return requireNotNull(attempt.get())
    }

    private fun complete(
        loader: TextPageLoader,
        attempt: com.folium.reader.index.OcrAttempt,
        page: TextPage
    ) {
        val completed = CountDownLatch(1)
        loader.completeOcr(attempt, page) { completed.countDown() }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
    }
}

private class IndexedTestDocument(
    private val pages: List<TextPage>,
    private val onExtract: (Int) -> Unit = {}
) : PdfDocument {
    override val pageCount = pages.size
    override fun pageInfo(index: Int) = PageInfo(index, 1f, 1f, 0)
    override fun buildDisplayList(index: Int) = object : DisplayList {
        override fun render(spec: RenderSpec, cancellationSignal: CancellationSignal) = Raster(1, 1, ByteArray(4))
        override fun close() = Unit
    }
    override fun extractText(index: Int): TextPage {
        onExtract(index)
        return pages[index]
    }
    override fun outline(): List<OutlineEntry> = emptyList()
    override fun close() = Unit
}

private class TestDocument(private val extract: () -> TextPage) : PdfDocument {
    override val pageCount = 1
    override fun pageInfo(index: Int) = PageInfo(index, 1f, 1f, 0)
    override fun buildDisplayList(index: Int) = object : DisplayList {
        override fun render(spec: RenderSpec, cancellationSignal: CancellationSignal) = Raster(1, 1, ByteArray(4))
        override fun close() = Unit
    }
    override fun extractText(index: Int) = extract()
    override fun outline(): List<OutlineEntry> = emptyList()
    override fun close() = Unit
}

private fun wordPage(text: String, source: TextSource) = TextPage(
    listOf(TextBlock(listOf(TextLine(listOf(TextWord(text, PageSpaceRect(0f, 0f, 1f, 1f), 0)), 0)), 0)),
    source
)
