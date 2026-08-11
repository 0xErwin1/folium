package com.folium.reader.reader

import com.folium.reader.core.library.BookId
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextSource
import com.folium.reader.index.DocumentContentVersion
import com.folium.reader.index.TEXT_PAGE_SCHEMA_VERSION
import com.folium.reader.index.TextPageIndex
import com.folium.reader.index.TextPageIndexKey
import com.folium.reader.index.TextPageIndexWriteOutcome
import com.folium.reader.index.TransientTextPageIndex
import java.util.concurrent.atomic.AtomicBoolean
import com.folium.reader.core.ocr.OcrPageStatus
import com.folium.reader.core.ocr.OcrCancellationReason
import com.folium.reader.core.text.TextPage
import com.folium.reader.index.OcrAttempt
import com.folium.reader.index.OcrTransition
import com.folium.reader.core.text.TextSearchSpec

internal interface SessionTextLoader {
    fun load(pageIndex: Int, callback: (TextPageLoadResult) -> Unit)
    fun search(query: String, callback: (TextSearchProgress) -> Unit) =
        search(TextSearchSpec(query), callback)
    fun search(spec: TextSearchSpec, callback: (TextSearchProgress) -> Unit) = Unit
    fun closeSearch() = Unit
    fun ocrStatus(pageIndex: Int, callback: (OcrCommandResult<OcrPageStatus?>) -> Unit) =
        callback(OcrCommandResult.Failure(OcrCommandError.NOT_CONFIGURED))
    fun claimOcr(pageIndex: Int, callback: (OcrCommandResult<OcrTransition>) -> Unit) =
        callback(OcrCommandResult.Failure(OcrCommandError.NOT_CONFIGURED))
    fun completeOcr(attempt: OcrAttempt, page: TextPage, callback: (OcrCommandResult<OcrTransition>) -> Unit) =
        callback(OcrCommandResult.Failure(OcrCommandError.NOT_CONFIGURED))
    fun failOcr(attempt: OcrAttempt, failureKind: String, retryable: Boolean,
                callback: (OcrCommandResult<OcrTransition>) -> Unit) =
        callback(OcrCommandResult.Failure(OcrCommandError.NOT_CONFIGURED))
    fun cancelOcr(
        attempt: OcrAttempt,
        reason: OcrCancellationReason = OcrCancellationReason.USER,
        callback: (OcrCommandResult<OcrTransition>) -> Unit
    ) =
        callback(OcrCommandResult.Failure(OcrCommandError.NOT_CONFIGURED))
    fun resumePausedOcr(pageIndex: Int, callback: (OcrCommandResult<OcrTransition>) -> Unit) =
        callback(OcrCommandResult.Failure(OcrCommandError.NOT_CONFIGURED))
    fun retryOcr(pageIndex: Int, callback: (OcrCommandResult<OcrTransition>) -> Unit) =
        callback(OcrCommandResult.Failure(OcrCommandError.NOT_CONFIGURED))
    fun beginOcrDrain() = close()
    fun close()
    fun dispose()
}

/**
 * Owns the two teardown phases independently so each phase is attempted at most once. A failing
 * cleanup never prevents later cleanups in that phase. Failures are aggregated for diagnostics but
 * never escape Android main/executor lifecycle boundaries.
 */
internal class ReaderSessionLifecycle(
    private val unregisterCallbacks: () -> Unit,
    private val closeTextLoader: () -> Unit,
    private val closePresenter: () -> Unit,
    private val shutdownPresenter: () -> Unit,
    private val disposeTextLoader: () -> Unit,
    private val closeTextIndex: () -> Unit,
    private val clearPageCache: () -> Unit,
    private val closeDocument: () -> Unit
) {
    private val closeStarted = AtomicBoolean()
    private val disposeStarted = AtomicBoolean()

    @Volatile internal var closeFailure: Throwable? = null
        private set
    @Volatile internal var disposeFailure: Throwable? = null
        private set

    fun close() {
        if (!closeStarted.compareAndSet(false, true)) return
        closeFailure = collectCleanupFailures(unregisterCallbacks, closeTextLoader, closePresenter)
    }

    fun dispose() {
        if (!disposeStarted.compareAndSet(false, true)) return
        disposeFailure = collectCleanupFailures(
            shutdownPresenter, disposeTextLoader, closeTextIndex, clearPageCache, closeDocument
        )
    }
}

internal fun closeThenScheduleDispose(close: () -> Unit, scheduleDispose: () -> Unit): Throwable? =
    collectCleanupFailures(close, scheduleDispose)

private fun collectCleanupFailures(vararg stages: () -> Unit): Throwable? {
    var firstFailure: Throwable? = null
    stages.forEach { stage ->
        try {
            stage()
        } catch (failure: Throwable) {
            val first = firstFailure
            if (first == null) firstFailure = failure
            else if (failure !== first) first.addSuppressed(failure)
        }
    }
    return firstFailure
}

internal class SessionConstructionScope {
    private val cleanups = mutableListOf<() -> Unit>()

    fun <T> acquire(factory: () -> T, cleanup: (T) -> Unit): T = factory().also { value ->
        cleanups += { cleanup(value) }
    }

    fun onCleanup(cleanup: () -> Unit) {
        cleanups += cleanup
    }

    fun <T> construct(block: SessionConstructionScope.() -> T): T = try {
        block().also { cleanups.clear() }
    } catch (failure: Throwable) {
        cleanupAfter(failure)
        throw failure
    }

    private fun cleanupAfter(failure: Throwable) {
        cleanups.asReversed().forEach { cleanup ->
            try {
                cleanup()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
        }
        cleanups.clear()
    }
}

internal data class TextSessionRequest(
    val bookId: BookId,
    val documentVersion: DocumentContentVersion,
    val pageCount: Int,
    val source: TextSource,
    val engineVersion: TextEngineVersion,
    val textSchemaVersion: Int = TEXT_PAGE_SCHEMA_VERSION
) {
    init { require(pageCount > 0) }
}

internal data class TextSessionResources(
    val index: TextPageIndex,
    val loader: SessionTextLoader
)

internal fun interface SessionTextLoaderFactory {
    fun create(index: TextPageIndex, keyFactory: (Int) -> TextPageIndexKey): SessionTextLoader
}

internal fun acquireTextSessionResources(
    scope: SessionConstructionScope,
    request: TextSessionRequest,
    openIndex: () -> TextPageIndex,
    openFallbackIndex: (Throwable) -> TextPageIndex = { TransientTextPageIndex(it) },
    createLoader: SessionTextLoaderFactory
): TextSessionResources {
    val index = scope.acquire(
        factory = { openPreparedIndex(request, openIndex, openFallbackIndex) },
        cleanup = TextPageIndex::close
    )

    val keyFactory: (Int) -> TextPageIndexKey = { pageIndex ->
        TextPageIndexKey(
            request.bookId,
            request.documentVersion,
            pageIndex,
            request.source,
            request.textSchemaVersion,
            request.engineVersion
        )
    }
    val loader = scope.acquire(
        factory = { createLoader.create(index, keyFactory) },
        cleanup = SessionTextLoader::dispose
    )
    return TextSessionResources(index, loader)
}

private fun openPreparedIndex(
    request: TextSessionRequest,
    openIndex: () -> TextPageIndex,
    openFallbackIndex: (Throwable) -> TextPageIndex
): TextPageIndex {
    val persistent = try {
        openIndex()
    } catch (failure: Throwable) {
        return prepareFallback(request, failure, openFallbackIndex)
    }
    try {
        prepareIndex(persistent, request)
        return persistent
    } catch (failure: Throwable) {
        try {
            persistent.close()
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        return prepareFallback(request, failure, openFallbackIndex)
    }
}

private fun prepareFallback(
    request: TextSessionRequest,
    failure: Throwable,
    openFallbackIndex: (Throwable) -> TextPageIndex
): TextPageIndex {
    val fallback = try {
        openFallbackIndex(failure)
    } catch (fallbackOpenFailure: Throwable) {
        if (fallbackOpenFailure !== failure) failure.addSuppressed(fallbackOpenFailure)
        throw failure
    }
    return fallback.also { index ->
        try {
            prepareIndex(index, request)
        } catch (fallbackFailure: Throwable) {
            try {
                index.close()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== fallbackFailure) fallbackFailure.addSuppressed(cleanupFailure)
            }
            failure.addSuppressed(fallbackFailure)
            throw failure
        }
    }
}

private fun prepareIndex(index: TextPageIndex, request: TextSessionRequest) {
    index.prepareDocument(request.bookId, request.documentVersion)
    check(
        index.prepareSource(
            request.bookId,
            request.documentVersion,
            request.source,
            request.textSchemaVersion,
            request.engineVersion
        ) == TextPageIndexWriteOutcome.APPLIED
    ) { "text index metadata changed during session construction" }
}
