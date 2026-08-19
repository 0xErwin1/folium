package com.folium.reader.reader

import com.folium.reader.core.ocr.OcrEngine
import com.folium.reader.core.ocr.OcrCancellationReason
import com.folium.reader.core.ocr.OcrException
import com.folium.reader.core.ocr.OcrFailure
import com.folium.reader.core.ocr.OcrRequest
import com.folium.reader.core.ocr.PageImage
import com.folium.reader.core.ocr.PixelFormat
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.text.TextPage
import com.folium.reader.index.OcrAttempt
import com.folium.reader.index.OcrTransition
import com.folium.reader.index.OcrTransitionOutcome
import com.folium.reader.index.OcrPlanningBatch
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal const val MAX_PENDING_OCR_PAGES = 32
internal const val OCR_PLANNER_BATCH_SIZE = 8
internal const val OCR_PLANNER_LOOKAHEAD = 16
internal const val OCR_PLANNER_MAX_ATTEMPTS_PER_EVENT = 2
/**
 * Full-size buffers a page is simultaneously resident in while it is being recognized: the raster
 * the engine produced, which [PageImage] owns outright, and the bitmap the OCR adapter copies it
 * into. Nothing between them copies any more.
 */
private const val OCR_CONTROLLED_PIXEL_BUFFERS = 2L
private const val OCR_SAFETY_MARGIN_DIVISOR = 4L

/**
 * Tesseract wants roughly 30 pixels of cap height, which 10pt body text on A4 only reaches above
 * about 150 DPI. At 1800 the long edge of an A4 page lands there; below it, recognition quality on
 * ordinary book text degrades before the memory budget is anywhere near binding.
 */
private const val DEFAULT_OCR_LONG_EDGE = 1_800
private const val MIN_OCR_WORKING_BYTES = 6L * 1024 * 1024
private const val MAX_OCR_WORKING_BYTES = 24L * 1024 * 1024

internal data class OcrRasterPolicy(
    val maxWorkingBytes: Long,
    val preferredLongEdge: Int = DEFAULT_OCR_LONG_EDGE
) {
    init {
        require(maxWorkingBytes >= minimumWorkingBytes())
        require(preferredLongEdge > 0)
    }

    fun renderSpec(info: PageInfo): RenderSpec {
        require(info.width.isFinite() && info.height.isFinite())
        val pixelBudget = maxWorkingBytes / bufferEquivalentCount() / PixelFormat.RGBA_8888.bytesPerPixel
        val preferredScale = preferredLongEdge / maxOf(info.width, info.height)
        val preferredWidth = maxOf(1, (info.width * preferredScale).roundToInt())
        val preferredHeight = maxOf(1, (info.height * preferredScale).roundToInt())
        val preferredPixels = preferredWidth.toLong() * preferredHeight
        val budgetScale = if (preferredPixels <= pixelBudget) 1.0 else sqrt(pixelBudget.toDouble() / preferredPixels)
        val width = maxOf(1, (preferredWidth * budgetScale).toInt())
        val height = maxOf(1, (preferredHeight * budgetScale).toInt())

        return trimToBudget(width, height, pixelBudget)
    }

    /**
     * Scaling alone cannot always reach the budget: a page thin enough that one side floors at a
     * single pixel keeps the other side proportionally long, and the product can still overshoot.
     * Trimming the long side afterwards keeps [renderSpec] from ever handing the rasterizer a spec
     * that [requireWithinBudget] would reject.
     */
    private fun trimToBudget(width: Int, height: Int, pixelBudget: Long): RenderSpec {
        val fullPage = PageSpaceRect(0f, 0f, 1f, 1f)
        if (width.toLong() * height <= pixelBudget) return RenderSpec(width, height, fullPage)
        return if (width >= height) {
            RenderSpec(maxOf(1, (pixelBudget / height).toInt()), height, fullPage)
        } else {
            RenderSpec(width, maxOf(1, (pixelBudget / width).toInt()), fullPage)
        }
    }

    fun workingBytes(spec: RenderSpec): Long = workingBytes(spec.width, spec.height)

    fun requireWithinBudget(width: Int, height: Int) {
        require(workingBytes(width, height) <= maxWorkingBytes)
    }

    private fun workingBytes(width: Int, height: Int): Long {
        val pixels = Math.multiplyExact(width.toLong(), height.toLong())
        val pixelBytes = Math.multiplyExact(pixels, PixelFormat.RGBA_8888.bytesPerPixel.toLong())
        val controlledBytes = Math.multiplyExact(pixelBytes, OCR_CONTROLLED_PIXEL_BUFFERS)
        val safetyMargin = (controlledBytes + OCR_SAFETY_MARGIN_DIVISOR - 1) / OCR_SAFETY_MARGIN_DIVISOR
        return Math.addExact(controlledBytes, safetyMargin)
    }

    companion object {
        fun forHeap(maxHeapBytes: Long): OcrRasterPolicy = OcrRasterPolicy(
            (maxHeapBytes / 16).coerceIn(MIN_OCR_WORKING_BYTES, MAX_OCR_WORKING_BYTES)
        )

        fun minimumWorkingBytes(): Long =
            PixelFormat.RGBA_8888.bytesPerPixel * bufferEquivalentCount()

        /**
         * Rounds the safety margin up, matching how [workingBytes] charges for it. Rounding down
         * here would let [renderSpec] size a page against a cheaper estimate than the one
         * [requireWithinBudget] then applies, and the rasterizer would reject the policy's own
         * choice.
         */
        internal fun bufferEquivalentCount(): Long = OCR_CONTROLLED_PIXEL_BUFFERS +
            (OCR_CONTROLLED_PIXEL_BUFFERS + OCR_SAFETY_MARGIN_DIVISOR - 1) / OCR_SAFETY_MARGIN_DIVISOR
    }
}

internal interface OcrClaimReporter {
    fun plan(preferredPage: Int, afterPage: Int, beforePage: Int, limit: Int): OcrPlanningBatch
    fun resumePaused(pageIndex: Int): OcrTransition
    fun claim(pageIndex: Int): OcrTransition
    fun complete(attempt: OcrAttempt, page: TextPage): OcrTransition
    fun fail(attempt: OcrAttempt, kind: String, retryable: Boolean): OcrTransition
    fun cancel(attempt: OcrAttempt, reason: OcrCancellationReason): OcrTransition
}

data class SearchOcrPlanState(
    val generation: Long,
    val revision: Long,
    val searchActive: Boolean,
    val running: Boolean,
    val queued: Boolean,
    val plannable: Boolean,
    val paused: Boolean,
    val draining: Boolean = false
) {
    val canPause: Boolean get() = searchActive && (running || queued)
    val canResume: Boolean get() = !searchActive && !running && !queued && !draining && (plannable || paused)
}

internal class ReaderSessionOcrClaimReporter(
    private val plan: (Int, Int, Int, Int, (OcrCommandResult<OcrPlanningBatch>) -> Unit) -> Unit,
    private val resumePaused: (Int, (OcrCommandResult<OcrTransition>) -> Unit) -> Unit,
    private val claim: (Int, (OcrCommandResult<OcrTransition>) -> Unit) -> Unit,
    private val complete: (OcrAttempt, TextPage, (OcrCommandResult<OcrTransition>) -> Unit) -> Unit,
    private val fail: (OcrAttempt, String, Boolean, (OcrCommandResult<OcrTransition>) -> Unit) -> Unit,
    private val cancel: (OcrAttempt, OcrCancellationReason, (OcrCommandResult<OcrTransition>) -> Unit) -> Unit
) : OcrClaimReporter {
    override fun plan(preferredPage: Int, afterPage: Int, beforePage: Int, limit: Int): OcrPlanningBatch =
        awaitResult { callback -> plan(preferredPage, afterPage, beforePage, limit, callback) }

    override fun resumePaused(pageIndex: Int): OcrTransition =
        await { callback -> resumePaused(pageIndex, callback) }

    override fun claim(pageIndex: Int): OcrTransition = await { callback -> claim(pageIndex, callback) }

    override fun complete(attempt: OcrAttempt, page: TextPage): OcrTransition =
        await { callback -> complete(attempt, page, callback) }

    override fun fail(attempt: OcrAttempt, kind: String, retryable: Boolean): OcrTransition =
        await { callback -> fail(attempt, kind, retryable, callback) }

    override fun cancel(attempt: OcrAttempt, reason: OcrCancellationReason): OcrTransition =
        await { callback -> cancel(attempt, reason, callback) }

    private fun <T> awaitResult(submit: ((OcrCommandResult<T>) -> Unit) -> Unit): T {
        val completed = CountDownLatch(1)
        val result = AtomicReference<OcrCommandResult<T>>()
        submit {
            result.set(it)
            completed.countDown()
        }
        try {
            completed.await()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OcrPipelineStopped(cause = failure)
        }

        return when (val command = requireNotNull(result.get())) {
            is OcrCommandResult.Success -> command.value
            is OcrCommandResult.Failure -> throw OcrCommandException(command.error, command.cause)
        }
    }

    private fun await(submit: ((OcrCommandResult<OcrTransition>) -> Unit) -> Unit): OcrTransition =
        awaitResult(submit)
}

internal class OcrPageRasterizer(
    private val document: PdfDocument,
    private val policy: OcrRasterPolicy
) {
    fun rasterize(pageIndex: Int, cancellationSignal: CancellationSignal): PageImage {
        abortIfCancelled(cancellationSignal)
        val spec = policy.renderSpec(document.pageInfo(pageIndex))
        abortIfCancelled(cancellationSignal)
        policy.requireWithinBudget(spec.width, spec.height)
        val displayList = document.buildDisplayList(pageIndex)
        return try {
            abortIfCancelled(cancellationSignal)
            val raster = displayList.render(spec, cancellationSignal)
            check(raster.width == spec.width && raster.height == spec.height)
            policy.requireWithinBudget(raster.width, raster.height)
            PageImage(raster.width, raster.height, PixelFormat.RGBA_8888, raster.rgba)
        } finally {
            displayList.close()
        }
    }

    private fun abortIfCancelled(cancellationSignal: CancellationSignal) {
        if (cancellationSignal.isCancelled()) throw PdfException(PdfFailure.Resource(retryable = true))
    }
}

internal class OcrPagePipeline(
    document: PdfDocument,
    private val pageCount: Int,
    private val engineFactory: () -> OcrEngine,
    private val reporter: OcrClaimReporter,
    private val priorityGate: DocumentPriorityGate,
    policy: OcrRasterPolicy,
    private val onStopped: () -> Unit = {},
    private val onPlanAttemptsExhausted: () -> Unit = {},
    private val onSearchStateChanged: (SearchOcrPlanState) -> Unit = {},
    threadFactory: (Runnable) -> Thread = { runnable ->
        Thread(runnable, "reader-ocr").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
) {
    private enum class WorkOrigin { SEARCH, EXPLICIT }
    private data class WorkItem(val pageIndex: Int, val origin: WorkOrigin, val searchGeneration: Long)
    private data class PlanRequest(
        val preferredPage: Int,
        val afterPage: Int,
        val beforePage: Int,
        val searchGeneration: Long,
        val refreshOnly: Boolean = false
    )

    private val rasterizer = OcrPageRasterizer(document, policy)
    private val lock = Object()
    private val pending = ArrayDeque<WorkItem>()

    /** The page indexes in [pending]. Kept in step with it so admission can dedupe without a scan. */
    private val pendingSet = mutableSetOf<Int>()
    private val worker = threadFactory(Runnable(::workLoop))
    private var searchActive = false
    private var searchGeneration = 0L
    private var preferredPage = 0
    private var documentCursor = -1
    private var documentBoundary = 0
    private var documentWrapped = false
    private var documentExhausted = false
    private var planRequested = false
    private var searchStateRefreshRequested = false
    private var consecutivePlanFailures = 0
    private var activeWork: WorkItem? = null
    private var cancellationEpoch = 0L
    private var cancellationReason = OcrCancellationReason.USER
    private var closing = false
    private var stopped = false
    private var searchStateRevision = 0L
    private var plannableSearchWork = false
    private var pausedSearchWork = false
    private var drainingSearchWork = false

    init {
        require(pageCount > 0)
        worker.start()
    }

    fun enqueue(pageIndex: Int) {
        if (pageIndex !in 0 until pageCount) return
        synchronized(lock) {
            if (closing || stopped || !searchActive) return
            admitLocked(WorkItem(pageIndex, WorkOrigin.SEARCH, searchGeneration), pageIndex == preferredPage)
            publishSearchStateLocked()
            lock.notifyAll()
        }
    }

    fun openSearch(visiblePage: Int) = synchronized(lock) {
        if (visiblePage !in 0 until pageCount || closing || stopped) return
        searchGeneration++
        searchActive = true
        preferredPage = visiblePage
        documentCursor = visiblePage
        documentBoundary = visiblePage
        documentWrapped = false
        documentExhausted = false
        consecutivePlanFailures = 0
        removeSearchWorkLocked()
        planRequested = true
        searchStateRefreshRequested = false
        plannableSearchWork = false
        pausedSearchWork = false
        drainingSearchWork = false
        publishSearchStateLocked()
        lock.notifyAll()
    }

    fun updateSearchDemand(visiblePage: Int) = synchronized(lock) {
        if (visiblePage !in 0 until pageCount || closing || stopped || !searchActive) return
        if (visiblePage == preferredPage) return
        preferredPage = visiblePage
        removeSearchWorkLocked()
        consecutivePlanFailures = 0
        planRequested = true
        publishSearchStateLocked()
        lock.notifyAll()
    }

    fun enqueueExplicit(pageIndex: Int) = synchronized(lock) {
        if (pageIndex !in 0 until pageCount || closing || stopped) return
        admitLocked(WorkItem(pageIndex, WorkOrigin.EXPLICIT, searchGeneration), first = true)
        lock.notifyAll()
    }

    fun resume() = openSearch(preferredPage)

    fun closeSearch() = synchronized(lock) {
        if (closing || stopped) return
        searchActive = false
        searchGeneration++
        planRequested = false
        removeSearchWorkLocked()
        plannableSearchWork = false
        pausedSearchWork = false
        drainingSearchWork = true
        searchStateRefreshRequested = true
        if (activeWork?.origin == WorkOrigin.SEARCH) {
            cancellationEpoch++
            cancellationReason = OcrCancellationReason.SEARCH_PAUSE
        }
        publishSearchStateLocked()
        lock.notifyAll()
    }

    fun cancel(reason: OcrCancellationReason = OcrCancellationReason.USER) = synchronized(lock) {
        if (closing || stopped) return
        if (reason == OcrCancellationReason.SEARCH_PAUSE) {
            closeSearch()
            return
        }
        cancellationEpoch++
        cancellationReason = reason
        searchActive = false
        searchGeneration++
        planRequested = false
        searchStateRefreshRequested = false
        pending.clear()
        pendingSet.clear()
        lock.notifyAll()
    }

    fun close() = synchronized(lock) {
        if (closing || stopped) return
        cancellationEpoch++
        cancellationReason = OcrCancellationReason.SESSION
        closing = true
        searchActive = false
        planRequested = false
        searchStateRefreshRequested = false
        pending.clear()
        pendingSet.clear()
        lock.notifyAll()
    }

    fun dispose() {
        close()
        var interrupted = false
        while (worker.isAlive) {
            try {
                worker.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    internal fun pendingCount(): Int = synchronized(lock) { pending.size }
    internal fun pendingPages(): List<Int> = synchronized(lock) { pending.map(WorkItem::pageIndex) }
    internal fun searchPlanningActive(): Boolean = synchronized(lock) { searchActive }
    internal fun searchState(): SearchOcrPlanState = synchronized(lock) { searchStateLocked() }

    private fun workLoop() {
        var engine: OcrEngine? = null
        try {
            while (true) {
                val work = nextWork() ?: return
                val pageIndex = work.pageIndex
                try {
                    val resumed = reporter.resumePaused(pageIndex)
                    if (resumed.outcome == OcrTransitionOutcome.APPLIED) {
                        synchronized(lock) {
                            pausedSearchWork = false
                            publishSearchStateLocked()
                        }
                    }
                } catch (_: Throwable) {
                    if (isStopped()) return
                    finishWork(work)
                    continue
                }
                if (isWorkCancelled(work)) {
                    finishWork(work)
                    continue
                }
                val transition = try {
                    reporter.claim(pageIndex)
                } catch (_: Throwable) {
                    if (isStopped()) return
                    finishWork(work)
                    continue
                }
                val attempt = transition.attempt
                if (transition.outcome != OcrTransitionOutcome.APPLIED || attempt == null) {
                    finishWork(work)
                    continue
                }
                if (isWorkCancelled(work)) {
                    reportCancellation(attempt, currentCancellationReason())
                    finishWork(work)
                    continue
                }

                try {
                    val activeEngine = engine ?: engineFactory().also { engine = it }
                    val page = recognize(work, activeEngine)
                    reporter.complete(attempt, page)
                } catch (cancelled: OcrPipelineStopped) {
                    reportCancellation(attempt, cancelled.reason ?: currentCancellationReason())
                } catch (failure: Throwable) {
                    val typed = failure.toPipelineFailure()
                    runCatching { reporter.fail(attempt, typed.kind, typed.retryable) }
                } finally {
                    finishWork(work)
                }
            }
        } finally {
            runCatching { engine?.close() }
            synchronized(lock) {
                stopped = true
                lock.notifyAll()
            }
            runCatching(onStopped)
        }
    }

    private fun reportCancellation(attempt: OcrAttempt, reason: OcrCancellationReason) {
        try {
            val transition = reporter.cancel(attempt, reason)
            if (reason == OcrCancellationReason.SEARCH_PAUSE &&
                transition.status?.cancellationReason == OcrCancellationReason.SEARCH_PAUSE) {
                synchronized(lock) {
                    pausedSearchWork = true
                    publishSearchStateLocked()
                }
            }
        } catch (_: Throwable) {
            runCatching {
                reporter.fail(attempt, "cancel-report", retryable = true)
            }
        }
    }

    private fun nextWork(): WorkItem? {
        while (true) {
            val plan = synchronized(lock) {
                while (!closing && !stopped && pending.isEmpty() && !planRequested &&
                    !searchStateRefreshRequested) {
                    try { lock.wait() } catch (_: InterruptedException) { if (stopped) return null }
                }
                if (closing || stopped) return null
                if (searchStateRefreshRequested) {
                    searchStateRefreshRequested = false
                    PlanRequest(preferredPage, -1, pageCount, searchGeneration, refreshOnly = true)
                } else {
                    pending.pollFirst()?.also {
                        pendingSet.remove(it.pageIndex)
                        activeWork = it
                        publishSearchStateLocked()
                        return it
                    }
                    planRequested = false
                    PlanRequest(
                        preferredPage,
                        documentCursor,
                        if (documentWrapped) documentBoundary else pageCount,
                        searchGeneration
                    )
                }
            }
            val batch = try {
                reporter.plan(plan.preferredPage, plan.afterPage, plan.beforePage, OCR_PLANNER_LOOKAHEAD)
            } catch (_: Throwable) {
                if (isStopped()) return null
                val exhausted = synchronized(lock) {
                    if (plan.refreshOnly && !searchActive && plan.searchGeneration == searchGeneration) {
                        drainingSearchWork = false
                        publishSearchStateLocked()
                        false
                    } else if (searchActive && plan.searchGeneration == searchGeneration) {
                        consecutivePlanFailures++
                        planRequested = consecutivePlanFailures < OCR_PLANNER_MAX_ATTEMPTS_PER_EVENT
                        !planRequested
                    } else {
                        false
                    }
                }
                if (exhausted) onPlanAttemptsExhausted()
                continue
            }
            val stale = synchronized(lock) {
                if (plan.refreshOnly) {
                    if (searchActive || plan.searchGeneration != searchGeneration) {
                        true
                    } else {
                        plannableSearchWork = batch.queuedAvailable
                        pausedSearchWork = batch.pausedAvailable
                        drainingSearchWork = false
                        publishSearchStateLocked()
                        false
                    }
                } else if (!searchActive || plan.searchGeneration != searchGeneration) {
                    true
                } else {
                    pausedSearchWork = batch.pausedAvailable
                    val candidates = batch.pageIndexes.take(OCR_PLANNER_BATCH_SIZE)
                    val admitted = candidates.filter { page ->
                        admitLocked(WorkItem(page, WorkOrigin.SEARCH, searchGeneration), page == preferredPage)
                    }
                    consecutivePlanFailures = 0
                    documentCursor = admitted.lastOrNull { page ->
                        page != plan.preferredPage && page > plan.afterPage && page < plan.beforePage
                    } ?: plan.afterPage
                    val entireBatchAdmitted = admitted.size == batch.pageIndexes.size
                    plannableSearchWork = batch.queuedAvailable && admitted.isEmpty() ||
                        !batch.rangeExhausted && admitted.isEmpty()
                    if (batch.rangeExhausted && entireBatchAdmitted) {
                        if (!documentWrapped && documentBoundary > 0) {
                            documentWrapped = true
                            documentCursor = -1
                            planRequested = true
                        } else {
                            documentExhausted = true
                        }
                    }
                    publishSearchStateLocked()
                    false
                }
            }
            if (stale) continue
        }
    }

    private fun recognize(work: WorkItem, engine: OcrEngine): TextPage {
        val cancellationToken = synchronized(lock) { cancellationEpoch }
        while (true) {
            val permit = priorityGate.awaitOcrPermit {
                isAttemptCancelled(work, cancellationToken)
            } ?: throw OcrPipelineStopped(currentCancellationReason())
            val signal = CancellationSignal {
                isAttemptCancelled(work, cancellationToken) || priorityGate.isPreempted(permit)
            }
            try {
                rasterizer.rasterize(work.pageIndex, signal).use { image ->
                    val page = engine.recognize(image, OcrRequest.DEFAULT, signal)
                    if (signal.isCancelled()) throw OcrPipelineStopped()
                    return page
                }
            } catch (failure: Throwable) {
                if (isAttemptCancelled(work, cancellationToken)) {
                    throw OcrPipelineStopped(currentCancellationReason(), failure)
                }
                if (priorityGate.isPreempted(permit) && failure.isPreemptible()) continue
                throw failure
            }
        }
    }

    private fun admitLocked(work: WorkItem, first: Boolean): Boolean {
        if (activeWork?.pageIndex == work.pageIndex) return true
        if (work.pageIndex in pendingSet) {
            val existing = pending.first { it.pageIndex == work.pageIndex }
            if (work.origin == WorkOrigin.EXPLICIT && existing.origin == WorkOrigin.SEARCH) {
                pending.remove(existing)
                pending.addFirst(work)
            }
            return true
        }
        pendingSet.add(work.pageIndex)
        if (pending.size >= MAX_PENDING_OCR_PAGES) {
            if (first) {
                val speculative = pending.lastOrNull { it.origin == WorkOrigin.SEARCH }
                if (speculative != null) {
                    pending.remove(speculative)
                    pendingSet.remove(speculative.pageIndex)
                    pending.addFirst(work)
                    return true
                }
            }
            pendingSet.remove(work.pageIndex)
            if (work.origin == WorkOrigin.SEARCH) {
                documentExhausted = false
                consecutivePlanFailures = 0
                planRequested = true
            }
            return false
        }
        if (first) pending.addFirst(work) else pending.addLast(work)
        return true
    }

    private fun removeSearchWorkLocked() {
        pending.removeAll { work ->
            (work.origin == WorkOrigin.SEARCH).also { remove -> if (remove) pendingSet.remove(work.pageIndex) }
        }
    }

    private fun finishWork(work: WorkItem) = synchronized(lock) {
        if (activeWork == work) activeWork = null
        if (work.origin == WorkOrigin.SEARCH && searchActive &&
            work.searchGeneration == searchGeneration && !documentExhausted) {
            consecutivePlanFailures = 0
            planRequested = true
        }
        publishSearchStateLocked()
        lock.notifyAll()
    }

    private fun publishSearchStateLocked() {
        searchStateRevision++
        onSearchStateChanged(searchStateLocked())
    }

    private fun searchStateLocked() = SearchOcrPlanState(
        generation = searchGeneration,
        revision = searchStateRevision,
        searchActive = searchActive,
        running = activeWork?.origin == WorkOrigin.SEARCH,
        queued = pending.any { it.origin == WorkOrigin.SEARCH },
        plannable = plannableSearchWork,
        paused = pausedSearchWork,
        draining = drainingSearchWork
    )

    private fun isWorkCancelled(work: WorkItem): Boolean = synchronized(lock) {
        closing || stopped || work.origin == WorkOrigin.SEARCH &&
            (!searchActive || work.searchGeneration != searchGeneration)
    }

    private fun isAttemptCancelled(work: WorkItem, token: Long): Boolean = synchronized(lock) {
        closing || stopped || cancellationEpoch != token || work.origin == WorkOrigin.SEARCH &&
            (!searchActive || work.searchGeneration != searchGeneration)
    }

    private fun currentCancellationReason(): OcrCancellationReason = synchronized(lock) {
        cancellationReason
    }

    private fun isStopped(): Boolean = synchronized(lock) { closing || stopped }
}

private data class PipelineFailure(val kind: String, val retryable: Boolean)
private class OcrPipelineStopped(
    val reason: OcrCancellationReason? = null,
    cause: Throwable? = null
) : RuntimeException(cause)
private class OcrCommandException(val error: OcrCommandError, cause: Throwable?) : RuntimeException(error.name, cause)

private fun Throwable.isPreemptible(): Boolean =
    this is OcrPipelineStopped ||
        this is OcrException && failure == OcrFailure.Cancelled ||
        this is PdfException && failure is PdfFailure.Resource

private fun Throwable.toPipelineFailure(): PipelineFailure = when (this) {
    is OcrException -> when (val typed = failure) {
        OcrFailure.Initialization -> PipelineFailure("ocr-initialization", false)
        OcrFailure.LanguageData -> PipelineFailure("ocr-language-data", false)
        OcrFailure.Recognition -> PipelineFailure("ocr-recognition", true)
        OcrFailure.Cancelled -> PipelineFailure("ocr-cancelled", true)
        OcrFailure.Closed -> PipelineFailure("ocr-closed", false)
        is OcrFailure.Resource -> PipelineFailure("ocr-resource", typed.retryable)
    }
    is PdfException -> when (val typed = failure) {
        is PdfFailure.Resource -> PipelineFailure("raster-resource", typed.retryable)
        PdfFailure.Closed -> PipelineFailure("raster-closed", false)
        else -> PipelineFailure("raster-page", true)
    }
    is OcrCommandException -> PipelineFailure("persistence-${error.name.lowercase()}", true)
    is OutOfMemoryError -> PipelineFailure("ocr-memory", true)
    else -> PipelineFailure("ocr-pipeline", true)
}
