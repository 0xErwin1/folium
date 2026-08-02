package com.folium.reader.library

import com.folium.reader.core.library.ImportFailure
import com.folium.reader.core.library.RecoveryReason
import com.folium.reader.core.pdf.PdfFailure
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportCopyTest {

    private val sourceUnavailableReasons = listOf(
        RecoveryReason.PermissionRevoked,
        RecoveryReason.ProviderUnavailable,
        RecoveryReason.SourceMissing,
        RecoveryReason.TransientQueryFailure,
        RecoveryReason.DocumentUnreadable
    )

    private val notReadableFailures = listOf(
        PdfFailure.Corrupt,
        PdfFailure.Unsupported,
        PdfFailure.PasswordRequired,
        PdfFailure.WrongPassword,
        PdfFailure.Closed,
        PdfFailure.Resource(retryable = true)
    )

    @Test fun every_source_unavailable_reason_has_its_own_explanation() {
        val explanations = sourceUnavailableReasons.associateWith {
            ImportCopy.explanation(ImportFailure.SourceUnavailable(it))
        }

        assertTrue("no explanation may be unresolved", explanations.values.none { it == 0 })
        assertTrue(
            "each reason needs its own explanation, not a shared catch-all",
            explanations.size == explanations.values.toSet().size
        )
    }

    @Test fun every_not_readable_failure_has_a_resolved_explanation() {
        val explanations = notReadableFailures.map { ImportCopy.explanation(ImportFailure.NotReadable(it)) }

        assertTrue("no explanation may be unresolved", explanations.none { it == 0 })
    }

    @Test fun storage_unavailable_has_its_own_explanation_distinct_from_every_other_failure() {
        val storage = ImportCopy.explanation(ImportFailure.StorageUnavailable)
        val others = sourceUnavailableReasons.map { ImportCopy.explanation(ImportFailure.SourceUnavailable(it)) } +
            notReadableFailures.map { ImportCopy.explanation(ImportFailure.NotReadable(it)) }

        assertTrue(storage != 0)
        assertTrue("storage-unavailable copy must not collide with any other failure's copy", storage !in others)
    }
}
