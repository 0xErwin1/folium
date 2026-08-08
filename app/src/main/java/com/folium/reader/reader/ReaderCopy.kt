package com.folium.reader.reader

import androidx.annotation.StringRes
import com.folium.reader.R
import com.folium.reader.core.pdf.PdfFailure

/**
 * Maps typed document failures to the copy the reader sees.
 *
 * Exhaustive over [PdfFailure] on purpose: a newly added failure has to be given words here before
 * it will compile, rather than reaching a reader as an unexplained blank page.
 */
object ReaderCopy {

    @StringRes
    fun title(failure: PdfFailure?): Int = when (failure) {
        PdfFailure.Corrupt -> R.string.reader_failure_title_corrupt
        PdfFailure.Unsupported -> R.string.reader_failure_title_unsupported
        PdfFailure.PasswordRequired, PdfFailure.WrongPassword -> R.string.reader_failure_title_protected
        PdfFailure.Closed -> R.string.reader_failure_title_closed
        PdfFailure.TextExtraction -> R.string.reader_failure_title_unknown
        is PdfFailure.Resource -> R.string.reader_failure_title_resource
        null -> R.string.reader_failure_title_unknown
    }

    @StringRes
    fun body(failure: PdfFailure?): Int = when (failure) {
        PdfFailure.Corrupt -> R.string.reader_failure_body_corrupt
        PdfFailure.Unsupported -> R.string.reader_failure_body_unsupported
        PdfFailure.PasswordRequired, PdfFailure.WrongPassword -> R.string.reader_failure_body_protected
        PdfFailure.Closed -> R.string.reader_failure_body_closed
        PdfFailure.TextExtraction -> R.string.reader_failure_body_unknown
        is PdfFailure.Resource -> R.string.reader_failure_body_resource
        null -> R.string.reader_failure_body_unknown
    }
}
