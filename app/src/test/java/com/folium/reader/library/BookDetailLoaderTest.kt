package com.folium.reader.library

import com.folium.reader.core.library.BookFormat
import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.DocumentMetadata
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor

class BookDetailLoaderTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private val direct = Executor(Runnable::run)
    private val book = BookId("a-book")

    @Test fun `a book reports its author and its contents flattened with their depth`() {
        val detail = load(
            DetailFakeDocument(
                metadata = DocumentMetadata(title = "Building Microservices", author = "Sam Newman"),
                outline = listOf(
                    OutlineEntry("Preface", 18, listOf(OutlineEntry("Who Should Read This Book", 18)))
                )
            )
        )

        assertEquals("Sam Newman", detail.metadata.author)
        assertEquals(listOf("Preface", "Who Should Read This Book"), detail.contents.map { it.title })
        assertEquals(listOf(0, 1), detail.contents.map { it.depth })
        assertFalse(detail.unreadable)
    }

    @Test fun `a document that declares nothing is still a readable detail`() {
        val detail = load(DetailFakeDocument())

        assertEquals(DocumentMetadata.NONE, detail.metadata)
        assertEquals(emptyList<Any>(), detail.contents)
        assertFalse(detail.unreadable)
    }

    /**
     * A shelf entry whose file has rotted still has to show its title, its position and a way to
     * remove it, so a failure to open is a state of the screen rather than a crash.
     */
    @Test fun `a file that will not open is reported rather than thrown`() {
        val detail = load(null)

        assertTrue(detail.unreadable)
        assertEquals(emptyList<Any>(), detail.contents)
    }

    /** One half failing must not cost the other: a broken outline still leaves the author. */
    @Test fun `an outline that throws does not take the metadata with it`() {
        val detail = load(
            DetailFakeDocument(metadata = DocumentMetadata(author = "Cervantes"), outlineThrows = true)
        )

        assertEquals("Cervantes", detail.metadata.author)
        assertEquals(emptyList<Any>(), detail.contents)
        assertFalse(detail.unreadable)
    }

    @Test fun `the document is closed however it is read`() {
        val readable = DetailFakeDocument()
        load(readable)
        assertTrue(readable.closed)

        val broken = DetailFakeDocument(outlineThrows = true)
        load(broken)
        assertTrue(broken.closed)
    }

    private fun load(document: DetailFakeDocument?): BookDetail {
        var loaded: BookDetail? = null
        BookDetailLoader(
            paths = LibraryPaths(tempFolder.root),
            engine = DetailFakeEngine(document),
            worker = direct,
            main = direct
        ).load(book, BookFormat.PDF) { loaded = it }
        return requireNotNull(loaded)
    }
}

private class DetailFakeEngine(private val document: DetailFakeDocument?) : PdfEngine {
    override val textEngineVersion = com.folium.reader.core.text.TextEngineVersion("detail-fake")
    override fun open(source: PdfSource): PdfDocument =
        document ?: throw PdfException(PdfFailure.Corrupt)
}

private class DetailFakeDocument(
    private val metadata: DocumentMetadata = DocumentMetadata.NONE,
    private val outline: List<OutlineEntry> = emptyList(),
    private val outlineThrows: Boolean = false
) : PdfDocument {
    var closed = false
        private set

    override val pageCount = 1
    override fun pageInfo(index: Int) = PageInfo(index, 100f, 200f, 0)
    override fun buildDisplayList(index: Int): DisplayList = object : DisplayList {
        override fun render(spec: RenderSpec, cancellationSignal: CancellationSignal) = Raster(1, 1, ByteArray(4))
        override fun close() = Unit
    }
    override fun extractText(index: Int) = TextPage(emptyList(), TextSource.NATIVE_PDF)
    override fun metadata() = this.metadata
    override fun outline(): List<OutlineEntry> {
        if (outlineThrows) throw PdfException(PdfFailure.Unsupported)
        return outline
    }
    override fun close() { closed = true }
}
