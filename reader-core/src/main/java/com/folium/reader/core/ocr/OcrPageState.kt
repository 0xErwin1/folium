package com.folium.reader.core.ocr

enum class OcrPageState { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, STALE }

enum class OcrCancellationReason { USER, SEARCH_PAUSE, SESSION, NATIVE_TEXT }

data class OcrFailureMetadata(val kind: String, val retryable: Boolean) {
    init { require(kind.isNotBlank()) }
}

data class OcrPageStatus(
    val state: OcrPageState,
    val generation: Long,
    val cancellationReason: OcrCancellationReason? = null,
    val failure: OcrFailureMetadata? = null
) {
    init {
        require(generation >= 0)
        require((state == OcrPageState.CANCELLED) == (cancellationReason != null))
        require((state == OcrPageState.FAILED) == (failure != null))
    }
}

enum class OcrTransitionOutcome {
    APPLIED,
    UNCHANGED,
    NOT_ELIGIBLE,
    INVALID_STATE,
    GENERATION_MISMATCH,
    STALE,
    REJECTED_DURING_PUBLICATION
}

data class OcrStateTransition(
    val outcome: OcrTransitionOutcome,
    val status: OcrPageStatus? = null
)

/** Pure policy reducer. Persistence adapters may only apply the returned transition. */
object OcrPageStateReducer {
    fun reconcile(status: OcrPageStatus?, nativeUsable: Boolean): OcrStateTransition {
        if (nativeUsable) {
            if (status?.state == OcrPageState.CANCELLED) return unchanged(status)
            return applied(OcrPageStatus(
                OcrPageState.CANCELLED,
                nextGeneration(status),
                cancellationReason = OcrCancellationReason.NATIVE_TEXT
            ))
        }
        return when {
            status == null -> applied(OcrPageStatus(OcrPageState.QUEUED, 0))
            status.state == OcrPageState.CANCELLED &&
                status.cancellationReason == OcrCancellationReason.NATIVE_TEXT ->
                applied(OcrPageStatus(OcrPageState.QUEUED, status.generation + 1))
            status.state == OcrPageState.STALE ->
                applied(OcrPageStatus(OcrPageState.QUEUED, status.generation + 1))
            else -> unchanged(status)
        }
    }

    fun recover(status: OcrPageStatus): OcrStateTransition =
        if (status.state == OcrPageState.RUNNING) {
            applied(OcrPageStatus(OcrPageState.QUEUED, status.generation + 1))
        } else unchanged(status)

    fun claim(status: OcrPageStatus?): OcrStateTransition =
        if (status?.state == OcrPageState.QUEUED) {
            applied(OcrPageStatus(OcrPageState.RUNNING, status.generation))
        } else invalid(status)

    fun complete(status: OcrPageStatus?, generation: Long, nativeUsable: Boolean): OcrStateTransition {
        generationFence(status, generation)?.let { return it }
        if (status?.state != OcrPageState.RUNNING) return invalid(status)
        if (nativeUsable) {
            return OcrStateTransition(
                OcrTransitionOutcome.NOT_ELIGIBLE,
                OcrPageStatus(OcrPageState.CANCELLED, status.generation + 1,
                    cancellationReason = OcrCancellationReason.NATIVE_TEXT)
            )
        }
        return applied(OcrPageStatus(OcrPageState.COMPLETED, generation))
    }

    fun fail(
        status: OcrPageStatus?,
        generation: Long,
        failure: OcrFailureMetadata
    ): OcrStateTransition {
        generationFence(status, generation)?.let { return it }
        if (status?.state != OcrPageState.RUNNING) return invalid(status)
        return applied(OcrPageStatus(OcrPageState.FAILED, generation, failure = failure))
    }

    fun cancel(
        status: OcrPageStatus?,
        generation: Long,
        reason: OcrCancellationReason = OcrCancellationReason.USER
    ): OcrStateTransition {
        generationFence(status, generation)?.let { return it }
        if (status?.state != OcrPageState.RUNNING) return invalid(status)
        return applied(OcrPageStatus(OcrPageState.CANCELLED, generation,
            cancellationReason = reason))
    }

    fun resumeSearch(status: OcrPageStatus?, nativeUsable: Boolean): OcrStateTransition {
        if (nativeUsable) return OcrStateTransition(OcrTransitionOutcome.NOT_ELIGIBLE, status)
        if (status?.state != OcrPageState.CANCELLED ||
            status.cancellationReason != OcrCancellationReason.SEARCH_PAUSE) return invalid(status)
        return applied(OcrPageStatus(OcrPageState.QUEUED, status.generation + 1))
    }

    fun retry(status: OcrPageStatus?, nativeUsable: Boolean): OcrStateTransition {
        if (nativeUsable) return OcrStateTransition(OcrTransitionOutcome.NOT_ELIGIBLE, status)
        val retryable = when (status?.state) {
            OcrPageState.FAILED -> status.failure?.retryable == true
            OcrPageState.CANCELLED -> status.cancellationReason != OcrCancellationReason.NATIVE_TEXT
            else -> false
        }
        if (!retryable) return invalid(status)
        return applied(OcrPageStatus(OcrPageState.QUEUED, status!!.generation + 1))
    }

    fun stale(status: OcrPageStatus): OcrStateTransition =
        applied(OcrPageStatus(OcrPageState.STALE, status.generation + 1))

    private fun generationFence(status: OcrPageStatus?, generation: Long): OcrStateTransition? =
        OcrStateTransition(OcrTransitionOutcome.GENERATION_MISMATCH, status)
            .takeIf { status?.generation != generation }

    private fun nextGeneration(status: OcrPageStatus?): Long = (status?.generation ?: -1L) + 1L
    private fun applied(status: OcrPageStatus) = OcrStateTransition(OcrTransitionOutcome.APPLIED, status)
    private fun unchanged(status: OcrPageStatus) = OcrStateTransition(OcrTransitionOutcome.UNCHANGED, status)
    private fun invalid(status: OcrPageStatus?) = OcrStateTransition(OcrTransitionOutcome.INVALID_STATE, status)
}
