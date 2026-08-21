package com.folium.reader.reader

import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.PdfEngine
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.text.TextEngineVersion
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed class ReaderDocumentResult {
    data class Opened(val document: ReaderDocument) : ReaderDocumentResult()
    data object Missing : ReaderDocumentResult()
    data class Unreadable(val failure: PdfFailure) : ReaderDocumentResult()
}

/**
 * One open document: the engine session over its stored file, and what is known so far about the
 * shape of its pages.
 *
 * Page shapes are learned as pages are rendered rather than measured up front, so opening a long
 * document costs one measurement rather than one per page. Until a page has been measured, the
 * first page's shape stands in for it — which is exact for the overwhelmingly common document whose
 * pages are all alike, and self-correcting for the ones that are not: [record] reports when a newly
 * measured page contradicts the assumption, and the reader re-lays out once when it does. The one
 * page the reader may open directly on — a restored page far from the first — is measured up front
 * too, so that page renders correctly on its very first frame instead of borrowing the first page's
 * shape and re-laying out the moment it is actually measured.
 */
class ReaderDocument internal constructor(
    internal val pdf: PdfDocument,
    val bookId: BookId,
    pageCount: Int,
    outline: List<OutlineEntry>,
    val textEngineVersion: TextEngineVersion,
    firstPageAspect: Float,
    initialPage: Int,
    initialPageAspect: Float?
) : Closeable {

    var pageCount: Int = pageCount
        private set

    var outline: List<OutlineEntry> = outline
        private set

    private val aspects = ConcurrentHashMap<Int, Float>()

    init {
        aspects[0] = firstPageAspect
        if (initialPageAspect != null) aspects[initialPage] = initialPageAspect
    }

    fun aspect(pageIndex: Int): Float = aspects[pageIndex] ?: aspects.getValue(0)

    /**
     * Adopts the document's shape after a successful [PdfDocument.relayout]: a new page count, a
     * freshly read outline (its own page indexes are only meaningful under the new layout) and a
     * cleared aspect map reseeded exactly as the constructor seeds it, since every previously known
     * page shape belonged to the layout that just stopped existing.
     */
    internal fun applyRelayout(
        newPageCount: Int,
        newOutline: List<OutlineEntry>,
        firstPageAspect: Float,
        resolvedPage: Int,
        resolvedPageAspect: Float?
    ) {
        pageCount = newPageCount
        outline = newOutline
        aspects.clear()
        aspects[0] = firstPageAspect
        if (resolvedPageAspect != null) aspects[resolvedPage] = resolvedPageAspect
    }

    /**
     * Measures [pageIndex] with [measure] only if its shape is not already held.
     *
     * A page's shape belongs to the document, not to the render that happened to notice it, so the
     * second measurement of a page can only confirm the first. Asking the engine again costs a page
     * lookup — around a fifth of what a whole-page preview costs — on every render of every page,
     * for an answer already in hand.
     *
     * Returns what [record] would have: whether this measurement contradicts the shape the reader
     * had been assuming, which is false for a page already held, exactly as a second [record] was.
     */
    fun measureIfUnknown(pageIndex: Int, measure: (Int) -> Float): Boolean {
        val assumed = aspect(pageIndex)
        var measured = false

        // Atomic rather than a read followed by a write: both tiers render the same page at the same
        // moment, and a check that is not held across the measurement lets each of them pay for it.
        val actual = aspects.computeIfAbsent(pageIndex) { index ->
            measured = true
            measure(index)
        }

        return measured && kotlin.math.abs(assumed - actual) > ASPECT_TOLERANCE
    }

    /** Returns whether this measurement contradicts the shape the reader had been assuming. */
    fun record(pageIndex: Int, aspect: Float): Boolean {
        val assumed = this.aspect(pageIndex)
        val previous = aspects.put(pageIndex, aspect)
        return previous == null && kotlin.math.abs(assumed - aspect) > ASPECT_TOLERANCE
    }

    override fun close() = pdf.close()

    companion object {
        private const val ASPECT_TOLERANCE = 0.01f

        /**
         * Opens the book's own stored file directly.
         *
         * Blocking: this parses a file, so it must run off the main thread. There is no copy step
         * and nothing to clean up on any path — the reader owns no scratch file, so no interim state
         * can leak. [engine] defaults to the packaged engine and exists only as a host-testing seam;
         * every production call site takes the default.
         */
        fun open(
            file: File,
            bookId: BookId,
            initialPage: Int,
            engine: PdfEngine = PdfEngines.load()
        ): ReaderDocumentResult {
            if (!file.exists()) return ReaderDocumentResult.Missing

            return try {
                openExisting(file, bookId, initialPage, engine)
            } catch (failure: PdfException) {
                ReaderDocumentResult.Unreadable(failure.failure)
            }
        }

        private fun openExisting(file: File, bookId: BookId, initialPage: Int, engine: PdfEngine): ReaderDocumentResult {
            val pdf = engine.open(PdfSource(file.absolutePath))

            return try {
                val pageCount = pdf.pageCount
                if (pageCount <= 0) throw PdfException(PdfFailure.Corrupt)

                val clampedInitial = initialPage.coerceIn(0, pageCount - 1)
                val firstPage = pdf.pageInfo(0)
                val initialPageInfo = if (clampedInitial != 0) pdf.pageInfo(clampedInitial) else null

                val outline = try {
                    pdf.outline()
                } catch (_: PdfException) {
                    emptyList()
                }

                ReaderDocumentResult.Opened(
                    ReaderDocument(
                        pdf = pdf,
                        bookId = bookId,
                        pageCount = pageCount,
                        outline = outline,
                        textEngineVersion = engine.textEngineVersion,
                        firstPageAspect = firstPage.width / firstPage.height,
                        initialPage = clampedInitial,
                        initialPageAspect = initialPageInfo?.let { it.width / it.height }
                    )
                )
            } catch (failure: Throwable) {
                pdf.close()
                throw failure
            }
        }
    }
}
