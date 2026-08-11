package com.folium.reader.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrPageStateReducerTest {
    @Test fun unusableReconcileQueuesOnlyAbsentNativeSuppressedAndStale() {
        assertStatus(OcrPageStateReducer.reconcile(null, false), OcrTransitionOutcome.APPLIED,
            OcrPageStatus(OcrPageState.QUEUED, 0))
        statuses().forEach { status ->
            val result = OcrPageStateReducer.reconcile(status, false)
            val queues = status.state == OcrPageState.STALE ||
                status.cancellationReason == OcrCancellationReason.NATIVE_TEXT
            val expected = if (queues) OcrPageStatus(OcrPageState.QUEUED, status.generation + 1) else status
            assertStatus(result, if (queues) OcrTransitionOutcome.APPLIED else OcrTransitionOutcome.UNCHANGED, expected)
        }
    }

    @Test fun usableReconcilePreservesBothCancellationReasonsAndSuppressesEveryOtherState() {
        statuses().forEach { status ->
            val result = OcrPageStateReducer.reconcile(status, true)
            if (status.state == OcrPageState.CANCELLED) {
                assertStatus(result, OcrTransitionOutcome.UNCHANGED, status)
            } else {
                assertStatus(result, OcrTransitionOutcome.APPLIED,
                    OcrPageStatus(OcrPageState.CANCELLED, status.generation + 1,
                        cancellationReason = OcrCancellationReason.NATIVE_TEXT))
            }
        }
    }

    @Test fun claimAcceptsOnlyQueuedWithoutChangingGeneration() {
        statuses().forEach { status ->
            val result = OcrPageStateReducer.claim(status)
            if (status.state == OcrPageState.QUEUED) {
                assertStatus(result, OcrTransitionOutcome.APPLIED,
                    OcrPageStatus(OcrPageState.RUNNING, status.generation))
            } else assertStatus(result, OcrTransitionOutcome.INVALID_STATE, status)
        }
        assertEquals(OcrTransitionOutcome.INVALID_STATE, OcrPageStateReducer.claim(null).outcome)
    }

    @Test fun completionRequiresRunningMatchingGenerationAndCurrentEligibility() {
        statuses().forEach { status ->
            val wrong = OcrPageStateReducer.complete(status, status.generation + 1, false)
            assertEquals(OcrTransitionOutcome.GENERATION_MISMATCH, wrong.outcome)
            val result = OcrPageStateReducer.complete(status, status.generation, false)
            if (status.state == OcrPageState.RUNNING) {
                assertStatus(result, OcrTransitionOutcome.APPLIED,
                    OcrPageStatus(OcrPageState.COMPLETED, status.generation))
                assertStatus(
                    OcrPageStateReducer.complete(status, status.generation, true),
                    OcrTransitionOutcome.NOT_ELIGIBLE,
                    OcrPageStatus(OcrPageState.CANCELLED, status.generation + 1,
                        cancellationReason = OcrCancellationReason.NATIVE_TEXT)
                )
            } else assertEquals(OcrTransitionOutcome.INVALID_STATE, result.outcome)
        }
    }

    @Test fun failureAndUserCancellationRequireRunningMatchingGeneration() {
        val failure = OcrFailureMetadata("recognition", true)
        statuses().forEach { status ->
            val failed = OcrPageStateReducer.fail(status, status.generation, failure)
            val cancelled = OcrPageStateReducer.cancel(status, status.generation)
            if (status.state == OcrPageState.RUNNING) {
                assertStatus(failed, OcrTransitionOutcome.APPLIED,
                    OcrPageStatus(OcrPageState.FAILED, status.generation, failure = failure))
                assertStatus(cancelled, OcrTransitionOutcome.APPLIED,
                    OcrPageStatus(OcrPageState.CANCELLED, status.generation,
                        cancellationReason = OcrCancellationReason.USER))
            } else {
                assertEquals(OcrTransitionOutcome.INVALID_STATE, failed.outcome)
                assertEquals(OcrTransitionOutcome.INVALID_STATE, cancelled.outcome)
            }
        }
    }

    @Test fun retryIsExplicitForFailedAndBothCancellationReasonsAndChecksEligibility() {
        statuses().forEach { status ->
            val result = OcrPageStateReducer.retry(status, false)
            val retryable = status.state in setOf(OcrPageState.FAILED, OcrPageState.CANCELLED)
            if (retryable) assertStatus(result, OcrTransitionOutcome.APPLIED,
                OcrPageStatus(OcrPageState.QUEUED, status.generation + 1))
            else assertEquals(OcrTransitionOutcome.INVALID_STATE, result.outcome)
            assertEquals(OcrTransitionOutcome.NOT_ELIGIBLE,
                OcrPageStateReducer.retry(status, true).outcome)
        }
    }

    @Test fun searchResumeOnlyRequeuesSearchPauseWithNewGeneration() {
        val paused = OcrPageStatus(
            OcrPageState.CANCELLED,
            4,
            cancellationReason = OcrCancellationReason.SEARCH_PAUSE
        )
        assertStatus(
            OcrPageStateReducer.resumeSearch(paused, nativeUsable = false),
            OcrTransitionOutcome.APPLIED,
            OcrPageStatus(OcrPageState.QUEUED, 5)
        )
        assertEquals(
            OcrTransitionOutcome.NOT_ELIGIBLE,
            OcrPageStateReducer.resumeSearch(paused, nativeUsable = true).outcome
        )
        statuses().filterNot { it == paused }.forEach { status ->
            assertEquals(
                OcrTransitionOutcome.INVALID_STATE,
                OcrPageStateReducer.resumeSearch(status, nativeUsable = false).outcome
            )
        }
    }

    @Test fun recoveryChangesOnlyRunningAndStaleAlwaysFencesGeneration() {
        statuses().forEach { status ->
            val recovered = OcrPageStateReducer.recover(status)
            if (status.state == OcrPageState.RUNNING) assertStatus(recovered,
                OcrTransitionOutcome.APPLIED, OcrPageStatus(OcrPageState.QUEUED, status.generation + 1))
            else assertStatus(recovered, OcrTransitionOutcome.UNCHANGED, status)
            assertStatus(OcrPageStateReducer.stale(status), OcrTransitionOutcome.APPLIED,
                OcrPageStatus(OcrPageState.STALE, status.generation + 1))
        }
    }

    private fun statuses() = listOf(
        OcrPageStatus(OcrPageState.QUEUED, 4),
        OcrPageStatus(OcrPageState.RUNNING, 4),
        OcrPageStatus(OcrPageState.COMPLETED, 4),
        OcrPageStatus(OcrPageState.FAILED, 4, failure = OcrFailureMetadata("failure", false)),
        OcrPageStatus(OcrPageState.CANCELLED, 4, cancellationReason = OcrCancellationReason.USER),
        OcrPageStatus(OcrPageState.CANCELLED, 4, cancellationReason = OcrCancellationReason.SEARCH_PAUSE),
        OcrPageStatus(OcrPageState.CANCELLED, 4, cancellationReason = OcrCancellationReason.SESSION),
        OcrPageStatus(OcrPageState.CANCELLED, 4, cancellationReason = OcrCancellationReason.NATIVE_TEXT),
        OcrPageStatus(OcrPageState.STALE, 4)
    )

    private fun assertStatus(
        transition: OcrStateTransition,
        outcome: OcrTransitionOutcome,
        status: OcrPageStatus
    ) {
        assertEquals(outcome, transition.outcome)
        assertEquals(status, transition.status)
    }
}
