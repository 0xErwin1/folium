package com.folium.reader.reader

import android.content.Context
import com.folium.reader.core.library.ProviderDocumentIdentity
import com.folium.reader.core.library.RecoveryState
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.saf.SafDocumentResult
import com.folium.reader.saf.SafDocumentSource
import com.folium.reader.saf.SharedPreferencesSafRootStorage
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed class ReaderDocumentResult {
    data class Opened(val document: ReaderDocument) : ReaderDocumentResult()
    data class Unavailable(val recovery: RecoveryState) : ReaderDocumentResult()
    data class Unreadable(val failure: PdfFailure) : ReaderDocumentResult()
}

/**
 * One open document: the private copy it was read into, the engine session over it, and what is
 * known so far about the shape of its pages.
 *
 * Page shapes are learned as pages are rendered rather than measured up front, so opening a long
 * document costs one measurement rather than one per page. Until a page has been measured, the
 * first page's shape stands in for it — which is exact for the overwhelmingly common document whose
 * pages are all alike, and self-correcting for the ones that are not: [record] reports when a newly
 * measured page contradicts the assumption, and the reader re-lays out once when it does.
 */
class ReaderDocument internal constructor(
    internal val pdf: PdfDocument,
    private val file: File,
    val identity: ProviderDocumentIdentity,
    val pageCount: Int,
    firstPageAspect: Float
) : Closeable {

    private val aspects = ConcurrentHashMap<Int, Float>()

    init { aspects[0] = firstPageAspect }

    val documentId: String = "${identity.providerAuthority}/${identity.documentId}"

    fun aspect(pageIndex: Int): Float = aspects[pageIndex] ?: aspects.getValue(0)

    /** Returns whether this measurement contradicts the shape the reader had been assuming. */
    fun record(pageIndex: Int, aspect: Float): Boolean {
        val assumed = this.aspect(pageIndex)
        val previous = aspects.put(pageIndex, aspect)
        return previous == null && kotlin.math.abs(assumed - aspect) > ASPECT_TOLERANCE
    }

    override fun close() {
        try {
            pdf.close()
        } finally {
            file.delete()
        }
    }

    companion object {
        private const val ASPECT_TOLERANCE = 0.01f

        /**
         * Copies the document out of the persisted SAF root and opens it.
         *
         * Blocking: this both streams a file and parses it, so it must run off the main thread. The
         * copy is deleted again on every path that does not hand back an open document, so a failed
         * open never leaves the cache directory holding a file nothing owns.
         */
        fun open(context: Context, identity: ProviderDocumentIdentity): ReaderDocumentResult {
            val file = File(context.cacheDir, "reader/open-document.pdf")
            val source = SafDocumentSource(context.contentResolver, SharedPreferencesSafRootStorage(context))

            when (val copied = source.copyTo(identity, file)) {
                is SafDocumentResult.Unavailable -> {
                    file.delete()
                    return ReaderDocumentResult.Unavailable(copied.recovery)
                }

                is SafDocumentResult.Copied -> Unit
            }

            return try {
                openCopied(file, identity)
            } catch (failure: PdfException) {
                file.delete()
                ReaderDocumentResult.Unreadable(failure.failure)
            }
        }

        private fun openCopied(file: File, identity: ProviderDocumentIdentity): ReaderDocumentResult {
            val pdf = PdfEngines.load().open(PdfSource(file.absolutePath))

            return try {
                val pageCount = pdf.pageCount
                if (pageCount <= 0) throw PdfException(PdfFailure.Corrupt)

                val firstPage = pdf.pageInfo(0)
                ReaderDocumentResult.Opened(
                    ReaderDocument(pdf, file, identity, pageCount, firstPage.width / firstPage.height)
                )
            } catch (failure: Throwable) {
                pdf.close()
                throw failure
            }
        }
    }
}
