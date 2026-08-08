package com.folium.reader.reader

import com.folium.reader.R
import com.folium.reader.core.pdf.PdfFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderCopyTest {
    @Test fun textExtractionUsesExistingUnknownDocumentCopy() {
        assertEquals(R.string.reader_failure_title_unknown, ReaderCopy.title(PdfFailure.TextExtraction))
        assertEquals(R.string.reader_failure_body_unknown, ReaderCopy.body(PdfFailure.TextExtraction))
    }
}
