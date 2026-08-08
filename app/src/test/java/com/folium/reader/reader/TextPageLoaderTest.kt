package com.folium.reader.reader

import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.Raster
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.text.TextBlock
import com.folium.reader.core.text.TextFont
import com.folium.reader.core.text.TextLine
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TextPageLoaderTest {
    private class CapturingThreadFactory : (Runnable) -> Thread {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        @Volatile var thread: Thread? = null

        override fun invoke(runnable: Runnable): Thread = Thread(runnable, "reader-text-test").apply {
            isDaemon = true
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught += error }
            thread = this
        }
    }

    private class FakeDocument(
        override val pageCount: Int,
        private val extract: (Int) -> TextPage
    ) : PdfDocument {
        val extracting = AtomicBoolean(false)
        var closedWhileExtracting = false

        override fun pageInfo(index: Int) = PageInfo(index, 1f, 1f, 0)
        override fun buildDisplayList(index: Int): DisplayList = object : DisplayList {
            override fun render(spec: RenderSpec, cancellationSignal: CancellationSignal) = Raster(1, 1, ByteArray(4))
            override fun close() = Unit
        }
        override fun extractText(index: Int): TextPage {
            extracting.set(true)
            return try {
                extract(index)
            } finally {
                extracting.set(false)
            }
        }
        override fun outline(): List<OutlineEntry> = emptyList()
        override fun close() {
            closedWhileExtracting = extracting.get()
        }
    }

    private val emptyPage = TextPage(emptyList(), TextSource.NATIVE_PDF)

    private fun page(text: String): TextPage = TextPage(
        listOf(TextBlock(listOf(TextLine(listOf(
            TextWord(text, PageSpaceRect(.1f, .1f, .9f, .2f), 0)
        ), 0)), 0)),
        TextSource.NATIVE_PDF
    )

    @Test fun sameActivePageDeduplicatesAndKeepsOnlyTheLatestPublicationOwner() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val deliveredOwners = Collections.synchronizedList(mutableListOf<Int>())
        val document = FakeDocument(10) {
            calls.incrementAndGet()
            entered.countDown()
            release.awaitIgnoringInterrupts()
            emptyPage
        }
        val loader = TextPageLoader(document, 10, deliver = { it() })

        loader.load(4) { deliveredOwners += 0 }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        repeat(100) { owner -> loader.load(4) { deliveredOwners += owner + 1 } }
        assertEquals(0, loader.queuedPageCount())

        release.countDown()
        waitUntil { deliveredOwners.isNotEmpty() }
        assertEquals(1, calls.get())
        assertEquals(listOf(100), deliveredOwners)
        loader.dispose()
    }

    @Test fun massiveNavigationKeepsOneLatestWinsQueuedPage() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val extracted = Collections.synchronizedList(mutableListOf<Int>())
        val delivered = Collections.synchronizedList(mutableListOf<Int>())
        val document = FakeDocument(1_000) { index ->
            extracted += index
            if (index == 0) {
                entered.countDown()
                release.awaitIgnoringInterrupts()
            }
            emptyPage
        }
        val loader = TextPageLoader(document, 1_000, deliver = { it() })

        loader.load(0) { delivered += 0 }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        (1 until 1_000).forEach { page -> loader.load(page) { delivered += page } }

        assertEquals(1, loader.queuedPageCount())
        release.countDown()
        waitUntil { delivered.isNotEmpty() }
        assertEquals(listOf(0, 999), extracted)
        assertEquals(listOf(999), delivered)
        loader.dispose()
    }

    @Test fun publicationQueuedForAnOlderOwnerIsSuppressedAfterSupersession() {
        val publications = CopyOnWriteArrayList<() -> Unit>()
        val extracted = AtomicInteger()
        val document = FakeDocument(2) { extracted.incrementAndGet(); emptyPage }
        val loader = TextPageLoader(document, 2, deliver = { publications += it })
        val delivered = Collections.synchronizedList(mutableListOf<Int>())

        loader.load(0) { delivered += 0 }
        waitUntil { publications.size == 1 }
        loader.load(1) { delivered += 1 }
        publications[0].invoke()
        assertTrue(delivered.isEmpty())

        waitUntil { publications.size == 2 }
        publications[1].invoke()
        assertEquals(listOf(1), delivered)
        assertEquals(2, extracted.get())
        loader.dispose()
    }

    @Test fun cacheEvictsByEstimatedBytesInsteadOfEntryCount() {
        val first = page("a".repeat(2_000))
        val second = page("b".repeat(2_000))
        val onePageBudget = estimateTextPageBytes(first) + 64L
        val calls = AtomicInteger()
        val document = FakeDocument(3) { index -> calls.incrementAndGet(); if (index == 0) first else second }
        val loader = TextPageLoader(document, 3, deliver = { it() }, maxCacheBytes = onePageBudget)

        loadAndWait(loader, 0)
        loadAndWait(loader, 1)

        assertEquals(1, loader.cachedPageCount())
        assertTrue(loader.cachedBytes() <= onePageBudget)
        loadAndWait(loader, 0)
        assertEquals(3, calls.get())
        loader.dispose()
    }

    @Test fun oversizedTextPageIsPublishedButNeverAdmittedToCache() {
        val large = page("x".repeat(100_000))
        val calls = AtomicInteger()
        val document = FakeDocument(1) { calls.incrementAndGet(); large }
        val loader = TextPageLoader(document, 1, deliver = { it() }, maxCacheBytes = 1_024L)

        loadAndWait(loader, 0)
        loadAndWait(loader, 0)

        assertEquals(2, calls.get())
        assertEquals(0, loader.cachedPageCount())
        assertEquals(0L, loader.cachedBytes())
        assertTrue(estimateTextPageBytes(large) > 200_000L)
        loader.dispose()
    }

    @Test fun byteEstimateAccountsForHierarchyDerivedStringsFontsAndGeometryOwners() {
        val minimal = page("word")
        val rich = TextPage(
            listOf(
                TextBlock(listOf(TextLine(listOf(
                    TextWord(
                        "word",
                        PageSpaceRect(.1f, .1f, .3f, .2f),
                        0,
                        fonts = listOf(TextFont("Long Font Name", false, false, false, false)),
                        languageTag = "en-US"
                    ),
                    TextWord("second", PageSpaceRect(.4f, .1f, .7f, .2f), 1)
                ), 0)), 0),
                TextBlock(listOf(TextLine(listOf(
                    TextWord("third", PageSpaceRect(.1f, .4f, .4f, .5f), 0)
                ), 0)), 1)
            ),
            TextSource.OCR
        )

        assertTrue(estimateTextPageBytes(rich) > estimateTextPageBytes(minimal))
    }

    @Test fun extractionFailurePublishesFailedWithoutLeavingCachedOrQueuedState() {
        val document = FakeDocument(1) { throw IllegalStateException("broken text layer") }
        val loader = TextPageLoader(document, 1, deliver = { it() })
        val delivered = CountDownLatch(1)
        var result: TextPageLoadResult? = null

        loader.load(0) {
            result = it
            delivered.countDown()
        }

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals(TextPageLoadResult.Failed, result)
        assertEquals(0, loader.cachedPageCount())
        assertEquals(0, loader.queuedPageCount())
        loader.dispose()
    }

    @Test fun closeSuppressesLatePublicationAndDisposeDrainsBeforeDocumentClose() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val document = FakeDocument(2) {
            entered.countDown()
            release.awaitIgnoringInterrupts()
            emptyPage
        }
        val threads = CapturingThreadFactory()
        val loader = TextPageLoader(document, 2, deliver = { it() }, threadFactory = threads)

        loader.load(0) { callbacks.incrementAndGet() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        loader.close()

        val disposed = CountDownLatch(1)
        Thread { loader.dispose(); disposed.countDown() }.start()
        assertFalse(disposed.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(disposed.await(2, TimeUnit.SECONDS))
        document.close()

        assertEquals(0, callbacks.get())
        assertFalse(document.closedWhileExtracting)
        assertTrue(threads.uncaught.isEmpty())
    }

    @Test fun closingAnIdleLoaderWakesItsWorkerWithoutAnUncaughtFailure() {
        val threads = CapturingThreadFactory()
        val loader = TextPageLoader(FakeDocument(1) { emptyPage }, 1, deliver = { it() }, threadFactory = threads)
        waitUntil { threads.thread?.state == Thread.State.WAITING }

        loader.dispose()

        assertFalse(requireNotNull(threads.thread).isAlive)
        assertTrue(threads.uncaught.isEmpty())
    }

    @Test fun repeatedOpenAndBackShutdownDoesNotLeakUncaughtWorkerFailures() {
        repeat(50) {
            val threads = CapturingThreadFactory()
            val loader = TextPageLoader(FakeDocument(1) { emptyPage }, 1, deliver = { it() }, threadFactory = threads)
            waitUntil { threads.thread?.state == Thread.State.WAITING }

            loader.dispose()

            assertFalse("cycle $it left its worker alive", requireNotNull(threads.thread).isAlive)
            assertTrue("cycle $it had uncaught ${threads.uncaught}", threads.uncaught.isEmpty())
        }
    }

    private fun loadAndWait(loader: TextPageLoader, pageIndex: Int) {
        val delivered = CountDownLatch(1)
        loader.load(pageIndex) { delivered.countDown() }
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}

private fun CountDownLatch.awaitIgnoringInterrupts() {
    while (count > 0L) {
        try {
            await()
        } catch (_: InterruptedException) {
            // The native extraction analogue does not stop until its owner releases it.
        }
    }
}
