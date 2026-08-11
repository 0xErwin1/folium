package com.folium.reader.index

import com.folium.reader.core.library.BookId
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.NATIVE_TEXT_USABILITY_POLICY_VERSION
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.text.TextBlock
import com.folium.reader.core.text.TextLine
import com.folium.reader.core.text.TextWord
import com.folium.reader.core.text.TextSearchMode
import com.folium.reader.core.text.TextSearchSpec
import com.folium.reader.core.ocr.OcrCancellationReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TransientTextPageIndexTest {
    private val key = TextPageIndexKey(
        BookId("book"), DocumentContentVersion("ab".repeat(32)), 0,
        TextSource.NATIVE_PDF, 1, TextEngineVersion("native-v1")
    )
    private val page = TextPage(emptyList(), TextSource.NATIVE_PDF)
    private val ocrKey = OcrPageKey(key.bookId, key.documentVersion, 0, key.textSchemaVersion,
        key.engineVersion, NATIVE_TEXT_USABILITY_POLICY_VERSION, TextEngineVersion("ocr-v1|eng:data"))

    @Test fun fallbackRetainsPagesOnlyForItsCurrentSession() {
        val failure = IllegalStateException("derived index unavailable")
        val first = TransientTextPageIndex(failure)
        prepare(first)

        assertSame(failure, first.fallbackFailure)
        assertEquals(TextPageIndexWriteOutcome.APPLIED, first.markInProgress(key).outcome)
        assertEquals(TextPageIndexWriteOutcome.APPLIED, first.complete(key, page))
        assertEquals(page, first.load(key))
        first.close()

        val nextSession = TransientTextPageIndex()
        prepare(nextSession)
        assertNull(nextSession.load(key))
        nextSession.close()
    }

    @Test fun closeInsideCrossThreadPublicationCallbackReturnsAndRejectsFutureOperations() {
        val index = TransientTextPageIndex()
        prepare(index)
        index.complete(key, page)
        val callbackReturned = CountDownLatch(1)
        val publicationFinished = CountDownLatch(1)
        val outcome = AtomicReference<TextPagePublicationOutcome>()
        val worker = transientThread("transient-publication") {
            outcome.set(index.publishIfCurrent(key) {
                val callback = transientThread("transient-callback") {
                    try {
                        index.runPublicationCallback {
                            index.close()
                            assertNull(index.load(key))
                            assertEquals(
                                TextPageIndexWriteOutcome.STALE,
                                index.complete(key, page)
                            )
                        }
                    } finally {
                        callbackReturned.countDown()
                    }
                }
                callback.start()
                assertTrue(callbackReturned.await(2, TimeUnit.SECONDS))
                callback.join(2_000)
            })
            publicationFinished.countDown()
        }

        worker.start()
        assertTrue(callbackReturned.await(2, TimeUnit.SECONDS))
        assertTrue(publicationFinished.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
        index.close()

        assertEquals(TextPagePublicationOutcome.INVALIDATED_DURING_PUBLICATION, outcome.get())
        assertFalse(worker.isAlive)
    }

    @Test fun callbackCloseReturnsWhenAnOutsideCloseIsAlreadyWaitingForPublication() {
        val index = TransientTextPageIndex()
        prepare(index)
        index.complete(key, page)
        val publicationEntered = CountDownLatch(1)
        val allowCallback = CountDownLatch(1)
        val callbackReturned = CountDownLatch(1)
        val publicationFinished = CountDownLatch(1)
        val worker = transientThread("transient-publication") {
            index.publishIfCurrent(key) {
                publicationEntered.countDown()
                allowCallback.await(2, TimeUnit.SECONDS)
                val callback = transientThread("transient-callback") {
                    index.runPublicationCallback { index.close() }
                    callbackReturned.countDown()
                }
                callback.start()
                callbackReturned.await(2, TimeUnit.SECONDS)
                callback.join(2_000)
            }
            publicationFinished.countDown()
        }
        worker.start()
        assertTrue(publicationEntered.await(2, TimeUnit.SECONDS))
        val outsideCloseReturned = CountDownLatch(1)
        val outsideClose = transientThread("transient-close") {
            index.close()
            outsideCloseReturned.countDown()
        }
        outsideClose.start()

        waitUntil(index::isClosed)
        assertNull(index.load(key))
        assertFalse(outsideCloseReturned.await(100, TimeUnit.MILLISECONDS))
        allowCallback.countDown()

        assertTrue(callbackReturned.await(2, TimeUnit.SECONDS))
        assertTrue(publicationFinished.await(2, TimeUnit.SECONDS))
        assertTrue(outsideCloseReturned.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
        outsideClose.join(2_000)
    }

    @Test fun transientOcrTransitionsFenceLateAttemptsRecoverAndRequireExplicitRetry() {
        val index = TransientTextPageIndex()
        prepare(index)
        assertEquals(OcrTransitionOutcome.APPLIED, index.prepareOcr(ocrKey))
        index.completeNativeAndReconcile(key, page, ocrKey)
        val first = requireNotNull(index.claimOcr(ocrKey).attempt)
        assertEquals(OcrTransitionOutcome.APPLIED, index.cancelOcr(first).outcome)
        index.completeNativeAndReconcile(key, page, ocrKey)
        assertEquals(OcrCancellationReason.USER, index.ocrStatus(ocrKey)?.cancellationReason)
        assertEquals(OcrTransitionOutcome.APPLIED, index.retryOcr(ocrKey).outcome)
        assertEquals(OcrTransitionOutcome.GENERATION_MISMATCH,
            index.completeOcr(first, wordPage("late", TextSource.OCR)).outcome)

        val second = requireNotNull(index.claimOcr(ocrKey).attempt)
        index.prepareOcr(ocrKey)
        assertEquals(OcrPageState.QUEUED, index.ocrStatus(ocrKey)?.state)
        assertEquals(second.generation + 1, index.ocrStatus(ocrKey)?.generation)
        val recovered = requireNotNull(index.claimOcr(ocrKey).attempt)
        index.failOcr(recovered, "recognition", true)
        assertEquals(OcrPageState.FAILED, index.ocrStatus(ocrKey)?.state)
        index.completeNativeAndReconcile(key, page, ocrKey)
        assertEquals(OcrPageState.FAILED, index.ocrStatus(ocrKey)?.state)
        index.close()
    }

    @Test fun searchPauseResumeFencesLateCancellationGeneration() {
        val index = TransientTextPageIndex()
        prepare(index)
        index.prepareOcr(ocrKey)
        index.completeNativeAndReconcile(key, page, ocrKey)
        val pausedAttempt = requireNotNull(index.claimOcr(ocrKey).attempt)
        index.cancelOcr(pausedAttempt, OcrCancellationReason.SEARCH_PAUSE)

        assertEquals(OcrTransitionOutcome.APPLIED, index.resumePausedOcr(ocrKey).outcome)
        val resumedAttempt = requireNotNull(index.claimOcr(ocrKey).attempt)
        assertEquals(pausedAttempt.generation + 1, resumedAttempt.generation)
        assertEquals(
            OcrTransitionOutcome.GENERATION_MISMATCH,
            index.cancelOcr(pausedAttempt, OcrCancellationReason.SEARCH_PAUSE).outcome
        )
        assertEquals(OcrPageState.RUNNING, index.ocrStatus(ocrKey)?.state)
        assertEquals(OcrTransitionOutcome.APPLIED,
            index.completeOcr(resumedAttempt, wordPage("recognized", TextSource.OCR)).outcome)
        index.close()
    }

    @Test fun transientNativePrecedenceAndOcrFallbackMatchSearchWinner() {
        val index = TransientTextPageIndex()
        prepare(index)
        index.prepareOcr(ocrKey)
        index.completeNativeAndReconcile(key, page, ocrKey)
        val attempt = requireNotNull(index.claimOcr(ocrKey).attempt)
        index.completeOcr(attempt, wordPage("recognized", TextSource.OCR))
        assertEquals("recognized", index.loadSelected(key, ocrKey)?.text)
        assertEquals(listOf(TextSource.OCR), search(index, "recognized").map { it.source })

        index.completeNativeAndReconcile(key, wordPage("native", TextSource.NATIVE_PDF), ocrKey)
        assertEquals("native", index.loadSelected(key, ocrKey)?.text)
        assertTrue(search(index, "recognized").isEmpty())
        assertEquals(listOf(TextSource.NATIVE_PDF), search(index, "native").map { it.source })
        index.close()
    }

    @Test fun transientUnusableNativeRemainsSearchFallbackThroughIncompleteOcrStates() {
        val index = TransientTextPageIndex()
        val native = wordPage("§", TextSource.NATIVE_PDF)
        prepare(index)
        index.prepareOcr(ocrKey)
        index.completeNativeAndReconcile(key, native, ocrKey)
        assertEquals(listOf(TextSource.NATIVE_PDF), search(index, "§").map { it.source })

        val running = requireNotNull(index.claimOcr(ocrKey).attempt)
        assertEquals(listOf(TextSource.NATIVE_PDF), search(index, "§").map { it.source })
        index.failOcr(running, "recognition", retryable = true)
        assertEquals(listOf(TextSource.NATIVE_PDF), search(index, "§").map { it.source })

        index.retryOcr(ocrKey)
        val cancelled = requireNotNull(index.claimOcr(ocrKey).attempt)
        index.cancelOcr(cancelled)
        assertEquals(listOf(TextSource.NATIVE_PDF), search(index, "§").map { it.source })

        index.retryOcr(ocrKey)
        val completed = requireNotNull(index.claimOcr(ocrKey).attempt)
        index.completeOcr(completed, wordPage("recognized", TextSource.OCR))
        assertTrue(search(index, "§").isEmpty())
        assertEquals(listOf(TextSource.OCR), search(index, "recognized").map { it.source })
        index.close()
    }

    @Test fun nativeSuppressionQueuesAgainOnlyAfterNativeBecomesUnusable() {
        val index = TransientTextPageIndex()
        prepare(index)
        index.prepareOcr(ocrKey)
        index.completeNativeAndReconcile(key, wordPage("native", TextSource.NATIVE_PDF), ocrKey)
        assertEquals(OcrCancellationReason.NATIVE_TEXT, index.ocrStatus(ocrKey)?.cancellationReason)

        index.completeNativeAndReconcile(key, page, ocrKey)

        assertEquals(OcrPageState.QUEUED, index.ocrStatus(ocrKey)?.state)
        index.close()
    }

    @Test fun exactStatusAndClaimRejectEverySupersededOwnershipDimension() {
        val index = TransientTextPageIndex()
        prepare(index)
        index.prepareOcr(ocrKey)
        index.completeNativeAndReconcile(key, page, ocrKey)
        val oldAttempt = requireNotNull(index.claimOcr(ocrKey).attempt)
        val changedNative = TextEngineVersion("native-v2")
        index.prepareSource(key.bookId, key.documentVersion, TextSource.NATIVE_PDF,
            key.textSchemaVersion, changedNative)
        val current = ocrKey.copy(nativeEngineVersion = changedNative,
            usabilityPolicyVersion = "native-usability-v2",
            ocrEngineVersion = TextEngineVersion("ocr-v2|eng:data-v2"))
        index.prepareOcr(current)

        assertNull(index.ocrStatus(ocrKey))
        assertEquals(OcrTransitionOutcome.STALE, index.claimOcr(ocrKey).outcome)
        assertEquals(OcrTransitionOutcome.STALE,
            index.completeOcr(oldAttempt, wordPage("late", TextSource.OCR)).outcome)
        index.close()
    }

    @Test fun incompatibleNativeAndOcrKeysRejectEveryDimensionBeforeMutation() {
        val index = TransientTextPageIndex()
        prepare(index)
        index.prepareOcr(ocrKey)
        index.completeNativeAndReconcile(key, wordPage("before", TextSource.NATIVE_PDF), ocrKey)
        val statusBefore = index.ocrStatus(ocrKey)
        val mismatches = listOf(
            ocrKey.copy(bookId = BookId("other-book")),
            ocrKey.copy(documentVersion = DocumentContentVersion("cd".repeat(32))),
            ocrKey.copy(pageIndex = 1),
            ocrKey.copy(textSchemaVersion = key.textSchemaVersion + 1),
            ocrKey.copy(nativeEngineVersion = TextEngineVersion("native-v2"))
        )

        mismatches.forEach { incompatible ->
            assertFalse(key.isCompatibleNativeOwner(incompatible))
            assertEquals(TextPageIndexWriteOutcome.STALE,
                index.completeNativeAndReconcile(
                    key, wordPage("mutated", TextSource.NATIVE_PDF), incompatible
                ))
            assertEquals("before", index.loadSelected(key, ocrKey)?.text)
            assertEquals(statusBefore, index.ocrStatus(ocrKey))
            assertTrue(search(index, "mutated").isEmpty())
            assertEquals(1, search(index, "before").size)
        }
        index.close()
    }

    @Test fun selectedPublicationMovesFromUnusableNativeToCompletedOcr() {
        val index = TransientTextPageIndex()
        prepare(index)
        index.prepareOcr(ocrKey)
        index.completeNativeAndReconcile(key, page, ocrKey)
        assertEquals(TextPagePublicationOutcome.CURRENT, index.publishIfSelected(key) {})
        val attempt = requireNotNull(index.claimOcr(ocrKey).attempt)
        index.completeOcr(attempt, wordPage("recognized", TextSource.OCR))

        assertEquals(TextPagePublicationOutcome.NOT_CURRENT, index.publishIfSelected(key) {})
        assertEquals(TextPagePublicationOutcome.CURRENT,
            index.publishIfSelected(ocrKey.textKey()) {})
        index.close()
    }

    @Test fun regexCaseAndWholeWordSemanticsMatchCoreMatcher() {
        val index = TransientTextPageIndex()
        prepare(index)
        index.complete(key, wordPage("Café cafe2 CAFE", TextSource.NATIVE_PDF))

        val hits = search(index, TextSearchSpec("cafe", TextSearchMode.REGEX, wholeWord = true))

        assertEquals(2, hits.size)
        assertEquals(listOf(0, 1), hits.map { it.occurrenceIndex })
        index.close()
    }

    private fun prepare(index: TextPageIndex) {
        index.prepareDocument(key.bookId, key.documentVersion)
        assertEquals(
            TextPageIndexWriteOutcome.APPLIED,
            index.prepareSource(
                key.bookId, key.documentVersion, key.source, key.textSchemaVersion, key.engineVersion
            )
        )
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }

    private fun search(index: TextPageIndex, query: String): List<TextPageSearchHit> {
        var result: List<TextPageSearchHit>? = null
        assertEquals(TextPagePublicationOutcome.CURRENT,
            index.searchIfCurrent(key.bookId, key.documentVersion, query) { result = it.hits })
        return requireNotNull(result)
    }

    private fun search(index: TextPageIndex, spec: TextSearchSpec): List<TextPageSearchHit> {
        var result: List<TextPageSearchHit>? = null
        assertEquals(TextPagePublicationOutcome.CURRENT,
            index.searchIfCurrent(key.bookId, key.documentVersion, spec) { result = it.hits })
        return requireNotNull(result)
    }
}

private fun wordPage(text: String, source: TextSource) = TextPage(
    listOf(TextBlock(listOf(TextLine(listOf(TextWord(text, PageSpaceRect(0f, 0f, 1f, 1f), 0)), 0)), 0)),
    source
)

private fun transientThread(name: String, block: () -> Unit) = Thread(block, name).apply { isDaemon = true }
