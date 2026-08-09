package com.folium.reader.reader

import com.folium.reader.core.library.BookId
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
import com.folium.reader.index.DocumentContentVersion
import com.folium.reader.index.TextPageIndex
import com.folium.reader.index.TextPageIndexKey
import com.folium.reader.index.TextPageIndexStartResult
import com.folium.reader.index.TextPageIndexState
import com.folium.reader.index.TextPageIndexWriteOutcome
import com.folium.reader.index.TextPageSearchHit
import com.folium.reader.index.TextPagePublicationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderSessionConstructionTest {
    @Test fun sha256FailureUsesNeutralTransientPlanWithoutFailingPdfOpen() {
        val failure = InjectedFailure()

        val plan = textIndexSessionPlan(File("unused")) { throw failure }

        assertFalse(plan.persistent)
        assertEquals(DocumentContentVersion("0".repeat(64)), plan.documentVersion)
        assertSame(failure, plan.fallbackFailure)
    }

    @Test fun databaseOpenFailureFallsBackToTransientIndexAndKeepsLoaderAvailable() {
        assertIndexFailureFallsBack(FailurePoint.DATABASE_OPEN, emptyList())
    }

    @Test fun prepareDocumentFailureClosesPersistentIndexBeforeOpeningFallback() {
        assertIndexFailureFallsBack(FailurePoint.PREPARE_DOCUMENT, listOf("index"))
    }

    @Test fun prepareSourceFailureClosesPersistentIndexBeforeOpeningFallback() {
        assertIndexFailureFallsBack(FailurePoint.PREPARE_SOURCE, listOf("index"))
    }

    @Test fun loaderConstructionFailureClosesIndexThenEarlierResources() {
        val events = mutableListOf<String>()
        val scope = baseScope(events)
        assertThrows(InjectedFailure::class.java) {
            scope.construct {
                acquireTextSessionResources(
                    this, request(), openIndex = { ConstructionFakeIndex(events) },
                    createLoader = SessionTextLoaderFactory { _, _ -> throw InjectedFailure() }
                )
            }
        }
        assertEquals(listOf("index", "callbacks", "presenter", "cache", "document"), events)
    }

    @Test fun persistentCleanupFailureIsRetainedOnFallbackCauseBeforeFallbackPreparation() {
        val events = mutableListOf<String>()
        val prepareFailure = InjectedFailure()
        val closeFailure = ConstructionCleanupFailure("persistent-close")
        var captured: Throwable? = null

        val resources = SessionConstructionScope().construct {
            acquireTextSessionResources(
                this,
                request(),
                openIndex = { ConstructionFakeIndex(events, FailurePoint.PREPARE_DOCUMENT, prepareFailure, closeFailure) },
                openFallbackIndex = { failure ->
                    captured = failure
                    events += "fallback-open"
                    ConstructionFakeIndex(events)
                },
                createLoader = SessionTextLoaderFactory { _, _ -> ConstructionFakeLoader(events) }
            )
        }

        assertSame(prepareFailure, captured)
        assertEquals(listOf(closeFailure), requireNotNull(captured).suppressed.toList())
        assertEquals(listOf("index", "fallback-open"), events)
        resources.loader.dispose()
        resources.index.close()
    }

    @Test fun failureAfterLoaderDrainsLoaderBeforeClosingIndexAndDocumentOnce() {
        val events = mutableListOf<String>()
        val scope = baseScope(events)

        assertThrows(InjectedFailure::class.java) {
            scope.construct {
                acquireTextSessionResources(
                    this,
                    request(),
                    openIndex = { ConstructionFakeIndex(events) },
                    createLoader = SessionTextLoaderFactory { _, _ -> ConstructionFakeLoader(events) }
                )
                throw InjectedFailure()
            }
        }

        assertEquals(listOf("loader", "index", "callbacks", "presenter", "cache", "document"), events)
        assertEquals(1, events.count { it == "document" })
    }

    @Test fun genericTextSessionPathPreservesOcrSourceAndEngineDataVersion() {
        val events = mutableListOf<String>()
        val index = ConstructionFakeIndex(events)
        val ocrVersion = TextEngineVersion("tesseract-v1|eng:data-v5")
        lateinit var loaderKey: TextPageIndexKey

        val resources = SessionConstructionScope().construct {
            acquireTextSessionResources(
                this,
                request().copy(source = TextSource.OCR, engineVersion = ocrVersion),
                openIndex = { index },
                createLoader = SessionTextLoaderFactory { _, keyFactory ->
                    loaderKey = keyFactory(0)
                    ConstructionFakeLoader(events)
                }
            )
        }

        assertEquals(TextSource.OCR, index.preparedSource)
        assertEquals(ocrVersion, index.preparedEngineVersion)
        assertEquals(TextSource.OCR, loaderKey.source)
        assertEquals(ocrVersion, loaderKey.engineVersion)
        resources.loader.dispose()
        resources.index.close()
    }

    private fun assertIndexFailureFallsBack(point: FailurePoint, expectedBeforeFallback: List<String>) {
        val events = mutableListOf<String>()
        val index = ConstructionFakeIndex(events, point)
        var fallbackCause: Throwable? = null

        val resources = SessionConstructionScope().construct {
            acquireTextSessionResources(
                this,
                request(),
                openIndex = {
                    if (point == FailurePoint.DATABASE_OPEN) throw InjectedFailure()
                    index
                },
                openFallbackIndex = { failure ->
                    fallbackCause = failure
                    events += "fallback-open"
                    ConstructionFakeIndex(events)
                },
                createLoader = SessionTextLoaderFactory { _, _ -> ConstructionFakeLoader(events) }
            )
        }

        assertTrue(fallbackCause is InjectedFailure)
        assertEquals(expectedBeforeFallback + "fallback-open", events)
        resources.loader.dispose()
        resources.index.close()
    }

    private fun baseScope(events: MutableList<String>) = SessionConstructionScope().apply {
        acquire({ Any() }) { events += "document" }
        acquire({ Any() }) { events += "cache" }
        acquire({ Any() }) { events += "presenter" }
        onCleanup { events += "callbacks" }
    }

    private fun request() = TextSessionRequest(
        BookId("book"),
        DocumentContentVersion("ab".repeat(32)),
        pageCount = 1,
        source = TextSource.NATIVE_PDF,
        engineVersion = TextEngineVersion("native-v1")
    )
}

private enum class FailurePoint { DATABASE_OPEN, PREPARE_DOCUMENT, PREPARE_SOURCE }
private class InjectedFailure : RuntimeException()
private class ConstructionCleanupFailure(message: String) : RuntimeException(message)

private class ConstructionFakeIndex(
    private val events: MutableList<String>,
    private val failurePoint: FailurePoint? = null,
    private val injectedFailure: Throwable = InjectedFailure(),
    private val closeFailure: Throwable? = null
) : TextPageIndex {
    var preparedSource: TextSource? = null
    var preparedEngineVersion: TextEngineVersion? = null

    override fun prepareDocument(bookId: BookId, documentVersion: DocumentContentVersion) {
        if (failurePoint == FailurePoint.PREPARE_DOCUMENT) throw injectedFailure
    }

    override fun prepareSource(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        source: TextSource,
        textSchemaVersion: Int,
        engineVersion: TextEngineVersion
    ): TextPageIndexWriteOutcome {
        if (failurePoint == FailurePoint.PREPARE_SOURCE) throw injectedFailure
        preparedSource = source
        preparedEngineVersion = engineVersion
        return TextPageIndexWriteOutcome.APPLIED
    }

    override fun load(key: TextPageIndexKey): TextPage? = null
    override fun state(key: TextPageIndexKey): TextPageIndexState? = null
    override fun markInProgress(key: TextPageIndexKey) =
        TextPageIndexStartResult(TextPageIndexWriteOutcome.APPLIED)
    override fun complete(key: TextPageIndexKey, page: TextPage) = TextPageIndexWriteOutcome.APPLIED
    override fun markFailed(key: TextPageIndexKey) = TextPageIndexWriteOutcome.APPLIED
    override fun publishIfCurrent(
        key: TextPageIndexKey,
        publication: () -> Unit
    ): TextPagePublicationOutcome {
        publication()
        return TextPagePublicationOutcome.CURRENT
    }
    override fun searchIfCurrent(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        query: String,
        publication: (List<TextPageSearchHit>) -> Unit
    ): TextPagePublicationOutcome {
        publication(emptyList())
        return TextPagePublicationOutcome.CURRENT
    }
    override fun close() { events += "index"; closeFailure?.let { throw it } }
}

private class ConstructionFakeLoader(private val events: MutableList<String>) : SessionTextLoader {
    override fun load(pageIndex: Int, callback: (TextPageLoadResult) -> Unit) = Unit
    override fun close() = Unit
    override fun dispose() { events += "loader" }
}
