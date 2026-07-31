package com.folium.reader.engine_mupdf

import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MuPdfOwnershipTest {
    @Test fun closeIsIdempotentAndPreventsLaterOperations() {
        var cleanupCount = 0
        var releaseCount = 0
        val owner = MuPdfSessionOwner { releaseCount++ }

        owner.close { cleanupCount++ }
        owner.close { cleanupCount++ }

        assertEquals(1, cleanupCount)
        assertEquals(1, releaseCount)
        val error = org.junit.Assert.assertThrows(PdfException::class.java) { owner.use {} }
        assertEquals(PdfFailure.Closed, error.failure)
    }

    @Test fun serializesConcurrentNativeOperations() {
        val owner = MuPdfSessionOwner()
        val workers = Executors.newFixedThreadPool(4)
        val entered = CountDownLatch(4)
        val start = CountDownLatch(1)
        val completed = CountDownLatch(4)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()

        repeat(4) {
            workers.execute {
                entered.countDown()
                start.await()
                owner.use {
                    maximumActive.updateAndGet { maxOf(it, active.incrementAndGet()) }
                    Thread.sleep(25)
                    active.decrementAndGet()
                }
                completed.countDown()
            }
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        workers.shutdownNow()
        assertEquals(1, maximumActive.get())
        assertEquals(0, active.get())
    }

    @Test fun nativeOwnerCleanupRunsExactlyOnceAfterConcurrentClose() {
        val cleanupCount = AtomicInteger()
        val owner = MuPdfSessionOwner()
        val workers = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val completed = CountDownLatch(2)

        repeat(2) {
            workers.execute {
                start.await()
                owner.close { cleanupCount.incrementAndGet() }
                completed.countDown()
            }
        }
        start.countDown()
        assertTrue(completed.await(1, TimeUnit.SECONDS))
        workers.shutdownNow()
        assertEquals(1, cleanupCount.get())
    }

    @Test fun fatalPostOpenFailureDestroysDocumentAndReleasesSession() {
        var destroyed = 0
        var released = 0
        val fatal = AssertionError("fatal native failure")

        val thrown = org.junit.Assert.assertThrows(AssertionError::class.java) {
            initializeMuPdfSession(
                acquire = { Any() },
                needsPassword = { throw fatal },
                createDocument = { error("unreachable") },
                destroy = { destroyed++ },
                releaseSession = { released++ }
            )
        }

        assertEquals(fatal, thrown)
        assertEquals(1, destroyed)
        assertEquals(1, released)
    }

    @Test fun onlyRecognizedNativeFailuresAreTranslated() {
        assertEquals(PdfFailure.Corrupt, translateOpenFailure(RuntimeException("cannot recognize version marker")).failure)
        assertEquals(PdfFailure.Resource(false), translateNativeFailure(RuntimeException("out of memory")).failure)

        val unknown = RuntimeException("unexpected JNI state").apply {
            stackTrace = arrayOf(StackTraceElement("com.artifex.mupdf.fitz.Document", "open", "Document.java", 1))
        }
        assertEquals(unknown, org.junit.Assert.assertThrows(RuntimeException::class.java) {
            translateOpenFailure(unknown)
        })
    }
}
