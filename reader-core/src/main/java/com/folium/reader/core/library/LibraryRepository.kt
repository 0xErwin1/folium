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
    object Empty : LibraryState()
    data class Content(val documents: List<LibraryDocumentCandidate>) : LibraryState()
    data class PermissionLost(val recovery: RecoveryState) : LibraryState()
    data class Error(val recovery: RecoveryState) : LibraryState()
}

/**
 * Pure mapping from a repository load outcome to the UI-facing library state.
 * `null` represents "no result yet" (the load is in flight).
 */
object LibraryStateReducer {
    fun reduce(result: LibraryLoadResult?): LibraryState = when (result) {
        null -> LibraryState.Loading
        is LibraryLoadResult.Loaded ->
            if (result.documents.isEmpty()) LibraryState.Empty else LibraryState.Content(result.documents)
        is LibraryLoadResult.Unavailable ->
            if (result.recovery.reason == RecoveryReason.PermissionRevoked) {
                LibraryState.PermissionLost(result.recovery)
            } else {
                LibraryState.Error(result.recovery)
            }
    }
}
