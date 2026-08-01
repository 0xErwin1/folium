package com.folium.reader.library

import com.folium.reader.core.library.DocumentProbeFailure
import com.folium.reader.core.library.DocumentVersion
import com.folium.reader.core.library.LibraryDocumentCandidate
import com.folium.reader.core.library.LibraryLoadResult
import com.folium.reader.core.library.ProviderDocumentIdentity
import com.folium.reader.core.library.RecoveryReason
import com.folium.reader.core.library.RecoveryState
import com.folium.reader.saf.SafCandidateProbe
import com.folium.reader.saf.SafCandidateProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryTest {

    @Test fun loaded_candidates_and_skips_are_forwarded_unchanged() {
        val candidate = LibraryDocumentCandidate(ProviderDocumentIdentity("provider", "doc"), DocumentVersion("1"), "doc.pdf", "application/pdf", true)
        val skip = DocumentProbeFailure(RecoveryState(RecoveryReason.MalformedMetadata))
        val probe = FakeProbe(SafCandidateProbeResult.Candidates(listOf(candidate), listOf(skip)))

        val result = SafLibraryRepository(probe).loadLibrary()

        assertTrue(result is LibraryLoadResult.Loaded)
        assertEquals(listOf(candidate), (result as LibraryLoadResult.Loaded).documents)
        assertEquals(listOf(skip), result.skipped)
    }

    @Test fun probe_failure_becomes_unavailable_with_the_same_recovery_reason() {
        val probe = FakeProbe(SafCandidateProbeResult.Failure(RecoveryState(RecoveryReason.PermissionRevoked)))

        val result = SafLibraryRepository(probe).loadLibrary()

        assertTrue(result is LibraryLoadResult.Unavailable)
        assertEquals(RecoveryReason.PermissionRevoked, (result as LibraryLoadResult.Unavailable).recovery.reason)
    }

    @Test fun empty_root_reports_no_documents_without_a_failure() {
        val probe = FakeProbe(SafCandidateProbeResult.Candidates(emptyList(), emptyList()))

        val result = SafLibraryRepository(probe).loadLibrary()

        assertTrue(result is LibraryLoadResult.Loaded)
        assertTrue((result as LibraryLoadResult.Loaded).documents.isEmpty())
    }

    private class FakeProbe(private val result: SafCandidateProbeResult) : SafCandidateProbe {
        override fun probePdfCandidates(): SafCandidateProbeResult = result
    }
}
