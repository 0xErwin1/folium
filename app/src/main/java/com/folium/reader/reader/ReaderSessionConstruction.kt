package com.folium.reader.reader

import com.folium.reader.core.library.BookId
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextSource
import com.folium.reader.index.DocumentContentVersion
import com.folium.reader.index.TEXT_PAGE_SCHEMA_VERSION
import com.folium.reader.index.TextPageIndex
import com.folium.reader.index.TextPageIndexKey
import com.folium.reader.index.TextPageIndexWriteOutcome
import java.util.concurrent.atomic.AtomicBoolean

internal interface SessionTextLoader {
    fun load(pageIndex: Int, callback: (TextPageLoadResult) -> Unit)
    fun close()
    fun dispose()
}

/**
 * Owns the two teardown phases independently so each phase is attempted at most once. A failing
 * cleanup never prevents later cleanups in that phase; the first failure is rethrown after the
 * remaining failures have been attached to it as suppressed exceptions.
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

    fun close() {
        if (!closeStarted.compareAndSet(false, true)) return
        runCleanupStages(unregisterCallbacks, closeTextLoader, closePresenter)
    }

    fun dispose() {
        if (!disposeStarted.compareAndSet(false, true)) return
        runCleanupStages(shutdownPresenter, disposeTextLoader, closeTextIndex, clearPageCache, closeDocument)
    }
}

internal fun closeThenScheduleDispose(close: () -> Unit, scheduleDispose: () -> Unit) {
    runCleanupStages(close, scheduleDispose)
}

private fun runCleanupStages(vararg stages: () -> Unit) {
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
    firstFailure?.let { throw it }
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
    createLoader: SessionTextLoaderFactory
): TextSessionResources {
    val index = scope.acquire(openIndex, TextPageIndex::close)
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
