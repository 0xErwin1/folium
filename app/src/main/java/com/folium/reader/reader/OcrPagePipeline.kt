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
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal const val MAX_PENDING_OCR_PAGES = 32
private const val OCR_CONTROLLED_PIXEL_BUFFERS = 4L
private const val OCR_SAFETY_MARGIN_DIVISOR = 4L
private const val DEFAULT_OCR_LONG_EDGE = 1_200
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

        return RenderSpec(width, height, PageSpaceRect(0f, 0f, 1f, 1f))
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

    private fun minimumWorkingBytes(): Long =
        PixelFormat.RGBA_8888.bytesPerPixel * bufferEquivalentCount()

    private fun bufferEquivalentCount(): Long =
        OCR_CONTROLLED_PIXEL_BUFFERS + OCR_CONTROLLED_PIXEL_BUFFERS / OCR_SAFETY_MARGIN_DIVISOR

    companion object {
        fun forHeap(maxHeapBytes: Long): OcrRasterPolicy = OcrRasterPolicy(
            (maxHeapBytes / 16).coerceIn(MIN_OCR_WORKING_BYTES, MAX_OCR_WORKING_BYTES)
        )
    }
}

internal interface OcrClaimReporter {
    fun resumePaused(pageIndex: Int): OcrTransition
    fun claim(pageIndex: Int): OcrTransition
    fun complete(attempt: OcrAttempt, page: TextPage): OcrTransition
    fun fail(attempt: OcrAttempt, kind: String, retryable: Boolean): OcrTransition
    fun cancel(attempt: OcrAttempt, reason: OcrCancellationReason): OcrTransition
}

internal class ReaderSessionOcrClaimReporter(
    private val resumePaused: (Int, (OcrCommandResult<OcrTransition>) -> Unit) -> Unit,
    private val claim: (Int, (OcrCommandResult<OcrTransition>) -> Unit) -> Unit,
    private val complete: (OcrAttempt, TextPage, (OcrCommandResult<OcrTransition>) -> Unit) -> Unit,
    private val fail: (OcrAttempt, String, Boolean, (OcrCommandResult<OcrTransition>) -> Unit) -> Unit,
    private val cancel: (OcrAttempt, OcrCancellationReason, (OcrCommandResult<OcrTransition>) -> Unit) -> Unit
) : OcrClaimReporter {
    override fun resumePaused(pageIndex: Int): OcrTransition =
        await { callback -> resumePaused(pageIndex, callback) }

    override fun claim(pageIndex: Int): OcrTransition = await { callback -> claim(pageIndex, callback) }

    override fun complete(attempt: OcrAttempt, page: TextPage): OcrTransition =
        await { callback -> complete(attempt, page, callback) }

    override fun fail(attempt: OcrAttempt, kind: String, retryable: Boolean): OcrTransition =
        await { callback -> fail(attempt, kind, retryable, callback) }

    override fun cancel(attempt: OcrAttempt, reason: OcrCancellationReason): OcrTransition =
        await { callback -> cancel(attempt, reason, callback) }

    private fun await(submit: ((OcrCommandResult<OcrTransition>) -> Unit) -> Unit): OcrTransition {
        val completed = CountDownLatch(1)
        val result = AtomicReference<OcrCommandResult<OcrTransition>>()
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
    threadFactory: (Runnable) -> Thread = { runnable ->
        Thread(runnable, "reader-ocr").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
) {
    private val rasterizer = OcrPageRasterizer(document, policy)
    private val lock = Object()
    private val pending = ArrayDeque<Int>()
    private val pendingSet = mutableSetOf<Int>()
    private val worker = threadFactory(Runnable(::workLoop))
    private var scanCursor = 0
    private var scanRequested = true
    private var paused = false
    private var cancellationEpoch = 0L
    private var cancellationReason = OcrCancellationReason.USER
    private var closing = false
    private var stopped = false

    init {
        require(pageCount > 0)
        worker.start()
    }

    fun enqueue(pageIndex: Int) {
        if (pageIndex !in 0 until pageCount) return
        synchronized(lock) {
            if (closing || stopped) return
            if (pendingSet.add(pageIndex)) {
                if (pending.size < MAX_PENDING_OCR_PAGES) pending.addLast(pageIndex)
                else {
                    pendingSet.remove(pageIndex)
                    requestScanLocked()
                }
            }
            lock.notifyAll()
        }
    }

    fun resume() = synchronized(lock) {
        if (closing || stopped) return
        paused = false
        requestScanLocked()
        lock.notifyAll()
    }

    fun cancel(reason: OcrCancellationReason = OcrCancellationReason.USER) = synchronized(lock) {
        if (closing || stopped) return
        cancellationEpoch++
        cancellationReason = reason
        paused = true
        pending.clear()
        pendingSet.clear()
        lock.notifyAll()
    }

    fun close() = synchronized(lock) {
        if (closing || stopped) return
        cancellationEpoch++
        cancellationReason = OcrCancellationReason.SESSION
        closing = true
        paused = false
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

    private fun workLoop() {
        var engine: OcrEngine? = null
        try {
            while (true) {
                val pageIndex = nextPage() ?: return
                try {
                    reporter.resumePaused(pageIndex)
                } catch (_: Throwable) {
                    if (isStopped()) return
                    continue
                }
                val transition = try {
                    reporter.claim(pageIndex)
                } catch (_: Throwable) {
                    if (isStopped()) return
                    continue
                }
                val attempt = transition.attempt
                if (transition.outcome != OcrTransitionOutcome.APPLIED || attempt == null) continue

                try {
                    val activeEngine = engine ?: engineFactory().also { engine = it }
                    val page = recognize(pageIndex, activeEngine)
                    reporter.complete(attempt, page)
                } catch (cancelled: OcrPipelineStopped) {
                    reportCancellation(attempt, cancelled.reason ?: currentCancellationReason())
                } catch (failure: Throwable) {
                    val typed = failure.toPipelineFailure()
                    runCatching { reporter.fail(attempt, typed.kind, typed.retryable) }
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
            reporter.cancel(attempt, reason)
        } catch (_: Throwable) {
            runCatching {
                reporter.fail(attempt, "cancel-report", retryable = true)
            }
        }
    }

    private fun nextPage(): Int? {
        synchronized(lock) {
            while (true) {
                while (!closing && !stopped && (paused || (pending.isEmpty() && !scanRequested))) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        if (stopped) return null
                    }
                }
                if (closing || stopped) return null

                if (pending.isNotEmpty()) {
                    return pending.removeFirst().also(pendingSet::remove)
                }
                if (scanCursor < pageCount) return scanCursor++

                scanRequested = false
            }
        }
    }

    private fun recognize(pageIndex: Int, engine: OcrEngine): TextPage {
        val cancellationToken = synchronized(lock) { cancellationEpoch }
        while (true) {
            val permit = priorityGate.awaitOcrPermit {
                isAttemptCancelled(cancellationToken)
            } ?: throw OcrPipelineStopped(currentCancellationReason())
            val signal = CancellationSignal {
                isAttemptCancelled(cancellationToken) || priorityGate.isPreempted(permit)
            }
            try {
                rasterizer.rasterize(pageIndex, signal).use { image ->
                    val page = engine.recognize(image, OcrRequest.DEFAULT, signal)
                    if (signal.isCancelled()) throw OcrPipelineStopped()
                    return page
                }
            } catch (failure: Throwable) {
                if (isAttemptCancelled(cancellationToken)) {
                    throw OcrPipelineStopped(currentCancellationReason(), failure)
                }
                if (priorityGate.isPreempted(permit) && failure.isPreemptible()) continue
                throw failure
            }
        }
    }

    private fun requestScanLocked() {
        scanCursor = 0
        scanRequested = true
    }

    private fun isAttemptCancelled(token: Long): Boolean = synchronized(lock) {
        closing || stopped || cancellationEpoch != token
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
