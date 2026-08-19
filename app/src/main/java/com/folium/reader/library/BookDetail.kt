package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.DocumentMetadata
import com.folium.reader.core.pdf.OutlineRow
import com.folium.reader.core.pdf.flattenOutline
import com.folium.reader.core.pdf.PdfEngine
import com.folium.reader.core.pdf.PdfSource
import java.io.File
import java.util.concurrent.Executor

/**
 * What a book says about itself, beyond what the shelf already knows.
 *
 * The shelf carries a title, a page count and a position because it stored them at import. Author
 * and table of contents live in the document, so reading them costs an open — which is why this is
 * loaded when a reader asks for a book's detail rather than for every row of a shelf.
 */
data class BookDetail(
    val metadata: DocumentMetadata = DocumentMetadata.NONE,
    val contents: List<OutlineRow> = emptyList(),
    val unreadable: Boolean = false
) {
    companion object {
        /** What the screen shows while the document is being opened. */
        val LOADING = BookDetail()
    }
}

/**
 * Opens a book far enough to read its bibliography and its contents, then closes it again.
 *
 * Deliberately not a session: nothing here renders, caches or holds the document open, because the
 * detail screen is a page of text about a book rather than a view of it. A document that will not
 * open is reported as such rather than thrown — a shelf entry whose file has rotted should still
 * show its title, its position and a way to remove it.
 */
internal class BookDetailLoader(
    private val paths: LibraryPaths,
    private val engine: PdfEngine,
    private val worker: Executor,
    private val main: Executor
) {
    fun load(id: BookId, onLoaded: (BookDetail) -> Unit) {
        worker.execute {
            val detail = read(paths.documentFile(id))
            main.execute { onLoaded(detail) }
        }
    }

    private fun read(document: File): BookDetail = try {
        engine.open(PdfSource(document.absolutePath)).use { pdf ->
            BookDetail(
                metadata = runCatching { pdf.metadata() }.getOrDefault(DocumentMetadata.NONE),
                contents = runCatching { flattenOutline(pdf.outline()) }.getOrDefault(emptyList())
            )
        }
    } catch (_: RuntimeException) {
        BookDetail(unreadable = true)
    } catch (_: OutOfMemoryError) {
        BookDetail(unreadable = true)
    }
}
