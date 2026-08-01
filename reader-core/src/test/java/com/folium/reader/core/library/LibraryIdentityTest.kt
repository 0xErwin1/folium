package com.folium.reader.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIdentityTest {
    @Test fun stable_identities_ignore_rename_and_version_while_versions_remain_explicit() {
        val documentId = "root:books/report"
        val beforeRename = ProviderDocumentIdentity("provider.example", documentId)
        val afterRenameSameDocument = ProviderDocumentIdentity("provider.example", documentId)
        val beforeVersionBump = DocumentVersion("7")
        val afterVersionBump = DocumentVersion("8")

        assertEquals(beforeRename, afterRenameSameDocument)
        assertEquals(beforeRename.hashCode(), afterRenameSameDocument.hashCode())
        assertNotEquals(beforeVersionBump, afterVersionBump)
        assertNotEquals(RootVersion("1"), RootVersion("2"))

        val sameAuthorityDifferentDocument = ProviderDocumentIdentity("provider.example", "root:books/other")
        val differentAuthoritySameDocument = ProviderDocumentIdentity("other.provider", documentId)
        assertNotEquals(beforeRename, sameAuthorityDifferentDocument)
        assertNotEquals(beforeRename, differentAuthoritySameDocument)

        val root = LibraryRootIdentity("provider.example", "root:books")
        val sameRootDifferentAuthority = LibraryRootIdentity("other.provider", "root:books")
        val sameAuthorityDifferentRoot = LibraryRootIdentity("provider.example", "root:other")
        assertNotEquals(root, sameRootDifferentAuthority)
        assertNotEquals(root, sameAuthorityDifferentRoot)
    }

    @Test fun malformed_identity_parts_are_rejected() {
        assertFails { LibraryRootIdentity("", "root") }
        assertFails { ProviderDocumentIdentity("provider", "document\n") }
        assertFails { RootVersion("") }
        assertFails { DocumentVersion("") }
    }

    @Test fun candidate_filter_accepts_only_openable_regular_pdfs() {
        assertTrue(LibraryCandidateFilter.accepts(CandidateMetadata("application/pdf", false, true)))
        assertFalse(LibraryCandidateFilter.accepts(CandidateMetadata("application/pdf", true, true)))
        assertFalse(LibraryCandidateFilter.accepts(CandidateMetadata("text/plain", false, true)))
        assertFalse(LibraryCandidateFilter.accepts(CandidateMetadata("application/pdf", false, false)))
    }

    @Test fun candidate_and_failure_models_are_neutral_and_typed() {
        val candidate = LibraryDocumentCandidate(ProviderDocumentIdentity("provider", "document"), DocumentVersion("3"), "Report.pdf", "application/pdf", true)
        val unreadable = DocumentProbeFailure(RecoveryState(RecoveryReason.DocumentUnreadable), candidate.identity)
        assertEquals("application/pdf", candidate.mimeType)
        assertEquals(RecoveryAction.SkipDocument, unreadable.recovery.action)
    }

    @Test fun candidates_carry_a_presentable_name_independent_of_their_stable_identity() {
        val identity = ProviderDocumentIdentity("provider.example", "root:books/8fa1")
        val candidate = LibraryDocumentCandidate(identity, DocumentVersion("3"), "Quarterly report.pdf", "application/pdf", true)

        assertEquals("Quarterly report.pdf", candidate.displayName)
        assertNotEquals(candidate.displayName, candidate.identity.documentId)

        val renamed = candidate.copy(displayName = "Q3 report.pdf")
        assertEquals(candidate.identity, renamed.identity)
        assertNotEquals(candidate.displayName, renamed.displayName)

        val blank = LibraryDocumentCandidate(identity, DocumentVersion("3"), "   ", "application/pdf", true)
        assertEquals(identity, blank.identity)
    }

    @Test fun recovery_is_typed_and_neutral() {
        assertEquals(RecoveryAction.SelectRoot, RecoveryState(RecoveryReason.RootNotSelected).action)
        assertEquals(RecoveryAction.RebindRoot, RecoveryState(RecoveryReason.PermissionRevoked).action)
        assertEquals(RecoveryAction.Retry, RecoveryState(RecoveryReason.TransientQueryFailure).action)
    }

    private fun assertFails(block: () -> Unit) {
        try { block(); throw AssertionError("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) { }
    }
}
