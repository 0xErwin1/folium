package com.folium.reader.library

import androidx.annotation.StringRes
import com.folium.reader.R
import com.folium.reader.core.library.ImportFailure
import com.folium.reader.core.library.RecoveryReason
import com.folium.reader.core.pdf.PdfFailure

/**
 * A picked file's presentation label reduced to what is safe to show: control characters removed and
 * the result trimmed.
 *
 * The label is the picker's, so it is outside data — a name carrying a line break or a NUL would
 * otherwise reach a `Text` verbatim and tear the row it is drawn in. Every surface that shows a
 * label goes through here, both the title an import settles on and the name its failure is reported
 * under, so the same file is never named two different ways. This removes control characters only:
 * a label is still shown as the file was named, not rewritten into something safe to trust.
 */
internal fun sanitizedLabel(label: String): String = label.filterNot { it.isISOControl() }.trim()

/**
 * Maps a per-file [ImportFailure] to the copy the import report shows for it.
 *
 * Deliberately free of Compose so the mapping stays exhaustively unit-testable, and deliberately
 * exhaustive over [ImportFailure], [RecoveryReason] and [PdfFailure] so a newly added member fails
 * to compile here instead of rendering an unexplained blank row.
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
    }

    @StringRes
    private fun notReadable(failure: PdfFailure): Int = when (failure) {
        PdfFailure.Corrupt -> R.string.import_failure_corrupt
        PdfFailure.Unsupported -> R.string.import_failure_unsupported
        PdfFailure.PasswordRequired, PdfFailure.WrongPassword -> R.string.import_failure_protected
        PdfFailure.Closed -> R.string.import_failure_closed
        PdfFailure.TextExtraction -> R.string.import_failure_document_unreadable
        is PdfFailure.Resource -> R.string.import_failure_resource
    }
}
