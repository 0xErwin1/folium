package com.folium.reader.reader

import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.PdfEngine
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.Raster
import com.folium.reader.core.pdf.RenderSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class DocumentFakeDisplayList : DisplayList {
    override fun render(spec: RenderSpec, cancellationSignal: CancellationSignal) = Raster(1, 1, byteArrayOf(0, 0, 0, 0))
    override fun close() = Unit
}

private class DocumentFakePdfDocument(
    override val pageCount: Int,
    private val pageWidth: Float = 100f,
    private val pageHeight: Float = 200f,
    private val outlineEntries: List<OutlineEntry> = emptyList(),
    private val outlineThrows: Boolean = false,
    private val pageHeightAt: (Int) -> Float = { pageHeight }
) : PdfDocument {
    var closed = false
        private set

    override fun pageInfo(index: Int) = PageInfo(index, pageWidth, pageHeightAt(index), 0)
    override fun buildDisplayList(index: Int): DisplayList = DocumentFakeDisplayList()
    override fun extractText(index: Int): String = ""

    override fun outline(): List<OutlineEntry> {
        if (outlineThrows) throw PdfException(PdfFailure.Unsupported)
        return outlineEntries
    }

    override fun close() {
        closed = true
    }
}

private class DocumentFakeEngine(private val document: PdfDocument) : PdfEngine {
    override fun open(source: PdfSource): PdfDocument = document
}

class ReaderDocumentTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val bookId = BookId("book-1")

    private fun file(name: String = "document.pdf", write: Boolean = true): File {
        val file = temporaryFolder.newFile(name)
        if (write) file.writeBytes(byteArrayOf(1, 2, 3))
        return file
    }

    @Test fun `a missing file is reported without touching the engine`() {
        val missing = File(temporaryFolder.root, "does-not-exist.pdf")
        var engineCalled = false
        val engine = object : PdfEngine {
            override fun open(source: PdfSource): PdfDocument {
                engineCalled = true
                error("must not be called for a missing file")
            }
        }

        val result = ReaderDocument.open(missing, bookId, 0, engine)

        assertTrue(result is ReaderDocumentResult.Missing)
        assertTrue(!engineCalled)
    }

    @Test fun `a non-positive page count is reported as corrupt`() {
        val engine = DocumentFakeEngine(DocumentFakePdfDocument(pageCount = 0))

        val result = ReaderDocument.open(file(), bookId, 0, engine)

        assertTrue(result is ReaderDocumentResult.Unreadable)
        assertEquals(PdfFailure.Corrupt, (result as ReaderDocumentResult.Unreadable).failure)
    }

    @Test fun `a document whose outline cannot be read still opens with an empty outline`() {
        val fake = DocumentFakePdfDocument(pageCount = 5, outlineThrows = true)
        val engine = DocumentFakeEngine(fake)

        val result = ReaderDocument.open(file(), bookId, 0, engine)

        assertTrue(result is ReaderDocumentResult.Opened)
        assertEquals(emptyList<OutlineEntry>(), (result as ReaderDocumentResult.Opened).document.outline)
        assertTrue(!fake.closed)
    }

    @Test fun `a document with a readable outline exposes it verbatim`() {
        val outline = listOf(OutlineEntry("Chapter 1", 0), OutlineEntry("Chapter 2", 10))
        val engine = DocumentFakeEngine(DocumentFakePdfDocument(pageCount = 20, outlineEntries = outline))

        val result = ReaderDocument.open(file(), bookId, 0, engine)

        assertTrue(result is ReaderDocumentResult.Opened)
        assertEquals(outline, (result as ReaderDocumentResult.Opened).document.outline)
    }

    @Test fun `an out-of-range initial page is clamped to the last page`() {
        val engine = DocumentFakeEngine(DocumentFakePdfDocument(pageCount = 10))

        val result = ReaderDocument.open(file(), bookId, 9999, engine)

        assertTrue(result is ReaderDocumentResult.Opened)
        val document = (result as ReaderDocumentResult.Opened).document
        assertEquals(document.aspect(9), document.aspect(9999))
    }

    @Test fun `the initial page is measured and seeded before any page is recorded`() {
        val fake = DocumentFakePdfDocument(pageCount = 1000, pageHeightAt = { index -> if (index == 900) 400f else 200f })
        val engine = DocumentFakeEngine(fake)

        val result = ReaderDocument.open(file(), bookId, 900, engine)

        assertTrue(result is ReaderDocumentResult.Opened)
        val document = (result as ReaderDocumentResult.Opened).document

        assertEquals(100f / 200f, document.aspect(0))
        assertEquals(100f / 400f, document.aspect(900))
    }

    @Test fun `documentId is bookId's own value`() {
        val engine = DocumentFakeEngine(DocumentFakePdfDocument(pageCount = 3))

        val result = ReaderDocument.open(file(), bookId, 0, engine)

        assertTrue(result is ReaderDocumentResult.Opened)
        assertEquals(bookId, (result as ReaderDocumentResult.Opened).document.bookId)
    }

    @Test fun `close does not delete the file it was opened from`() {
        val documentFile = file()
        val engine = DocumentFakeEngine(DocumentFakePdfDocument(pageCount = 3))

        val result = ReaderDocument.open(documentFile, bookId, 0, engine)
        assertTrue(result is ReaderDocumentResult.Opened)

        (result as ReaderDocumentResult.Opened).document.close()

        assertTrue("the reader owns no scratch file and must not delete the caller's copy", documentFile.exists())
    }
}
