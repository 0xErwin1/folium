package com.folium.reader.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Covers the renderer-failure branch of [ViewportScheduler]: a thrown [PdfException] must surface
 * its typed [PdfFailure] on the rejected outcome rather than collapsing into an opaque reason, and
 * an unexpected non-[PdfException] failure must still free the worker slot and report a rejection.
 */
class ViewportSchedulerFailureTest {

    /**
     * A regression on any of these paths shows up as an unbounded hang rather than as a failed
     * assertion, and a wedged suite costs far more than a red build (`folium/probe-timeout-scaling`).
     * The bound dominates every observation timeout in this class by a wide margin, so it can only
     * ever fire on a genuine hang.
     */
    @get:Rule val perTestTimeout: Timeout = Timeout.seconds(60)

    @Test fun corruptDocumentFailureIsRejectedWithTheTypedFailureAttached() {
        val outcomes = CountDownLatch(1)
        var lastOutcome: SchedulerOutcome<String>? = null

        val renderer = ViewportRenderer<String> { _, _ -> throw PdfException(PdfFailure.Corrupt) }
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) {
            lastOutcome = it
            outcomes.countDown()
        }
        try {
            scheduler.submit(0, RenderPriority.VISIBLE, spec())
            assertTrue(outcomes.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        val outcome = lastOutcome as SchedulerOutcome.Rejected
        assertEquals(RejectionReason.FAILED, outcome.reason)
        assertEquals(PdfFailure.Corrupt, outcome.failure)
    }

    @Test fun retryableResourceFailureIsDistinguishableFromACorruptDocument() {
        val outcomes = CountDownLatch(1)
        var lastOutcome: SchedulerOutcome<String>? = null

        val renderer = ViewportRenderer<String> { _, _ -> throw PdfException(PdfFailure.Resource(retryable = true)) }
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) {
            lastOutcome = it
            outcomes.countDown()
        }
        try {
            scheduler.submit(0, RenderPriority.VISIBLE, spec())
            assertTrue(outcomes.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        val outcome = lastOutcome as SchedulerOutcome.Rejected
        assertEquals(RejectionReason.FAILED, outcome.reason)
        assertEquals(PdfFailure.Resource(retryable = true), outcome.failure)
    }

    /**
     * Exercises [ViewportScheduler]'s rollback around a failed dispatch directly, through the
     * internal [ViewportScheduler.workerDispatchHookForTests] seam, rather than only through the
     * renderer-throw branch the other two tests in this file cover. Without this test, a regression
     * that reintroduced a partial rollback (e.g. forgetting to remove the request from `inFlight`)
     * would pass the rest of the suite: the renderer never runs for a request whose dispatch never
     * happened, so no other test reaches this code path.
     */
    @Test fun workerDispatchFailureRollsBackFullyAndSurfacesARetryableRejection() {
        val outcomes = CountDownLatch(2)
        val received = mutableListOf<SchedulerOutcome<String>>()

        val renderer = ViewportRenderer<String> { request, _ -> RenderCandidate("page-${request.pageIndex}") {} }
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) {
            synchronized(received) { received.add(it) }
            outcomes.countDown()
        }
        scheduler.workerDispatchHookForTests = { throw OutOfMemoryError("simulated native thread-creation failure") }

        try {
            scheduler.submit(0, RenderPriority.VISIBLE, spec())
            assertEquals(0, scheduler.pendingCount())

            scheduler.workerDispatchHookForTests = null
            scheduler.submit(1, RenderPriority.VISIBLE, spec())
            assertTrue(outcomes.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }

        val rejected = synchronized(received) { received.filterIsInstance<SchedulerOutcome.Rejected>() }.single()
        assertEquals(RejectionReason.FAILED, rejected.reason)
        assertEquals(PdfFailure.Resource(retryable = true), rejected.failure)

        val rendered = synchronized(received) { received.filterIsInstance<SchedulerOutcome.Rendered<String>>() }.single()
        assertEquals(1, rendered.request.pageIndex)

        assertEquals(0, scheduler.cancelledCount())
    }

    @Test fun unexpectedNonPdfFailureStillFreesTheWorkerSlotAndReportsRejection() {
        val outcomes = CountDownLatch(2)
        val secondRendered = CountDownLatch(1)

        val renderer = ViewportRenderer<String> { request, _ ->
            if (request.pageIndex == 0) throw IllegalStateException("simulated unexpected failure")
            RenderCandidate("page-${request.pageIndex}") {}
        }
        val scheduler = ViewportScheduler(maxConcurrentWorkers = 1, renderer = renderer) { outcome ->
            if (outcome is SchedulerOutcome.Rendered) secondRendered.countDown()
            outcomes.countDown()
        }
        try {
            scheduler.submit(0, RenderPriority.VISIBLE, spec())
            scheduler.submit(1, RenderPriority.VISIBLE, spec())
            assertTrue(outcomes.await(5, TimeUnit.SECONDS))
            assertTrue("the worker slot freed by the first failure must serve the second request", secondRendered.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.close()
        }
    }
}
