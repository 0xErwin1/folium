package com.folium.reader.library

import com.folium.reader.core.library.DocumentProbeFailure
import com.folium.reader.core.library.DocumentVersion
import com.folium.reader.core.library.LibraryDocumentCandidate
import com.folium.reader.core.library.LibraryLoadResult
import com.folium.reader.core.library.LibraryState
import com.folium.reader.core.library.LibraryStateReducer
import com.folium.reader.core.library.ProviderDocumentIdentity
import com.folium.reader.core.library.RecoveryReason
import com.folium.reader.core.library.RecoveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryStateReducerTest {

    private val expectedTerminalStateByReason: Map<RecoveryReason, Class<out LibraryState>> = mapOf(
        RecoveryReason.RootNotSelected to LibraryState.Error::class.java,
        RecoveryReason.PermissionRevoked to LibraryState.PermissionLost::class.java,
        RecoveryReason.ProviderUnavailable to LibraryState.Error::class.java,
        RecoveryReason.RootOrDocumentMissing to LibraryState.Error::class.java,
        RecoveryReason.MalformedMetadata to LibraryState.Error::class.java,
        RecoveryReason.UnsupportedMetadata to LibraryState.Error::class.java,
        RecoveryReason.TransientQueryFailure to LibraryState.Error::class.java,
        RecoveryReason.DocumentUnreadable to LibraryState.Error::class.java
    )

    @Test fun no_result_yet_reduces_to_loading() {
        assertEquals(LibraryState.Loading, LibraryStateReducer.reduce(null))
    }

    @Test fun loaded_with_no_documents_reduces_to_empty_even_with_skipped_entries() {
        val skipped = listOf(DocumentProbeFailure(RecoveryState(RecoveryReason.MalformedMetadata)))
        val result = LibraryLoadResult.Loaded(documents = emptyList(), skipped = skipped)

        assertEquals(LibraryState.Empty, LibraryStateReducer.reduce(result))
    }

    @Test fun loaded_with_documents_reduces_to_content_preserving_order() {
        val first = LibraryDocumentCandidate(ProviderDocumentIdentity("provider", "a"), DocumentVersion("1"), "application/pdf", true)
        val second = LibraryDocumentCandidate(ProviderDocumentIdentity("provider", "b"), DocumentVersion("2"), "application/pdf", true)
        val result = LibraryLoadResult.Loaded(documents = listOf(first, second), skipped = emptyList())

        val state = LibraryStateReducer.reduce(result)

        assertTrue(state is LibraryState.Content)
        assertEquals(listOf(first, second), (state as LibraryState.Content).documents)
    }

    @Test fun permission_revoked_is_the_only_reason_mapped_to_permission_lost() {
        assertEquals(
            "expectedTerminalStateByReason must cover every RecoveryReason so a newly added reason fails this test rather than going uncovered",
            RecoveryReason.entries.toSet(),
            expectedTerminalStateByReason.keys
        )

        RecoveryReason.entries.forEach { reason ->
            val recovery = RecoveryState(reason)
            val expectedType = requireNotNull(expectedTerminalStateByReason[reason]) { "No expected state mapped for $reason" }
            val state = LibraryStateReducer.reduce(LibraryLoadResult.Unavailable(recovery))
            assertEquals("$reason must reduce to $expectedType", expectedType, state.javaClass)

            when (state) {
                is LibraryState.PermissionLost -> assertEquals(recovery, state.recovery)
                is LibraryState.Error -> assertEquals(recovery, state.recovery)
                else -> throw AssertionError("Unexpected state type for $reason: $state")
            }
        }
    }
}
