package com.folium.reader.library

import com.folium.reader.core.library.BookFormat
import com.folium.reader.core.library.BookId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryPathsTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private val paths get() = LibraryPaths(tempFolder.root)
    private val bookId = BookId("a-book")

    @Test fun documentFileIsNamedByItsFormat() {
        assertEquals("document.pdf", paths.documentFile(bookId, BookFormat.PDF).name)
        assertEquals("document.epub", paths.documentFile(bookId, BookFormat.EPUB).name)
    }

    @Test fun stagingDocumentFileIsNamedByItsFormat() {
        assertEquals("document.pdf", paths.stagingDocumentFile("token", BookFormat.PDF).name)
        assertEquals("document.epub", paths.stagingDocumentFile("token", BookFormat.EPUB).name)
    }

    /**
     * A book stored before formats other than PDF existed must resolve to the exact same file it
     * always did, so this identity is what makes the change need no on-disk move.
     */
    @Test fun pdfDocumentFileNameIsUnchangedFromBeforeFormatsExisted() {
        assertEquals("document.pdf", LibraryPaths.documentFileName(BookFormat.PDF))
    }
}
