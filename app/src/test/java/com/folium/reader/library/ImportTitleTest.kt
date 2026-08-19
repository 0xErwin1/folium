package com.folium.reader.library

import com.folium.reader.core.pdf.DocumentMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The title a book shows is the one thing on the shelf a reader reads, and until now it was always
 * the filename — slugged, URL-encoded, extension-bearing. These pin which of the two sources wins.
 */
class ImportTitleTest {
    @Test fun `a declared title beats the file name`() {
        assertEquals(
            "Building Microservices",
            title(DocumentMetadata(title = "Building Microservices"), "building-microservices-2e.pdf")
        )
    }

    @Test fun `a document with nothing to declare keeps the sanitized file name`() {
        assertEquals("pan-sin-amasar.pdf", title(DocumentMetadata.NONE, "pan-sin-amasar.pdf"))
        assertEquals("book.pdf", title(DocumentMetadata.NONE, "/storage/emulated/0/Download/book.pdf"))
    }

    /**
     * Some producers write the file name into the title field. Preferring it there would look like
     * the app had learned something when it had not.
     */
    @Test fun `a title that only restates the file name loses to it`() {
        assertEquals("cocina.pdf", title(DocumentMetadata(title = "cocina"), "cocina.pdf"))
        assertEquals("cocina.pdf", title(DocumentMetadata(title = "COCINA.PDF"), "cocina.pdf"))
    }

    /** A blank title never reaches the importer: the type refuses to hold one. */
    @Test fun `a real declaration survives a file name that looks nothing like it`() {
        assertEquals("A Real Title", title(DocumentMetadata(title = "A Real Title"), "real-name.pdf"))
    }

    @Test fun `metadata rejects a blank field rather than storing one`() {
        listOf<(String) -> DocumentMetadata>(
            { DocumentMetadata(title = it) },
            { DocumentMetadata(author = it) },
            { DocumentMetadata(producer = it) }
        ).forEach { build ->
            runCatching { build("  ") }.exceptionOrNull().let { failure ->
                assertEquals(IllegalArgumentException::class.java, failure?.javaClass)
            }
        }
    }

    private fun title(metadata: DocumentMetadata, label: String): String =
        bookTitle(metadata, label)
}
