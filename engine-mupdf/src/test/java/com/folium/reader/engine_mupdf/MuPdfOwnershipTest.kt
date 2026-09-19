package com.folium.reader.engine_mupdf

import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MuPdfOwnershipTest {
    @Test fun textVersionNamesNativeLibraryAndStructuredExtractionBehavior() {
        assertEquals("mupdf-1.28.0-structured-text-v1", MuPdfEngine().textEngineVersion.value)
    }

    @Test fun closeIsIdempotentAndPreventsLaterOperations() {
        var cleanupCount = 0
        var releaseCount = 0
        val owner = MuPdfSessionOwner { releaseCount++ }

        owner.close("test") { cleanupCount++ }
        owner.close("test") { cleanupCount++ }

        assertEquals(1, cleanupCount)
        assertEquals(1, releaseCount)
        val error = org.junit.Assert.assertThrows(PdfException::class.java) { owner.use("test") {} }
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
                owner.use("test") {
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

    /**
     * Models [MuPdfDocument.renderPage]'s single outer acquisition: an outer `owner.use("render")`
     * whose block makes its own nested `owner.use`/`owner.serialized` calls, exactly like
     * [MuPdfDocument.buildDisplayList], [MuPdfDisplayList.render] and [MuPdfDisplayList.close] do
     * from inside it.
     *
     * [ReentrantLock] lets same-thread nested calls re-enter without waiting, so a contending
     * second thread must never see the lock free between any two of the nested steps — only once
     * the outer block has returned. That is the difference between one acquisition for the whole
     * sequence and four acquisitions that happen to run back to back: with four, another thread can
     * slip in between any pair of them.
     */
    @Test fun oneStepRenderHoldsTheLockAcrossEveryNestedStep() {
        val owner = MuPdfSessionOwner()
        val stepMillis = 30L
        val started = CountDownLatch(1)
        val startNanos = java.util.concurrent.atomic.AtomicLong(-1)
        val contenderEnteredAtNanos = java.util.concurrent.atomic.AtomicLong(-1)

        val contender = Thread {
            started.await()
            owner.use("other") { contenderEnteredAtNanos.set(System.nanoTime()) }
        }
        contender.start()

        startNanos.set(System.nanoTime())
        owner.use("render") {
            started.countDown()
            owner.use("displaylist") { Thread.sleep(stepMillis) }
            owner.use("raster") { Thread.sleep(stepMillis) }
            owner.serialized("displayListClose") { Thread.sleep(stepMillis) }
        }

        contender.join(2_000)
        val elapsedMillis = (contenderEnteredAtNanos.get() - startNanos.get()) / 1_000_000
        assertTrue(
            "the contender must wait for the whole nested sequence, not slip in between steps; waited ${elapsedMillis}ms",
            elapsedMillis >= stepMillis * 3
        )
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
                owner.close("test") { cleanupCount.incrementAndGet() }
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

    @Test fun textExtractionMapsUnexpectedRuntimeFailureWithoutChangingTypedOrCancellationFailures() {
        val unexpected = IllegalStateException("unexpected text state")
        val mapped = org.junit.Assert.assertThrows(PdfException::class.java) {
            typedTextExtraction { throw unexpected }
        }
        assertEquals(PdfFailure.TextExtraction, mapped.failure)
        assertEquals(unexpected, mapped.cause)

        val typed = PdfException(PdfFailure.Resource(retryable = false))
        assertEquals(typed, org.junit.Assert.assertThrows(PdfException::class.java) {
            typedTextExtraction { throw typed }
        })

        val cancellation = CancellationException("cancelled")
        assertEquals(cancellation, org.junit.Assert.assertThrows(CancellationException::class.java) {
            typedTextExtraction { throw cancellation }
        })

        val fatal = AssertionError("fatal")
        assertSame(fatal, org.junit.Assert.assertThrows(AssertionError::class.java) {
            typedTextExtraction { throw fatal }
        })
    }
}
