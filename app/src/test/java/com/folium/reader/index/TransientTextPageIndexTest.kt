package com.folium.reader.index

import com.folium.reader.core.library.BookId
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
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
}

private fun transientThread(name: String, block: () -> Unit) = Thread(block, name).apply { isDaemon = true }
