package com.folium.reader.reader

import com.folium.reader.core.library.BookId
import com.folium.reader.core.ocr.OcrEngine
import com.folium.reader.core.ocr.OcrCancellationReason
import com.folium.reader.core.ocr.OcrEngineDescriptor
import com.folium.reader.core.ocr.OcrEngineEnvironment
import com.folium.reader.core.ocr.OcrException
import com.folium.reader.core.ocr.OcrFailure
import com.folium.reader.core.ocr.OcrPageState
import com.folium.reader.core.ocr.OcrPageStatus
import com.folium.reader.core.ocr.OcrRequest
import com.folium.reader.core.ocr.PageImage
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
import com.folium.reader.core.text.TextSearchSpec
import com.folium.reader.core.text.TextWord
import com.folium.reader.index.DocumentContentVersion
import com.folium.reader.index.OcrAttempt
import com.folium.reader.index.OcrPageKey
import com.folium.reader.index.OcrTransition
import com.folium.reader.index.OcrTransitionOutcome
import com.folium.reader.index.TextPageIndexKey
import com.folium.reader.index.TransientTextPageIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.ByteArrayInputStream
import java.io.File

class OcrPagePipelineTest {
    @Test fun createsUsesAndClosesEngineOnOneLowPriorityOwningThread() {
        val completed = CountDownLatch(1)
        val reporter = RecordingReporter(setOf(0), completed = completed)
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        val priorities = Collections.synchronizedList(mutableListOf<Int>())
        val requests = Collections.synchronizedList(mutableListOf<OcrRequest>())
        val specs = Collections.synchronizedList(mutableListOf<RenderSpec>())
        val pipeline = pipeline(
            document = FakePdfDocument(specs = specs),
            reporter = reporter,
            engineFactory = {
                threads += Thread.currentThread()
                priorities += Thread.currentThread().priority
                object : OcrEngine {
                    override fun textEngineVersion(request: OcrRequest) = TextEngineVersion("fake-ocr")
                    override fun recognize(image: PageImage, request: OcrRequest, cancellationSignal: CancellationSignal): TextPage {
                        threads += Thread.currentThread()
                        requests += request
                        assertEquals(900, image.width)
                        assertEquals(1_200, image.height)
                        return ocrPage("recognized")
                    }
                    override fun close() {
                        threads += Thread.currentThread()
                    }
                }
            }
        )

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        pipeline.dispose()

        assertEquals(3, threads.size)
        assertTrue(threads.all { it === threads.first() })
        assertEquals(listOf(Thread.MIN_PRIORITY), priorities)
        assertEquals(listOf(OcrRequest.DEFAULT), requests)
        assertEquals(PageSpaceRect(0f, 0f, 1f, 1f), specs.single().pageSpace)
        assertEquals("recognized", reporter.completedPages.single().text)
    }

    @Test fun nativeUsablePagesNeverConstructOrRunTheEngine() {
        val claimsFinished = CountDownLatch(2)
        val reporter = RecordingReporter(emptySet(), claimsFinished = claimsFinished)
        val engineCreations = AtomicInteger()
        val pipeline = pipeline(
            document = FakePdfDocument(pageCount = 2),
            reporter = reporter,
            engineFactory = {
                engineCreations.incrementAndGet()
                FakeOcrEngine()
            }
        )

        assertTrue(claimsFinished.await(2, TimeUnit.SECONDS))
        pipeline.dispose()

        assertEquals(listOf(0, 1), reporter.claimedPages)
        assertEquals(0, engineCreations.get())
        assertTrue(reporter.completedPages.isEmpty())
    }

    @Test fun typedPageFailureDoesNotStopLaterPages() {
        val terminal = CountDownLatch(2)
        val reporter = RecordingReporter(setOf(0, 1), completed = terminal, failed = terminal)
        val calls = AtomicInteger()
        val pipeline = pipeline(
            document = FakePdfDocument(pageCount = 2),
            reporter = reporter,
            engineFactory = {
                object : FakeOcrEngine() {
                    override fun recognize(image: PageImage, request: OcrRequest, cancellationSignal: CancellationSignal): TextPage {
                        if (calls.getAndIncrement() == 0) throw OcrException(OcrFailure.Recognition)
                        return ocrPage("second")
                    }
                }
            }
        )

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        pipeline.dispose()

        assertEquals(listOf(Triple(0, "ocr-recognition", true)), reporter.failures)
        assertEquals(listOf("second"), reporter.completedPages.map(TextPage::text))
    }

    @Test fun completionGenerationMismatchIsNotRetriedOrRepublished() {
        val completed = CountDownLatch(1)
        val reporter = RecordingReporter(
            eligiblePages = setOf(0),
            completed = completed,
            completionOutcome = OcrTransitionOutcome.GENERATION_MISMATCH
        )
        val recognitionCalls = AtomicInteger()
        val pipeline = pipeline(
            document = FakePdfDocument(),
            reporter = reporter,
            engineFactory = {
                object : FakeOcrEngine() {
                    override fun recognize(image: PageImage, request: OcrRequest, cancellationSignal: CancellationSignal): TextPage {
                        recognitionCalls.incrementAndGet()
                        return ocrPage("stale")
                    }
                }
            }
        )

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)
        pipeline.dispose()

        assertEquals(1, recognitionCalls.get())
        assertEquals(OcrTransitionOutcome.GENERATION_MISMATCH, reporter.completionOutcomes.single())
    }

    @Test fun foregroundPreemptsOcrAndRecognitionRestartsAfterVisibleWork() {
        val firstRecognition = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val reporter = RecordingReporter(setOf(0), completed = completed)
        val gate = DocumentPriorityGate()
        val calls = AtomicInteger()
        val pipeline = pipeline(
            document = FakePdfDocument(),
            reporter = reporter,
            priorityGate = gate,
            engineFactory = {
                object : FakeOcrEngine() {
                    override fun recognize(image: PageImage, request: OcrRequest, cancellationSignal: CancellationSignal): TextPage {
                        val call = calls.incrementAndGet()
                        if (call == 1) {
                            firstRecognition.countDown()
                            while (!cancellationSignal.isCancelled()) Thread.yield()
                            throw OcrException(OcrFailure.Cancelled)
                        }
                        return ocrPage("after-visible")
                    }
                }
            }
        )
        assertTrue(firstRecognition.await(2, TimeUnit.SECONDS))

        gate.foreground { Thread.sleep(20) }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        pipeline.dispose()
        assertEquals(2, calls.get())
        assertEquals("after-visible", reporter.completedPages.single().text)
    }

    @Test fun cancellationReportsAttemptAndClosesTheActivePageImage() {
        val recognitionStarted = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val reporter = RecordingReporter(setOf(0), cancelled = cancelled)
        val recognizedImage = AtomicReference<PageImage>()
        val pipeline = pipeline(
            document = FakePdfDocument(),
            reporter = reporter,
            engineFactory = {
                object : FakeOcrEngine() {
                    override fun recognize(image: PageImage, request: OcrRequest, cancellationSignal: CancellationSignal): TextPage {
                        recognizedImage.set(image)
                        recognitionStarted.countDown()
                        while (!cancellationSignal.isCancelled()) Thread.yield()
                        assertTrue(cancellationSignal.isCancelled())
                        throw OcrException(OcrFailure.Cancelled)
                    }
                }
            }
        )
        assertTrue(recognitionStarted.await(2, TimeUnit.SECONDS))

        pipeline.cancel()

        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        pipeline.dispose()
        assertEquals(listOf(0), reporter.cancelledPages)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            requireNotNull(recognizedImage.get()).pixels()
        }
    }

    @Test fun closePersistsActiveAttemptAsCancelledBeforeWorkerStops() {
        val recognitionStarted = CountDownLatch(1)
        val workerStopped = CountDownLatch(1)
        val index = preparedOcrIndex()
        val pipeline = OcrPagePipeline(
            FakePdfDocument(),
            1,
            engineFactory = {
                object : FakeOcrEngine() {
                    override fun recognize(
                        image: PageImage,
                        request: OcrRequest,
                        cancellationSignal: CancellationSignal
                    ): TextPage {
                        recognitionStarted.countDown()
                        while (!cancellationSignal.isCancelled()) Thread.yield()
                        throw OcrException(OcrFailure.Cancelled)
                    }
                }
            },
            reporter = IndexReporter(index),
            priorityGate = DocumentPriorityGate(),
            policy = OcrRasterPolicy(24L * 1024 * 1024),
            onStopped = workerStopped::countDown
        )
        assertTrue(recognitionStarted.await(2, TimeUnit.SECONDS))

        pipeline.close()
        pipeline.dispose()

        assertTrue(workerStopped.await(2, TimeUnit.SECONDS))
        assertEquals(OcrPageState.CANCELLED, index.ocrStatus(ocrKey(0))?.state)
        assertEquals(null, index.load(ocrKey(0).textKey()))
        index.close()
    }

    @Test fun failedCancellationReportIsContainedAndLaterPageStillCompletes() {
        val recognitionStarted = CountDownLatch(1)
        val cancellationFallback = CountDownLatch(1)
        val secondCompleted = CountDownLatch(1)
        val reporter = CancelFailureReporter(cancellationFallback, secondCompleted)
        val calls = AtomicInteger()
        val pipeline = pipeline(
            document = FakePdfDocument(pageCount = 2),
            reporter = reporter,
            engineFactory = {
                object : FakeOcrEngine() {
                    override fun recognize(
                        image: PageImage,
                        request: OcrRequest,
                        cancellationSignal: CancellationSignal
                    ): TextPage {
                        if (calls.getAndIncrement() == 0) {
                            recognitionStarted.countDown()
                            while (!cancellationSignal.isCancelled()) Thread.yield()
                            throw OcrException(OcrFailure.Cancelled)
                        }
                        return ocrPage("second")
                    }
                }
            }
        )
        assertTrue(recognitionStarted.await(2, TimeUnit.SECONDS))

        pipeline.cancel()
        assertTrue(cancellationFallback.await(2, TimeUnit.SECONDS))
        pipeline.resume()

        assertTrue(secondCompleted.await(2, TimeUnit.SECONDS))
        pipeline.dispose()
        assertEquals(listOf("cancel-report"), reporter.fallbackKinds)
        assertEquals(listOf(1), reporter.completedPages)
    }

    @Test fun rasterPolicyBoundsWorkingCopiesAndAlwaysUsesFullPageSpace() {
        val policy = OcrRasterPolicy(maxWorkingBytes = 6L * 1024 * 1024, preferredLongEdge = 4_000)
        val spec = policy.renderSpec(PageInfo(0, 20_000f, 10_000f, 0))

        assertTrue(policy.workingBytes(spec) <= policy.maxWorkingBytes)
        assertEquals(PageSpaceRect(0f, 0f, 1f, 1f), spec.pageSpace)
        assertEquals(2f, spec.width.toFloat() / spec.height, 0.01f)
    }

    @Test fun memoryPolicyRejectsOutOfBudgetInputBeforeAnyExternalWork() {
        assertThrows(IllegalArgumentException::class.java) {
            OcrRasterPolicy(maxWorkingBytes = 19)
        }
        val policy = OcrRasterPolicy(maxWorkingBytes = 20)

        assertEquals(20L, policy.workingBytes(RenderSpec(1, 1)))
        assertThrows(IllegalArgumentException::class.java) {
            policy.requireWithinBudget(width = 2, height = 1)
        }
    }

    @Test fun productionSessionWiringCompletesThroughFol6ReporterAndNeutralDescriptor() {
        val completed = CountDownLatch(1)
        val descriptorCreated = AtomicBoolean()
        val index = preparedOcrIndex()
        val loader = TextPageLoader(
            FakePdfDocument(),
            pageCount = 1,
            deliver = { it() },
            index = index,
            indexKey = ::nativeKey,
            ocrKey = ::ocrKey
        )
        val observedLoader = object : SessionTextLoader by loader {
            override fun completeOcr(
                attempt: OcrAttempt,
                page: TextPage,
                callback: (OcrCommandResult<OcrTransition>) -> Unit
            ) = loader.completeOcr(attempt, page) {
                callback(it)
                completed.countDown()
            }
        }
        val descriptor = object : OcrEngineDescriptor {
            override fun textEngineVersion(request: OcrRequest) = TextEngineVersion("descriptor-v1")
            override fun create(environment: OcrEngineEnvironment): OcrEngine {
                descriptorCreated.set(true)
                return FakeOcrEngine()
            }
        }
        val environment = OcrEngineEnvironment(File("unused")) { ByteArrayInputStream(ByteArray(0)) }
        val pipeline = createSessionOcrPipeline(
            FakePdfDocument(),
            pageCount = 1,
            engineFactory = { descriptor.create(environment) },
            textLoader = observedLoader,
            priorityGate = DocumentPriorityGate(),
            policy = OcrRasterPolicy(24L * 1024 * 1024),
            onStopped = loader::close
        )

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(OcrPageState.COMPLETED, index.ocrStatus(ocrKey(0))?.state)
        assertEquals(TextSource.OCR, index.loadSelected(nativeKey(0), ocrKey(0))?.source)
        assertTrue(descriptorCreated.get())
        pipeline.dispose()
        loader.dispose()
        index.close()
    }

    @Test fun productionSessionDrainPersistsCancellationBeforeClosingLoader() {
        val recognitionStarted = CountDownLatch(1)
        val loaderClosed = CountDownLatch(1)
        val index = preparedOcrIndex()
        val loader = TextPageLoader(
            FakePdfDocument(),
            pageCount = 1,
            deliver = { it() },
            index = index,
            indexKey = ::nativeKey,
            ocrKey = ::ocrKey
        )
        val pipeline = createSessionOcrPipeline(
            FakePdfDocument(),
            pageCount = 1,
            engineFactory = {
                object : FakeOcrEngine() {
                    override fun recognize(
                        image: PageImage,
                        request: OcrRequest,
                        cancellationSignal: CancellationSignal
                    ): TextPage {
                        recognitionStarted.countDown()
                        while (!cancellationSignal.isCancelled()) Thread.yield()
                        throw OcrException(OcrFailure.Cancelled)
                    }
                }
            },
            textLoader = loader,
            priorityGate = DocumentPriorityGate(),
            policy = OcrRasterPolicy(24L * 1024 * 1024),
            onStopped = {
                loader.close()
                loaderClosed.countDown()
            }
        )
        assertTrue(recognitionStarted.await(2, TimeUnit.SECONDS))

        loader.beginOcrDrain()
        pipeline.close()
        pipeline.dispose()

        assertTrue(loaderClosed.await(2, TimeUnit.SECONDS))
        assertEquals(OcrPageState.CANCELLED, index.ocrStatus(ocrKey(0))?.state)
        assertEquals(null, index.load(ocrKey(0).textKey()))
        loader.dispose()
        index.close()
    }

    @Test fun searchPauseReopensAsNewGenerationAndPublishesCompletedOcr() {
        val firstRecognition = CountDownLatch(1)
        val pausePersisted = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val calls = AtomicInteger()
        val index = preparedOcrIndex()
        val loader = TextPageLoader(
            FakePdfDocument(), 1, deliver = { it() }, index = index,
            indexKey = ::nativeKey, ocrKey = ::ocrKey
        )
        val observedLoader = object : SessionTextLoader by loader {
            override fun cancelOcr(
                attempt: OcrAttempt,
                reason: OcrCancellationReason,
                callback: (OcrCommandResult<OcrTransition>) -> Unit
            ) = loader.cancelOcr(attempt, reason) {
                callback(it)
                pausePersisted.countDown()
            }

            override fun completeOcr(
                attempt: OcrAttempt,
                page: TextPage,
                callback: (OcrCommandResult<OcrTransition>) -> Unit
            ) = loader.completeOcr(attempt, page) {
                callback(it)
                completed.countDown()
            }
        }
        val pipeline = createSessionOcrPipeline(
            FakePdfDocument(), 1,
            engineFactory = {
                object : FakeOcrEngine() {
                    override fun recognize(
                        image: PageImage,
                        request: OcrRequest,
                        cancellationSignal: CancellationSignal
                    ): TextPage {
                        if (calls.getAndIncrement() == 0) {
                            firstRecognition.countDown()
                            while (!cancellationSignal.isCancelled()) Thread.yield()
                            throw OcrException(OcrFailure.Cancelled)
                        }
                        return ocrPage("resumed")
                    }
                }
            },
            textLoader = observedLoader,
            priorityGate = DocumentPriorityGate(),
            policy = OcrRasterPolicy(24L * 1024 * 1024),
            onStopped = loader::close
        )
        loader.search(TextSearchSpec("missing")) {}
        pipeline.resume()
        assertTrue(firstRecognition.await(2, TimeUnit.SECONDS))

        loader.closeSearch()
        pipeline.cancel(OcrCancellationReason.SEARCH_PAUSE)
        assertTrue(pausePersisted.await(2, TimeUnit.SECONDS))
        val paused = requireNotNull(index.ocrStatus(ocrKey(0)))
        assertEquals(OcrPageState.CANCELLED, paused.state)
        assertEquals(OcrCancellationReason.SEARCH_PAUSE, paused.cancellationReason)

        loader.search(TextSearchSpec("missing")) {}
        pipeline.resume()
        assertTrue(completed.await(2, TimeUnit.SECONDS))

        val final = requireNotNull(index.ocrStatus(ocrKey(0)))
        assertEquals(OcrPageState.COMPLETED, final.state)
        assertEquals(paused.generation + 1, final.generation)
        assertEquals("resumed", index.loadSelected(nativeKey(0), ocrKey(0))?.text)
        assertEquals(2, calls.get())
        pipeline.dispose()
        loader.dispose()
        index.close()
    }

    @Test fun explicitUserCancellationRemainsTerminalWhenSearchReopens() {
        val recognitionStarted = CountDownLatch(1)
        val userCancelPersisted = CountDownLatch(1)
        val reopenedClaim = CountDownLatch(1)
        val reopened = AtomicBoolean()
        val calls = AtomicInteger()
        val index = preparedOcrIndex()
        val loader = TextPageLoader(
            FakePdfDocument(), 1, deliver = { it() }, index = index,
            indexKey = ::nativeKey, ocrKey = ::ocrKey
        )
        val observedLoader = object : SessionTextLoader by loader {
            override fun cancelOcr(
                attempt: OcrAttempt,
                reason: OcrCancellationReason,
                callback: (OcrCommandResult<OcrTransition>) -> Unit
            ) = loader.cancelOcr(attempt, reason) {
                callback(it)
                userCancelPersisted.countDown()
            }

            override fun claimOcr(
                pageIndex: Int,
                callback: (OcrCommandResult<OcrTransition>) -> Unit
            ) = loader.claimOcr(pageIndex) {
                callback(it)
                if (reopened.get()) reopenedClaim.countDown()
            }
        }
        val pipeline = createSessionOcrPipeline(
            FakePdfDocument(), 1,
            engineFactory = {
                object : FakeOcrEngine() {
                    override fun recognize(
                        image: PageImage,
                        request: OcrRequest,
                        cancellationSignal: CancellationSignal
                    ): TextPage {
                        calls.incrementAndGet()
                        recognitionStarted.countDown()
                        while (!cancellationSignal.isCancelled()) Thread.yield()
                        throw OcrException(OcrFailure.Cancelled)
                    }
                }
            },
            textLoader = observedLoader,
            priorityGate = DocumentPriorityGate(),
            policy = OcrRasterPolicy(24L * 1024 * 1024),
            onStopped = loader::close
        )
        loader.search(TextSearchSpec("missing")) {}
        pipeline.resume()
        assertTrue(recognitionStarted.await(2, TimeUnit.SECONDS))

        pipeline.cancel(OcrCancellationReason.USER)
        assertTrue(userCancelPersisted.await(2, TimeUnit.SECONDS))
        reopened.set(true)
        loader.search(TextSearchSpec("missing")) {}
        pipeline.resume()
        assertTrue(reopenedClaim.await(2, TimeUnit.SECONDS))

        val status = requireNotNull(index.ocrStatus(ocrKey(0)))
        assertEquals(OcrPageState.CANCELLED, status.state)
        assertEquals(OcrCancellationReason.USER, status.cancellationReason)
        assertEquals(1, calls.get())
        pipeline.dispose()
        loader.dispose()
        index.close()
    }

    @Test fun rasterizerClosesDisplayListWhenRenderingFails() {
        val closed = AtomicBoolean()
        val document = FakePdfDocument(closed = closed, renderFailure = IllegalStateException("render"))
        val rasterizer = OcrPageRasterizer(document, OcrRasterPolicy(6L * 1024 * 1024))

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            rasterizer.rasterize(0, CancellationSignal { false })
        }

        assertTrue(closed.get())
    }

    @Test fun pendingQueueNeverExceedsItsExplicitBound() {
        val firstClaim = CountDownLatch(1)
        val releaseClaim = CountDownLatch(1)
        val reporter = RecordingReporter(emptySet(), beforeClaim = {
            if (firstClaim.count > 0) {
                firstClaim.countDown()
                releaseClaim.await(2, TimeUnit.SECONDS)
            }
        })
        val pipeline = pipeline(
            document = FakePdfDocument(pageCount = 100),
            reporter = reporter,
            engineFactory = { FakeOcrEngine() }
        )
        assertTrue(firstClaim.await(2, TimeUnit.SECONDS))

        repeat(100) { pipeline.enqueue(it) }

        assertTrue(pipeline.pendingCount() <= MAX_PENDING_OCR_PAGES)
        releaseClaim.countDown()
        pipeline.dispose()
    }

    private fun pipeline(
        document: PdfDocument,
        reporter: OcrClaimReporter,
        engineFactory: () -> OcrEngine,
        priorityGate: DocumentPriorityGate = DocumentPriorityGate()
    ) = OcrPagePipeline(
        document,
        document.pageCount,
        engineFactory,
        reporter,
        priorityGate,
        OcrRasterPolicy(maxWorkingBytes = 24L * 1024 * 1024)
    )
}

private open class FakeOcrEngine : OcrEngine {
    override fun textEngineVersion(request: OcrRequest) = TextEngineVersion("fake-ocr")
    override fun recognize(image: PageImage, request: OcrRequest, cancellationSignal: CancellationSignal) =
        ocrPage("recognized")
    override fun close() = Unit
}

private class RecordingReporter(
    private val eligiblePages: Set<Int>,
    private val completed: CountDownLatch = CountDownLatch(0),
    private val failed: CountDownLatch = CountDownLatch(0),
    private val cancelled: CountDownLatch = CountDownLatch(0),
    private val claimsFinished: CountDownLatch = CountDownLatch(0),
    private val completionOutcome: OcrTransitionOutcome = OcrTransitionOutcome.APPLIED,
    private val beforeClaim: () -> Unit = {}
) : OcrClaimReporter {
    val claimedPages = Collections.synchronizedList(mutableListOf<Int>())
    val completedPages = Collections.synchronizedList(mutableListOf<TextPage>())
    val completionOutcomes = Collections.synchronizedList(mutableListOf<OcrTransitionOutcome>())
    val failures = Collections.synchronizedList(mutableListOf<Triple<Int, String, Boolean>>())
    val cancelledPages = Collections.synchronizedList(mutableListOf<Int>())

    override fun resumePaused(pageIndex: Int) = OcrTransition(OcrTransitionOutcome.INVALID_STATE)

    override fun claim(pageIndex: Int): OcrTransition {
        beforeClaim()
        claimedPages += pageIndex
        claimsFinished.countDown()
        if (pageIndex !in eligiblePages) return OcrTransition(OcrTransitionOutcome.INVALID_STATE)
        return OcrTransition(
            OcrTransitionOutcome.APPLIED,
            OcrPageStatus(OcrPageState.RUNNING, 0),
            attempt(pageIndex)
        )
    }

    override fun complete(attempt: OcrAttempt, page: TextPage): OcrTransition {
        completedPages += page
        completionOutcomes += completionOutcome
        completed.countDown()
        return OcrTransition(completionOutcome)
    }

    override fun fail(attempt: OcrAttempt, kind: String, retryable: Boolean): OcrTransition {
        failures += Triple(attempt.key.pageIndex, kind, retryable)
        failed.countDown()
        return OcrTransition(OcrTransitionOutcome.APPLIED)
    }

    override fun cancel(attempt: OcrAttempt, reason: OcrCancellationReason): OcrTransition {
        cancelledPages += attempt.key.pageIndex
        cancelled.countDown()
        return OcrTransition(OcrTransitionOutcome.APPLIED)
    }
}

private class IndexReporter(private val index: TransientTextPageIndex) : OcrClaimReporter {
    override fun resumePaused(pageIndex: Int) = index.resumePausedOcr(ocrKey(pageIndex))
    override fun claim(pageIndex: Int) = index.claimOcr(ocrKey(pageIndex))
    override fun complete(attempt: OcrAttempt, page: TextPage) = index.completeOcr(attempt, page)
    override fun fail(attempt: OcrAttempt, kind: String, retryable: Boolean) =
        index.failOcr(attempt, kind, retryable)
    override fun cancel(attempt: OcrAttempt, reason: OcrCancellationReason) =
        index.cancelOcr(attempt, reason)
}

private class CancelFailureReporter(
    private val cancellationFallback: CountDownLatch,
    private val secondCompleted: CountDownLatch
) : OcrClaimReporter {
    private val claimed = mutableSetOf<Int>()
    val fallbackKinds = Collections.synchronizedList(mutableListOf<String>())
    val completedPages = Collections.synchronizedList(mutableListOf<Int>())

    override fun resumePaused(pageIndex: Int) = OcrTransition(OcrTransitionOutcome.INVALID_STATE)

    @Synchronized override fun claim(pageIndex: Int): OcrTransition {
        if (!claimed.add(pageIndex)) return OcrTransition(OcrTransitionOutcome.INVALID_STATE)
        return OcrTransition(
            OcrTransitionOutcome.APPLIED,
            OcrPageStatus(OcrPageState.RUNNING, 0),
            attempt(pageIndex)
        )
    }

    override fun complete(attempt: OcrAttempt, page: TextPage): OcrTransition {
        completedPages += attempt.key.pageIndex
        secondCompleted.countDown()
        return OcrTransition(OcrTransitionOutcome.APPLIED)
    }

    override fun fail(attempt: OcrAttempt, kind: String, retryable: Boolean): OcrTransition {
        fallbackKinds += kind
        cancellationFallback.countDown()
        return OcrTransition(OcrTransitionOutcome.APPLIED)
    }

    override fun cancel(attempt: OcrAttempt, reason: OcrCancellationReason): OcrTransition =
        throw OcrCommandExceptionForTest()
}

private class OcrCommandExceptionForTest : RuntimeException()

private class FakePdfDocument(
    override val pageCount: Int = 1,
    private val specs: MutableList<RenderSpec> = mutableListOf(),
    private val closed: AtomicBoolean = AtomicBoolean(),
    private val renderFailure: RuntimeException? = null
) : PdfDocument {
    override fun pageInfo(index: Int) = PageInfo(index, 900f, 1_200f, 0)

    override fun buildDisplayList(index: Int) = object : DisplayList {
        override fun render(spec: RenderSpec, cancellationSignal: CancellationSignal): Raster {
            specs += spec
            renderFailure?.let { throw it }
            return Raster(spec.width, spec.height, ByteArray(spec.width * spec.height * 4))
        }

        override fun close() {
            closed.set(true)
        }
    }

    override fun extractText(index: Int) = TextPage(emptyList(), TextSource.NATIVE_PDF)
    override fun outline(): List<OutlineEntry> = emptyList()
    override fun close() = Unit
}

private fun nativeKey(pageIndex: Int) = TextPageIndexKey(
    BookId("book"),
    DocumentContentVersion("ab".repeat(32)),
    pageIndex,
    TextSource.NATIVE_PDF,
    2,
    TextEngineVersion("native-v1")
)

private fun ocrKey(pageIndex: Int) = OcrPageKey(
        BookId("book"),
        DocumentContentVersion("ab".repeat(32)),
        pageIndex,
        2,
        TextEngineVersion("native-v1"),
        NATIVE_TEXT_USABILITY_POLICY_VERSION,
        TextEngineVersion("ocr-v1")
)

private fun attempt(pageIndex: Int) = OcrAttempt(ocrKey(pageIndex), 0)

private fun preparedOcrIndex() = TransientTextPageIndex().also { index ->
    val native = nativeKey(0)
    val ocr = ocrKey(0)
    index.prepareDocument(native.bookId, native.documentVersion)
    index.prepareSource(
        native.bookId,
        native.documentVersion,
        native.source,
        native.textSchemaVersion,
        native.engineVersion
    )
    index.prepareOcr(ocr)
    index.completeNativeAndReconcile(native, TextPage(emptyList(), TextSource.NATIVE_PDF), ocr)
}

private fun ocrPage(text: String) = TextPage(
    listOf(
        TextBlock(
            listOf(TextLine(listOf(TextWord(text, PageSpaceRect(0f, 0f, 1f, 1f), 0)), 0)),
            0
        )
    ),
    TextSource.OCR
)
