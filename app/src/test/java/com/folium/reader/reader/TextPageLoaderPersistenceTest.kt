package com.folium.reader.reader

import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.Raster
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
import com.folium.reader.index.DocumentContentVersion
import com.folium.reader.index.TextPageIndex
import com.folium.reader.index.TextPageIndexKey
import com.folium.reader.index.TextPageIndexState
import com.folium.reader.index.TextPageIndexStartResult
import com.folium.reader.index.TextPageSearchHit
import com.folium.reader.index.TextPageIndexWriteOutcome
import com.folium.reader.index.TextPagePublicationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TextPageLoaderPersistenceTest {
    private val key = TextPageIndexKey(
        BookId("book"), DocumentContentVersion("ab".repeat(32)), 0,
        TextSource.NATIVE_PDF, 1, TextEngineVersion("native-v1")
    )
    private val page = TextPage(emptyList(), TextSource.NATIVE_PDF)

    @Test fun completePersistedPageIsReadWithoutNativeExtraction() {
        val index = FakeTextPageIndex().apply { complete(key, page) }
        val calls = AtomicInteger()
        val loader = loader(index) { calls.incrementAndGet(); error("must not extract") }

        assertEquals(TextPageLoadResult.Loaded(page), load(loader))
        assertEquals(0, calls.get())
        loader.dispose()
    }

    @Test fun missingPageIsMarkedThenPersistedBeforePublication() {
        val index = FakeTextPageIndex()
        val loader = loader(index) { page }

        assertEquals(TextPageLoadResult.Loaded(page), load(loader))
        assertEquals(listOf("in-progress:null", "complete"), index.events)
        assertEquals(page, index.load(key))
        loader.dispose()
    }

    @Test fun interruptedAndFailedPagesRemainRetryable() {
        for (initial in listOf(TextPageIndexState.IN_PROGRESS, TextPageIndexState.FAILED)) {
            val index = FakeTextPageIndex().apply { states[key] = initial }
            val loader = loader(index) { page }

            assertEquals(TextPageLoadResult.Loaded(page), load(loader))
            assertEquals("in-progress:$initial", index.events.first())
            assertEquals(TextPageIndexState.COMPLETE, index.state(key))
            loader.dispose()
        }
    }

    @Test fun extractionFailureIsPersistedAndNextSessionCanRetry() {
        val index = FakeTextPageIndex()
        val failed = loader(index) { error("broken") }
        assertEquals(TextPageLoadResult.Failed, load(failed))
        assertEquals(TextPageIndexState.FAILED, index.state(key))
        failed.dispose()

        val retried = loader(index) { page }
        assertEquals(TextPageLoadResult.Loaded(page), load(retried))
        assertEquals(TextPageIndexState.COMPLETE, index.state(key))
        retried.dispose()
    }

    @Test fun staleCompletionIsNeitherPublishedNorCached() {
        val index = FakeTextPageIndex().apply { rejectCompletion = true }
        val calls = AtomicInteger()
        val loader = loader(index) { calls.incrementAndGet(); page }

        assertEquals(TextPageLoadResult.Failed, load(loader))
        index.rejectCompletion = false
        assertEquals(TextPageLoadResult.Loaded(page), load(loader))
        assertTrue(calls.get() >= 2)
        loader.dispose()
    }

    @Test fun staleStartIsRejectedBeforeNativeExtraction() {
        val index = FakeTextPageIndex().apply { rejectStart = true }
        val calls = AtomicInteger()
        val loader = loader(index) { calls.incrementAndGet(); page }

        assertEquals(TextPageLoadResult.Failed, load(loader))
        assertEquals(0, calls.get())
        loader.dispose()
    }

    @Test fun invalidationAfterPersistedLoadRejectsCacheAdmissionAndPublication() {
        val index = FakeTextPageIndex().apply {
            complete(key, page)
            beforeNextPublicationFence = ::invalidate
        }
        val loader = loader(index) { error("persisted page must be used") }

        assertEquals(TextPageLoadResult.Failed, load(loader))
        assertEquals(0, loader.cachedPageCount())
        loader.dispose()
    }

    @Test fun invalidationAfterCompleteRejectsCacheAdmissionAndPublication() {
        val index = FakeTextPageIndex().apply { beforeNextPublicationFence = ::invalidate }
        val calls = AtomicInteger()
        val loader = loader(index) { calls.incrementAndGet(); page }

        assertEquals(TextPageLoadResult.Failed, load(loader))
        assertEquals(1, calls.get())
        assertEquals(0, loader.cachedPageCount())
        loader.dispose()
    }

    @Test fun memoryCacheHitAfterInvalidationIsRejectedAndEvictsItself() {
        val index = FakeTextPageIndex()
        val calls = AtomicInteger()
        val loader = loader(index) { calls.incrementAndGet(); page }
        assertEquals(TextPageLoadResult.Loaded(page), load(loader))
        assertEquals(1, loader.cachedPageCount())

        index.invalidate()
        assertEquals(TextPageLoadResult.Failed, load(loader))
        assertEquals(0, loader.cachedPageCount())
        assertEquals(1, calls.get())

        index.reactivate()
        assertEquals(TextPageLoadResult.Loaded(page), load(loader))
        assertEquals(2, calls.get())
        loader.dispose()
    }

    @Test fun publicationCallbackCanReentrantlyInvalidateAndCloseWithoutDeadlock() {
        val index = FakeTextPageIndex()
        val loader = loader(index) { page }
        val delivered = CountDownLatch(1)
        val results = mutableListOf<TextPageLoadResult>()

        loader.load(0) { result ->
            results += result
            index.invalidate()
            index.close()
            delivered.countDown()
        }

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        waitUntil { loader.cachedPageCount() == 0 }
        assertEquals(listOf(TextPageLoadResult.Loaded(page)), results)
        loader.dispose()
    }

    private fun loader(index: TextPageIndex, extract: () -> TextPage) = TextPageLoader(
        document = PersistenceFakeDocument(extract), pageCount = 1, deliver = { it() },
        index = index, indexKey = { key }
    )

    private fun load(loader: TextPageLoader): TextPageLoadResult {
        val latch = CountDownLatch(1)
        var result: TextPageLoadResult? = null
        loader.load(0) { result = it; latch.countDown() }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        return requireNotNull(result)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}

private class FakeTextPageIndex : TextPageIndex {
    val states = mutableMapOf<TextPageIndexKey, TextPageIndexState>()
    private val pages = mutableMapOf<TextPageIndexKey, TextPage>()
    val events = mutableListOf<String>()
    var rejectStart = false
    var rejectCompletion = false
    var beforeNextPublicationFence: (() -> Unit)? = null
    private var active = true
    private var closed = false
    private val publicationLock = ReentrantLock(true)
    override fun prepareDocument(bookId: BookId, documentVersion: DocumentContentVersion) = Unit
    override fun prepareSource(bookId: BookId, documentVersion: DocumentContentVersion, source: TextSource, textSchemaVersion: Int, engineVersion: TextEngineVersion) = TextPageIndexWriteOutcome.APPLIED
    override fun load(key: TextPageIndexKey) = synchronized(this) {
        pages[key].takeIf { !closed && active && states[key] == TextPageIndexState.COMPLETE }
    }
    override fun state(key: TextPageIndexKey) = synchronized(this) { states[key].takeIf { !closed && active } }
    override fun pageStatesIfCurrent(key: TextPageIndexKey) = synchronized(this) {
        if (!active || closed) return@synchronized null
        states.filterKeys {
            it.bookId == key.bookId && it.documentVersion == key.documentVersion &&
                it.source == key.source && it.textSchemaVersion == key.textSchemaVersion &&
                it.engineVersion == key.engineVersion
        }.mapKeys { it.key.pageIndex }
    }
    override fun markInProgress(key: TextPageIndexKey) = synchronized(this) {
        if (closed || !active || rejectStart) {
            return@synchronized TextPageIndexStartResult(TextPageIndexWriteOutcome.STALE)
        }
        val previous = states[key]
        events += "in-progress:$previous"
        states[key] = TextPageIndexState.IN_PROGRESS
        TextPageIndexStartResult(TextPageIndexWriteOutcome.APPLIED, previous)
    }
    override fun complete(key: TextPageIndexKey, page: TextPage) = synchronized(this) {
        if (closed || !active || rejectCompletion) return@synchronized TextPageIndexWriteOutcome.STALE
        events += "complete"; pages[key] = page; states[key] = TextPageIndexState.COMPLETE
        TextPageIndexWriteOutcome.APPLIED
    }
    override fun markFailed(key: TextPageIndexKey) = synchronized(this) {
        if (closed || !active) return@synchronized TextPageIndexWriteOutcome.STALE
        states[key] = TextPageIndexState.FAILED
        TextPageIndexWriteOutcome.APPLIED
    }
    override fun publishIfCurrent(
        key: TextPageIndexKey,
        publication: () -> Unit
    ): TextPagePublicationOutcome = publicationLock.withLock {
        val hook = synchronized(this) { beforeNextPublicationFence.also { beforeNextPublicationFence = null } }
        hook?.invoke()
        val current = synchronized(this) { !closed && active && states[key] == TextPageIndexState.COMPLETE }
        if (!current) return@withLock TextPagePublicationOutcome.NOT_CURRENT
        publication()
        val stillCurrent = synchronized(this) { !closed && active && states[key] == TextPageIndexState.COMPLETE }
        if (stillCurrent) TextPagePublicationOutcome.CURRENT
        else TextPagePublicationOutcome.INVALIDATED_DURING_PUBLICATION
    }
    override fun searchIfCurrent(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        query: String,
        includeOcr: Boolean,
        limit: Int,
        publication: (com.folium.reader.index.TextPageSearchResult) -> Unit
    ): TextPagePublicationOutcome = publicationLock.withLock {
        if (!active || closed) return@withLock TextPagePublicationOutcome.NOT_CURRENT
        publication(com.folium.reader.index.TextPageSearchResult(emptyList()))
        if (active && !closed) TextPagePublicationOutcome.CURRENT
        else TextPagePublicationOutcome.INVALIDATED_DURING_PUBLICATION
    }

    fun invalidate() = publicationLock.withLock {
        synchronized(this) {
            active = false
            pages.clear()
            states.clear()
        }
    }

    fun reactivate() = synchronized(this) { active = true }

    override fun close() = publicationLock.withLock { synchronized(this) { closed = true } }
}

private class PersistenceFakeDocument(private val extract: () -> TextPage) : PdfDocument {
    override val pageCount = 1
    override fun pageInfo(index: Int) = PageInfo(index, 1f, 1f, 0)
    override fun buildDisplayList(index: Int) = object : DisplayList {
        override fun render(spec: RenderSpec, cancellationSignal: CancellationSignal) = Raster(1, 1, ByteArray(4))
        override fun close() = Unit
    }
    override fun extractText(index: Int) = extract()
    override fun outline(): List<OutlineEntry> = emptyList()
    override fun metadata() = com.folium.reader.core.pdf.DocumentMetadata.NONE
    override fun close() = Unit
}
