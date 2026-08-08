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
import org.junit.Assert.assertThrows
import org.junit.Test

class ReaderSessionConstructionTest {
    @Test fun databaseOpenFailureCleansEarlierResourcesInReverseOrder() {
        assertFailureCleanup(FailurePoint.DATABASE_OPEN, listOf("callbacks", "presenter", "cache", "document"))
    }

    @Test fun prepareDocumentFailureClosesIndexThenEarlierResources() {
        assertFailureCleanup(
            FailurePoint.PREPARE_DOCUMENT,
            listOf("index", "callbacks", "presenter", "cache", "document")
        )
    }

    @Test fun prepareSourceFailureClosesIndexThenEarlierResources() {
        assertFailureCleanup(
            FailurePoint.PREPARE_SOURCE,
            listOf("index", "callbacks", "presenter", "cache", "document")
        )
    }

    @Test fun loaderConstructionFailureClosesIndexThenEarlierResources() {
        assertFailureCleanup(
            FailurePoint.LOADER_CONSTRUCTION,
            listOf("index", "callbacks", "presenter", "cache", "document")
        )
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

    private fun assertFailureCleanup(point: FailurePoint, expected: List<String>) {
        val events = mutableListOf<String>()
        val scope = baseScope(events)
        val index = ConstructionFakeIndex(events, point)

        assertThrows(InjectedFailure::class.java) {
            scope.construct {
                acquireTextSessionResources(
                    this,
                    request(),
                    openIndex = {
                        if (point == FailurePoint.DATABASE_OPEN) throw InjectedFailure()
                        index
                    },
                    createLoader = SessionTextLoaderFactory { _, _ ->
                        if (point == FailurePoint.LOADER_CONSTRUCTION) throw InjectedFailure()
                        ConstructionFakeLoader(events)
                    }
                )
            }
        }

        assertEquals(expected, events)
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

private enum class FailurePoint { DATABASE_OPEN, PREPARE_DOCUMENT, PREPARE_SOURCE, LOADER_CONSTRUCTION }
private class InjectedFailure : RuntimeException()

private class ConstructionFakeIndex(
    private val events: MutableList<String>,
    private val failurePoint: FailurePoint? = null
) : TextPageIndex {
    var preparedSource: TextSource? = null
    var preparedEngineVersion: TextEngineVersion? = null

    override fun prepareDocument(bookId: BookId, documentVersion: DocumentContentVersion) {
        if (failurePoint == FailurePoint.PREPARE_DOCUMENT) throw InjectedFailure()
    }

    override fun prepareSource(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        source: TextSource,
        textSchemaVersion: Int,
        engineVersion: TextEngineVersion
    ): TextPageIndexWriteOutcome {
        if (failurePoint == FailurePoint.PREPARE_SOURCE) throw InjectedFailure()
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
    override fun close() { events += "index" }
}

private class ConstructionFakeLoader(private val events: MutableList<String>) : SessionTextLoader {
    override fun load(pageIndex: Int, callback: (TextPageLoadResult) -> Unit) = Unit
    override fun close() = Unit
    override fun dispose() { events += "loader" }
}
