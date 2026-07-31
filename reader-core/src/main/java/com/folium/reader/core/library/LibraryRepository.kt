package com.folium.reader.core.library

sealed class LibraryLoadResult {
    data class Loaded(
        val documents: List<LibraryDocumentCandidate>,
        val skipped: List<DocumentProbeFailure>
    ) : LibraryLoadResult()

    data class Unavailable(val recovery: RecoveryState) : LibraryLoadResult()
}

interface LibraryRepository {
    fun loadLibrary(): LibraryLoadResult
}

sealed class LibraryState {
    object Loading : LibraryState()
    data class Empty(val skipped: List<DocumentProbeFailure>) : LibraryState()
    data class Content(
        val documents: List<LibraryDocumentCandidate>,
        val skipped: List<DocumentProbeFailure>
    ) : LibraryState()
    data class PermissionLost(val recovery: RecoveryState) : LibraryState()
    data class Error(val recovery: RecoveryState) : LibraryState()
}

/**
 * Pure mapping from a repository load outcome to the UI-facing library state.
 * `null` represents "no result yet" (the load is in flight).
 *
 * A root whose PDFs all fail to open still reaches [LibraryState.Empty], distinguished from a
 * genuinely empty root by its non-empty [LibraryState.Empty.skipped] list.
 */
object LibraryStateReducer {
    fun reduce(result: LibraryLoadResult?): LibraryState = when (result) {
        null -> LibraryState.Loading
        is LibraryLoadResult.Loaded ->
            if (result.documents.isEmpty()) {
                LibraryState.Empty(result.skipped)
            } else {
                LibraryState.Content(result.documents, result.skipped)
            }
        is LibraryLoadResult.Unavailable ->
            if (result.recovery.reason == RecoveryReason.PermissionRevoked) {
                LibraryState.PermissionLost(result.recovery)
            } else {
                LibraryState.Error(result.recovery)
            }
    }
}
