package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.ImportFailure
import com.folium.reader.core.library.ImportOutcome
import com.folium.reader.core.library.RecoveryReason
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
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
import java.io.FileNotFoundException
import java.io.IOException

private val FIXTURE_BYTES = byteArrayOf(1, 2, 3, 4)

private class FakeDisplayList : DisplayList {
    var closed = false
    override fun render(spec: RenderSpec, cancellationSignal: CancellationSignal): Raster =
        Raster(1, 1, byteArrayOf(0, 0, 0, 0))
    override fun close() { closed = true }
}

private class FakeDocument(override val pageCount: Int) : PdfDocument {
    var closed = false
    var lastDisplayList: FakeDisplayList? = null
    override fun pageInfo(index: Int) = PageInfo(index, 100f, 200f, 0)
    override fun buildDisplayList(index: Int): DisplayList = FakeDisplayList().also { lastDisplayList = it }
    override fun extractText(index: Int): TextPage = TextPage(emptyList(), TextSource.NATIVE_PDF)
    override fun outline() = emptyList<com.folium.reader.core.pdf.OutlineEntry>()
    override fun close() { closed = true }
}

private class FakeEngine(
    private val pageCount: Int = 3,
    private val openFailure: PdfException? = null
) : PdfEngine {
    override val textEngineVersion = TextEngineVersion("test-pdf")
    var lastOpened: PdfSource? = null
    var lastDocument: FakeDocument? = null

    override fun open(source: PdfSource): PdfDocument {
        lastOpened = source
        openFailure?.let { throw it }
        return FakeDocument(pageCount).also { lastDocument = it }
    }
}

private class FakeThumbnailWriter(private val succeed: Boolean = true) : ThumbnailWriter {
    var wroteTo: File? = null
    override fun write(raster: Raster, destination: File): Boolean {
        wroteTo = destination
        if (succeed) destination.writeBytes(byteArrayOf(1))
        return succeed
    }
}

private class ThrowingThumbnailWriter(private val failure: Throwable) : ThumbnailWriter {
    override fun write(raster: Raster, destination: File): Boolean = throw failure
}

private class ThrowingEngine(private val failure: Throwable) : PdfEngine {
    override val textEngineVersion = TextEngineVersion("test-pdf")
    override fun open(source: PdfSource): PdfDocument = throw failure
}

class BookImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun paths() = LibraryPaths(tempFolder.root)
    private fun catalog(paths: LibraryPaths = paths()) = BookCatalogStore(paths)

    private fun source(label: String = "book.pdf", bytes: ByteArray = FIXTURE_BYTES) =
        PickedSource(label) { bytes.inputStream() }

    private fun importer(
        paths: LibraryPaths = paths(),
        catalog: BookCatalogStore = catalog(paths),
        engine: PdfEngine = FakeEngine(),
        thumbnails: ThumbnailWriter = FakeThumbnailWriter(),
        ids: Iterator<String> = generateSequence(0) { it + 1 }.map { "id-$it" }.iterator()
    ) = BookImporter(paths, catalog, engine, thumbnails, newId = { ids.next() })

    @Test
    fun `success renames the staging directory and appends the catalog`() {
        val paths = paths()
        val catalog = catalog(paths)
        val engine = FakeEngine()
        val importer = importer(paths, catalog, engine = engine)

        val outcome = importer.import(source(label = "My Book.pdf"))

        val imported = outcome as ImportOutcome.Imported
        assertEquals("My Book.pdf", imported.book.title)
        assertEquals(3, imported.book.pageCount)
        assertTrue(File(paths.bookDir(imported.book.id), "document.pdf").exists())
        assertTrue(File(paths.bookDir(imported.book.id), "thumb.png").exists())
        assertFalse(paths.stagingDir("id-0").exists())
        assertEquals(listOf(imported.book), catalog.read())
        assertEquals(paths.stagingDocumentFile("id-0").absolutePath, engine.lastOpened?.path)
        assertTrue("the opened document must be closed once the probe finishes", engine.lastDocument?.closed == true)
        assertTrue(
            "the display list built for the thumbnail must be closed once rendered",
            engine.lastDocument?.lastDisplayList?.closed == true
        )
    }

    @Test
    fun `an append failure deletes the renamed book directory and leaves the catalog empty`() {
        val paths = paths()
        val catalog = catalog(paths)
        File(tempFolder.root, "library").mkdirs()
        File(tempFolder.root, "library/catalog").mkdirs()
        val importer = importer(paths, catalog)

        val outcome = importer.import(source())

        val failed = outcome as ImportOutcome.Failed
        assertEquals(ImportFailure.StorageUnavailable, failed.failure)
        assertFalse(paths.bookDir(BookId("id-0")).exists())
        assertTrue(catalog.read().isEmpty())
    }

    @Test
    fun `copy failure leaves no book directory, no staging directory and an untouched catalog`() {
        val paths = paths()
        val catalog = catalog(paths)
        val importer = importer(paths, catalog)
        val failing = PickedSource("missing.pdf") { throw FileNotFoundException() }

        val outcome = importer.import(failing)

        val failed = outcome as ImportOutcome.Failed
        assertEquals(ImportFailure.SourceUnavailable(RecoveryReason.SourceMissing), failed.failure)
        assertFalse(paths.stagingDir("id-0").exists())
        assertFalse(File(tempFolder.root, "library/id-0").exists())
        assertTrue(catalog.read().isEmpty())
    }

    @Test
    fun `probe failure leaves no book directory, no staging directory and an untouched catalog`() {
        val paths = paths()
        val catalog = catalog(paths)
        val engine = FakeEngine(openFailure = PdfException(PdfFailure.Corrupt))
        val importer = importer(paths, catalog, engine = engine)

        val outcome = importer.import(source())

        val failed = outcome as ImportOutcome.Failed
        assertEquals(ImportFailure.NotReadable(PdfFailure.Corrupt), failed.failure)
        assertFalse(paths.stagingDir("id-0").exists())
        assertFalse(File(tempFolder.root, "library/id-0").exists())
        assertTrue(catalog.read().isEmpty())
    }

    @Test
    fun `thumbnail failure leaves no book directory, no staging directory and an untouched catalog`() {
        val paths = paths()
        val catalog = catalog(paths)
        val engine = FakeEngine()
        val thumbnails = FakeThumbnailWriter(succeed = false)
        val importer = importer(paths, catalog, engine = engine, thumbnails = thumbnails)

        val outcome = importer.import(source())

        val failed = outcome as ImportOutcome.Failed
        assertEquals(ImportFailure.StorageUnavailable, failed.failure)
        assertFalse(paths.stagingDir("id-0").exists())
        assertFalse(File(tempFolder.root, "library/id-0").exists())
        assertTrue(catalog.read().isEmpty())
        assertTrue("a failed probe must still close the opened document", engine.lastDocument?.closed == true)
        assertTrue(
            "a failed probe must still close the display list it built",
            engine.lastDocument?.lastDisplayList?.closed == true
        )
    }

    @Test
    fun `a non-PdfException from the raster stage reports the document, not app storage`() {
        val paths = paths()
        val catalog = catalog(paths)
        val engine = FakeEngine()
        val thumbnails = ThrowingThumbnailWriter(IllegalArgumentException("degenerate raster size"))
        val importer = importer(paths, catalog, engine = engine, thumbnails = thumbnails)

        val outcome = importer.import(source())

        val failed = outcome as ImportOutcome.Failed
        assertEquals(ImportFailure.NotReadable(PdfFailure.Resource(retryable = true)), failed.failure)
        assertFalse(paths.stagingDir("id-0").exists())
        assertFalse(File(tempFolder.root, "library/id-0").exists())
        assertTrue(catalog.read().isEmpty())
        assertTrue("the document opened for the probe must still be closed", engine.lastDocument?.closed == true)
    }

    @Test
    fun `an OutOfMemoryError from the raster stage is a per-file failure, not a batch abort`() {
        val paths = paths()
        val catalog = catalog(paths)
        val engine = FakeEngine()
        val thumbnails = ThrowingThumbnailWriter(OutOfMemoryError("bitmap allocation"))
        val importer = importer(paths, catalog, engine = engine, thumbnails = thumbnails)

        val outcome = importer.import(source())

        val failed = outcome as ImportOutcome.Failed
        assertEquals(ImportFailure.NotReadable(PdfFailure.Resource(retryable = true)), failed.failure)
        assertFalse("a leaked staging directory would survive the batch", paths.stagingDir("id-0").exists())
        assertFalse(File(tempFolder.root, "library/id-0").exists())
        assertTrue(catalog.read().isEmpty())
        assertTrue("the document opened for the probe must still be closed", engine.lastDocument?.closed == true)
    }

    @Test
    fun `an untyped engine failure at open reports the document, not app storage`() {
        val paths = paths()
        val catalog = catalog(paths)
        val importer = importer(paths, catalog, engine = ThrowingEngine(IllegalStateException("engine wrapper")))

        val outcome = importer.import(source())

        val failed = outcome as ImportOutcome.Failed
        assertEquals(ImportFailure.NotReadable(PdfFailure.Resource(retryable = true)), failed.failure)
        assertFalse(paths.stagingDir("id-0").exists())
        assertTrue(catalog.read().isEmpty())
    }

    @Test
    fun `an OutOfMemoryError at open is a per-file failure, not a batch abort`() {
        val paths = paths()
        val catalog = catalog(paths)
        val importer = importer(paths, catalog, engine = ThrowingEngine(OutOfMemoryError("engine buffers")))

        val outcome = importer.import(source())

        val failed = outcome as ImportOutcome.Failed
        assertEquals(ImportFailure.NotReadable(PdfFailure.Resource(retryable = true)), failed.failure)
        assertFalse("a leaked staging directory would survive the batch", paths.stagingDir("id-0").exists())
        assertTrue(catalog.read().isEmpty())
    }

    @Test
    fun `a thumbnail writer that reports a failed write is a storage failure`() {
        val paths = paths()
        val catalog = catalog(paths)
        val importer = importer(paths, catalog, thumbnails = FakeThumbnailWriter(succeed = false))

        val outcome = importer.import(source())

        assertEquals(ImportFailure.StorageUnavailable, (outcome as ImportOutcome.Failed).failure)
    }

    @Test
    fun `a batch continues past a failing file with one outcome per file`() {
        val paths = paths()
        val catalog = catalog(paths)
        val importer = importer(paths, catalog)
        val failing = PickedSource("broken.pdf") { throw IOException() }

        val first = importer.import(source(label = "one.pdf"))
        val second = importer.import(failing)
        val third = importer.import(source(label = "two.pdf"))

        assertTrue(first is ImportOutcome.Imported)
        assertTrue(second is ImportOutcome.Failed)
        assertTrue(third is ImportOutcome.Imported)
        assertEquals(2, catalog.read().size)
    }

    @Test
    fun `two imports of identical bytes produce two distinct ids`() {
        val paths = paths()
        val catalog = catalog(paths)
        val importer = importer(paths, catalog)

        val first = importer.import(source()) as ImportOutcome.Imported
        val second = importer.import(source()) as ImportOutcome.Imported

        assertTrue(first.book.id != second.book.id)
        assertEquals(2, catalog.read().size)
    }

    @Test
    fun `a non-path label becomes the title sanitized as-is`() {
        val importer = importer()

        val outcome = importer.import(source(label = "My Book.pdf")) as ImportOutcome.Imported

        assertEquals("My Book.pdf", outcome.book.title)
    }

    @Test
    fun `a path-like label yields only its last segment as the title`() {
        val importer = importer()

        val outcome =
            importer.import(source(label = "/storage/emulated/0/Download/book.pdf")) as ImportOutcome.Imported

        assertEquals("book.pdf", outcome.book.title)
    }

    @Test
    fun `a label that sanitizes to blank falls back to a generic title`() {
        val importer = importer()

        val outcome = importer.import(source(label = "   ")) as ImportOutcome.Imported

        assertEquals("Untitled document", outcome.book.title)
    }

    @Test
    fun `sweepStaging clears garbage left by a killed prior import`() {
        val paths = paths()
        val garbage = paths.stagingDir("orphan")
        garbage.mkdirs()
        File(garbage, "document.pdf").writeText("partial")

        importer(paths).sweepStaging()

        assertFalse(garbage.exists())
    }
}
