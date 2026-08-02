package com.folium.reader.library

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.library.ImportOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Runs a real PDF through [BookImporter] and back out through [BookCatalogStore]: the real engine
 * parses it, a real [android.graphics.Bitmap] is encoded to `filesDir`, and the catalog row that
 * results is read back rather than assumed. Nothing here has run against a real engine, a real
 * bitmap or a real `filesDir` since Phase C — this is that gap closing.
 */
@RunWith(AndroidJUnit4::class)
class ImportInstrumentedTest {

    private companion object {
        const val PAGE_COUNT = 4
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val libraryRoot = File(context.filesDir, "library")

    private val paths = LibraryPaths(context.filesDir)
    private val catalog = BookCatalogStore(paths)
    private val importer = BookImporter(paths, catalog)

    @Before fun clearLibrary() {
        libraryRoot.deleteRecursively()
    }

    @After fun tearDown() {
        libraryRoot.deleteRecursively()
    }

    @Test fun a_real_pdf_is_staged_probed_and_appended_to_the_catalog() {
        val source = PickedSource("A real book.pdf") { ByteArrayInputStream(realPdfBytes(PAGE_COUNT)) }

        val outcome = importer.import(source)

        assertTrue("import must succeed for a genuinely readable PDF: $outcome", outcome is ImportOutcome.Imported)
        val book = (outcome as ImportOutcome.Imported).book

        assertEquals(PAGE_COUNT, book.pageCount)
        assertEquals("A real book.pdf", book.title)

        val documentFile = paths.documentFile(book.id)
        val thumbnailFile = paths.thumbnailFile(book.id)
        assertTrue("the copied document must exist", documentFile.exists())
        assertTrue("the thumbnail must exist", thumbnailFile.exists())

        val thumbnail = BitmapFactory.decodeFile(thumbnailFile.absolutePath)
        assertNotNull("the thumbnail must be a decodable image, not just bytes on disk", thumbnail)
        thumbnail?.recycle()

        val row = catalog.read().singleOrNull { it.id == book.id }
        assertNotNull("the catalog must carry exactly one row for the imported book", row)
        assertEquals(PAGE_COUNT, row?.pageCount)

        assertFalse("staging must be swept clean once the import is done", paths.stagingDir(book.id.value).exists())
    }

    @Test fun a_corrupt_file_leaves_no_directory_and_no_catalog_row() {
        val source = PickedSource("broken.pdf") { ByteArrayInputStream("this is not a pdf".toByteArray()) }

        importer.sweepStaging()
        val outcome = importer.import(source)

        assertTrue("a corrupt file must be reported as a failure, not imported: $outcome", outcome is ImportOutcome.Failed)
        assertTrue("the catalog must stay untouched", catalog.read().isEmpty())
        assertTrue(
            "staging must leave no trace of the failed attempt",
            paths.stagingRoot().listFiles()?.isEmpty() != false
        )
    }

    /** Writes a genuinely renderable, throwaway PDF and returns its bytes. */
    private fun realPdfBytes(pages: Int): ByteArray {
        val document = PdfDocument()
        return try {
            repeat(pages) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("Page ${index + 1}", 90f, 300f, android.graphics.Paint().apply {
                    color = Color.BLACK
                    textSize = 48f
                })
                document.finishPage(page)
            }
            val out = java.io.ByteArrayOutputStream()
            document.writeTo(out)
            out.toByteArray()
        } finally {
            document.close()
        }
    }
}
