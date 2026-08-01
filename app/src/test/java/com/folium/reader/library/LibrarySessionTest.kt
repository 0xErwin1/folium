package com.folium.reader.library

import com.folium.reader.core.library.DocumentProbeFailure
import com.folium.reader.core.library.DocumentVersion
import com.folium.reader.core.library.LibraryDocumentCandidate
import com.folium.reader.core.library.LibraryLoadResult
import com.folium.reader.core.library.LibraryRepository
import com.folium.reader.core.library.LibraryRootIdentity
import com.folium.reader.core.library.LibraryState
import com.folium.reader.core.library.PersistedGrantState
import com.folium.reader.core.library.ProviderDocumentIdentity
import com.folium.reader.core.library.RecoveryReason
import com.folium.reader.core.library.RecoveryState
import com.folium.reader.core.library.RootVersion
import com.folium.reader.saf.LibraryRootBinder
import com.folium.reader.saf.SafDiagnosticStage
import com.folium.reader.saf.SafFailure
import com.folium.reader.saf.SafRootResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySessionTest {

    private val root = LibraryRootIdentity("provider.example", "root:books")
    private val candidate = LibraryDocumentCandidate(
        ProviderDocumentIdentity("provider.example", "root:books/8fa1"),
        DocumentVersion("3"),
        "Quarterly report.pdf",
        "application/pdf",
        true
    )

    @Test fun an_unrecoverable_root_never_reaches_the_library_and_keeps_its_reason() {
        RecoveryReason.entries.forEach { reason ->
            val library = RecordingLibrary(LibraryLoadResult.Loaded(listOf(candidate), emptyList()))
            val session = LibrarySession(binder(SafRootResult.Unavailable(SafFailure(RecoveryState(reason), SafDiagnosticStage.RootQuery))), library)

            val state = session.load()

            assertEquals("$reason must not trigger a library query", 0, library.calls)
            when (reason) {
                RecoveryReason.PermissionRevoked -> {
                    assertTrue("$reason produced $state", state is LibraryState.PermissionLost)
                    assertEquals(reason, (state as LibraryState.PermissionLost).recovery.reason)
                }
                else -> {
                    assertTrue("$reason produced $state", state is LibraryState.Error)
                    assertEquals(reason, (state as LibraryState.Error).recovery.reason)
                }
            }
        }
    }

    @Test fun a_bound_root_with_documents_becomes_content_carrying_the_skipped_documents() {
        val skipped = listOf(DocumentProbeFailure(RecoveryState(RecoveryReason.DocumentUnreadable), candidate.identity))
        val session = LibrarySession(binder(ready()), RecordingLibrary(LibraryLoadResult.Loaded(listOf(candidate), skipped)))

        val state = session.load()

        assertTrue(state is LibraryState.Content)
        assertEquals(listOf(candidate), (state as LibraryState.Content).documents)
        assertEquals(skipped, state.skipped)
    }

    @Test fun a_bound_root_whose_documents_all_failed_becomes_empty_but_still_reports_them() {
        val skipped = listOf(DocumentProbeFailure(RecoveryState(RecoveryReason.MalformedMetadata), candidate.identity))
        val session = LibrarySession(binder(ready()), RecordingLibrary(LibraryLoadResult.Loaded(emptyList(), skipped)))

        val state = session.load()

        assertTrue(state is LibraryState.Empty)
        assertEquals(skipped, (state as LibraryState.Empty).skipped)
    }

    @Test fun a_bound_root_that_becomes_unreadable_during_enumeration_surfaces_that_failure() {
        val session = LibrarySession(
            binder(ready()),
            RecordingLibrary(LibraryLoadResult.Unavailable(RecoveryState(RecoveryReason.PermissionRevoked)))
        )

        val state = session.load()

        assertTrue(state is LibraryState.PermissionLost)
        assertEquals(RecoveryReason.PermissionRevoked, (state as LibraryState.PermissionLost).recovery.reason)
    }

    private fun ready() = SafRootResult.Ready(PersistedGrantState(root, RootVersion("1"), true))

    private fun binder(result: SafRootResult) = object : LibraryRootBinder {
        override fun recover(): SafRootResult = result
    }

    private class RecordingLibrary(private val result: LibraryLoadResult) : LibraryRepository {
        var calls: Int = 0
        override fun loadLibrary(): LibraryLoadResult {
            calls++
            return result
        }
    }
}
