package com.folium.reader.library

import androidx.annotation.StringRes
import com.folium.reader.R
import com.folium.reader.core.library.ImportFailure
import com.folium.reader.core.library.RecoveryReason
import com.folium.reader.core.pdf.PdfFailure

/**
 * Maps a per-file [ImportFailure] to the copy the import report shows for it.
 *
 * Deliberately free of Compose so the mapping stays exhaustively unit-testable, and deliberately
 * exhaustive over [ImportFailure], [RecoveryReason] and [PdfFailure] so a newly added member fails
 * to compile here instead of rendering an unexplained blank row — the same discipline [LibraryCopy]
 * already applies to the tree-grant vocabulary.
 */
object ImportCopy {

    @StringRes
    fun explanation(failure: ImportFailure): Int = when (failure) {
        is ImportFailure.SourceUnavailable -> sourceUnavailable(failure.reason)
        is ImportFailure.NotReadable -> notReadable(failure.failure)
        ImportFailure.StorageUnavailable -> R.string.import_failure_storage_unavailable
    }

    @StringRes
    private fun sourceUnavailable(reason: RecoveryReason): Int = when (reason) {
        RecoveryReason.PermissionRevoked -> R.string.import_failure_permission_revoked
        RecoveryReason.ProviderUnavailable -> R.string.import_failure_provider_unavailable
        RecoveryReason.SourceMissing -> R.string.import_failure_source_missing
        RecoveryReason.TransientQueryFailure -> R.string.import_failure_transient
        RecoveryReason.DocumentUnreadable -> R.string.import_failure_document_unreadable

        RecoveryReason.RootNotSelected,
        RecoveryReason.RootOrDocumentMissing,
        RecoveryReason.MalformedMetadata,
        RecoveryReason.UnsupportedMetadata ->
            error("$reason cannot be produced by DocumentCopy's exception catch table")
    }

    @StringRes
    private fun notReadable(failure: PdfFailure): Int = when (failure) {
        PdfFailure.Corrupt -> R.string.import_failure_corrupt
        PdfFailure.Unsupported -> R.string.import_failure_unsupported
        PdfFailure.PasswordRequired, PdfFailure.WrongPassword -> R.string.import_failure_protected
        PdfFailure.Closed -> R.string.import_failure_closed
        is PdfFailure.Resource -> R.string.import_failure_resource
    }
}
