package com.folium.reader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReaderSessionLifecycleTest {
    @Test fun closeAttemptsEveryStageInOrderAfterFailureAndIsIdempotent() {
        closeStages.forEach { failingStage ->
            val events = mutableListOf<String>()
            val lifecycle = lifecycle(events, mapOf(failingStage to CleanupFailure(failingStage)))

            lifecycle.close()
            val failure = lifecycle.closeFailure as CleanupFailure

            assertEquals(failingStage, failure.stage)
            assertEquals(closeStages, events)
            lifecycle.close()
            assertEquals(closeStages, events)
        }
    }

    @Test fun disposeAttemptsEveryStageInOrderAfterFailureAndIsIdempotent() {
        disposeStages.forEach { failingStage ->
            val events = mutableListOf<String>()
            val lifecycle = lifecycle(events, mapOf(failingStage to CleanupFailure(failingStage)))

            lifecycle.dispose()
            val failure = lifecycle.disposeFailure as CleanupFailure

            assertEquals(failingStage, failure.stage)
            assertEquals(disposeStages, events)
            lifecycle.dispose()
            assertEquals(disposeStages, events)
        }
    }

    @Test fun closeRetainsFirstFailureAndSuppressesLaterFailures() {
        assertAggregatedFailures(closeStages) { lifecycle -> lifecycle.close() }
    }

    @Test fun disposeRetainsFirstFailureAndSuppressesLaterFailures() {
        assertAggregatedFailures(disposeStages) { lifecycle -> lifecycle.dispose() }
    }

    @Test fun hostSchedulesDisposeWhenCloseFailsAndPreservesBothFailures() {
        val closeFailure = CleanupFailure("close")
        val scheduleFailure = CleanupFailure("schedule-dispose")
        val events = mutableListOf<String>()

        val thrown = closeThenScheduleDispose(
            close = { events += "close"; throw closeFailure },
            scheduleDispose = { events += "schedule-dispose"; throw scheduleFailure }
        )

        assertSame(closeFailure, thrown)
        assertEquals(listOf("close", "schedule-dispose"), events)
        assertEquals(listOf(scheduleFailure), requireNotNull(thrown).suppressed.toList())
    }

    private fun assertAggregatedFailures(stages: List<String>, action: (ReaderSessionLifecycle) -> Unit) {
        val events = mutableListOf<String>()
        val failures = stages.associateWith(::CleanupFailure)
        val lifecycle = lifecycle(events, failures)

        action(lifecycle)
        val thrown = lifecycle.closeFailure ?: lifecycle.disposeFailure

        assertSame(failures.getValue(stages.first()), thrown)
        assertEquals(stages, events)
        assertEquals(stages.drop(1), requireNotNull(thrown).suppressed.map { (it as CleanupFailure).stage })
    }

    private fun lifecycle(events: MutableList<String>, failures: Map<String, CleanupFailure>) = ReaderSessionLifecycle(
        unregisterCallbacks = stage("callbacks", events, failures),
        closeTextLoader = stage("text-loader-close", events, failures),
        closePresenter = stage("presenter-close", events, failures),
        shutdownPresenter = stage("presenter-shutdown", events, failures),
        disposeTextLoader = stage("text-loader-dispose", events, failures),
        closeTextIndex = stage("text-index-close", events, failures),
        clearPageCache = stage("cache-clear", events, failures),
        closeDocument = stage("document-close", events, failures)
    )

    private fun stage(
        name: String,
        events: MutableList<String>,
        failures: Map<String, CleanupFailure>
    ): () -> Unit = {
        events += name
        failures[name]?.let { throw it }
    }

    private companion object {
        val closeStages = listOf("callbacks", "text-loader-close", "presenter-close")
        val disposeStages = listOf(
            "presenter-shutdown",
            "text-loader-dispose",
            "text-index-close",
            "cache-clear",
            "document-close"
        )
    }
}

private class CleanupFailure(val stage: String) : RuntimeException(stage)
