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
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextEngineVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val pageHeightAt: (Int) -> Float = { pageHeight },
    private val reflowableValue: Boolean = false
) : PdfDocument {
    var closed = false
        private set

    val queriedIndices = mutableListOf<Int>()

    var reflowableReads = 0
        private set

    override val reflowable: Boolean get() {
        reflowableReads++
        return reflowableValue
    }

    override fun pageInfo(index: Int): PageInfo {
        queriedIndices += index
        return PageInfo(index, pageWidth, pageHeightAt(index), 0)
    }

    override fun buildDisplayList(index: Int): DisplayList = DocumentFakeDisplayList()
    override fun extractText(index: Int): TextPage = TextPage(emptyList(), TextSource.NATIVE_PDF)

    override fun outline(): List<OutlineEntry> {
        if (outlineThrows) throw PdfException(PdfFailure.Unsupported)
        return outlineEntries
    }

    override fun metadata() = com.folium.reader.core.pdf.DocumentMetadata.NONE

    override fun close() {
        closed = true
    }
}

private class DocumentFakeEngine(private val document: PdfDocument) : PdfEngine {
    override val textEngineVersion = TextEngineVersion("test-pdf")
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
            override val textEngineVersion = TextEngineVersion("test-pdf")
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
        val fake = DocumentFakePdfDocument(pageCount = 10, pageHeightAt = { index -> if (index == 9) 400f else 200f })
        val engine = DocumentFakeEngine(fake)

        val result = ReaderDocument.open(file(), bookId, 9999, engine)

        assertTrue(result is ReaderDocumentResult.Opened)
        val document = (result as ReaderDocumentResult.Opened).document
        val firstPageAspect = 100f / 200f
        val lastPageAspect = 100f / 400f

        // page 9 is what the clamp must actually seed — its own, distinct aspect, not page 0's.
        assertEquals(lastPageAspect, document.aspect(9))
        // aspect()'s own fallback-to-0 contract is unrelated to clamping: an index nobody seeded
        // (9999, since the clamp must have redirected the open-time query to 9) still falls back
        // to page 0's aspect, which here is deliberately different from page 9's.
        assertEquals(firstPageAspect, document.aspect(9999))
        assertTrue(
            "pageInfo must never be queried with an index outside the document, saw ${fake.queriedIndices}",
            fake.queriedIndices.all { it < fake.pageCount }
        )
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

    /**
     * The file format decides whether a document is reflowable, and that cannot change while it
     * stays open, so opening it must read the engine's own answer exactly once rather than queue
     * behind the document lock for it again on every later ask.
     */
    @Test fun `reflowable is read once at open and answered from the cached value after`() {
        val fake = DocumentFakePdfDocument(pageCount = 5, reflowableValue = true)
        val engine = DocumentFakeEngine(fake)

        val result = ReaderDocument.open(file(), bookId, 0, engine)

        assertTrue(result is ReaderDocumentResult.Opened)
        val document = (result as ReaderDocumentResult.Opened).document
        assertTrue(document.reflowable)
        assertTrue(document.reflowable)
        assertEquals(1, fake.reflowableReads)
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

    /**
     * A page's shape is a property of the document, not of the render that happened to notice it,
     * so measuring it twice is work with no result. It was being paid on every render of every
     * page: about a fifth of what a whole-page preview costs, forever, for an answer already held.
     */
    @Test fun `a page whose shape is already known is not measured again`() {
        val fake = DocumentFakePdfDocument(pageCount = 10)
        val document = opened(fake, initialPage = 0)
        var measurements = 0
        val measure = { _: Int -> measurements++; 0.5f }

        document.measureIfUnknown(4, measure)
        document.measureIfUnknown(4, measure)
        document.measureIfUnknown(4, measure)

        assertEquals(1, measurements)
    }

    @Test fun `a page the reader was seeded with is never measured at all`() {
        val fake = DocumentFakePdfDocument(pageCount = 10)
        val document = opened(fake, initialPage = 7)
        var measurements = 0

        document.measureIfUnknown(0, { _ -> measurements++; 0.5f })
        document.measureIfUnknown(7, { _ -> measurements++; 0.5f })

        assertEquals(0, measurements)
    }

    /**
     * The signal the reader corrects its layout on is unchanged: only a first measurement that
     * contradicts the shape being assumed is worth a relayout.
     */
    @Test fun `only a first measurement that contradicts the assumed shape asks for a relayout`() {
        val fake = DocumentFakePdfDocument(pageCount = 10)
        val document = opened(fake, initialPage = 0)

        assertTrue(document.measureIfUnknown(3) { 2f })
        assertFalse(document.measureIfUnknown(3) { 9f })
        assertFalse(document.measureIfUnknown(5) { document.aspect(0) })
    }

    private fun opened(fake: DocumentFakePdfDocument, initialPage: Int): ReaderDocument {
        val result = ReaderDocument.open(file(), bookId, initialPage, DocumentFakeEngine(fake))
        return (result as ReaderDocumentResult.Opened).document
    }

    /**
     * The two tiers render the same page at the same moment, so both arrive here at once. Measuring
     * is the expensive half of a render on a document whose pages are costly to parse — 60 to 80ms
     * on CAD plans — and paying it twice for one answer is the whole of what this is here to avoid.
     */
    @Test fun `two renders arriving together measure a page once between them`() {
        val fake = DocumentFakePdfDocument(pageCount = 10)
        val document = opened(fake, initialPage = 0)
        val measurements = java.util.concurrent.atomic.AtomicInteger()
        val start = java.util.concurrent.CountDownLatch(1)
        val measure = { _: Int ->
            measurements.incrementAndGet()
            Thread.sleep(50)
            0.5f
        }

        val threads = (1..4).map {
            Thread {
                start.await()
                document.measureIfUnknown(6, measure)
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join(10_000) }

        assertEquals(1, measurements.get())
    }
}
