package com.folium.reader.core.library

import com.folium.reader.core.pdf.PdfFailure

/**
 * Why a single import failed, typed by the stage that rejected it: reading the picked source,
 * making sense of the copy as a document, or writing it into app storage.
 */
sealed class ImportFailure {
    data class SourceUnavailable(val reason: RecoveryReason) : ImportFailure()
    data class NotReadable(val failure: PdfFailure) : ImportFailure()
    data object StorageUnavailable : ImportFailure()
}

/** The per-file result of one import attempt. [label] is always present, success or failure. */
sealed class ImportOutcome {
    abstract val label: String

    data class Imported(override val label: String, val book: LibraryBook) : ImportOutcome()
    data class Failed(override val label: String, val failure: ImportFailure) : ImportOutcome()
}

/** The full result of an import batch, one [ImportOutcome] per selected file, in selection order. */
data class ImportReport(val outcomes: List<ImportOutcome>) {
    val importedCount: Int get() = outcomes.count { it is ImportOutcome.Imported }
    val failures: List<ImportOutcome.Failed> get() = outcomes.filterIsInstance<ImportOutcome.Failed>()
}
