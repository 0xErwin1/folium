package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.ImportFailure
import com.folium.reader.core.library.ImportOutcome
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.pdf.PdfEngine
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.reader.PdfEngines
import com.folium.reader.saf.DocumentCopy
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.roundToInt

private const val THUMB_LONGEST_EDGE_PX = 320

/** One file picked for import: a presentation [label] and a stream opened on demand. */
data class PickedSource(val label: String, val open: () -> InputStream)

/**
 * Imports one picked file at a time into app storage, all-or-nothing per file.
 *
 * Every step runs against a staging directory, and any failure up to and including the final
 * rename deletes that staging directory and leaves the catalog untouched — a partial import is
 * never listed. [sweepStaging] must be called once per batch, before the first [import] call, to
 * clear staging garbage a killed prior import left behind.
 */
class BookImporter(
    private val paths: LibraryPaths,
    private val catalog: BookCatalogStore,
    private val engine: PdfEngine = PdfEngines.load(),
    private val thumbnails: ThumbnailWriter = BitmapThumbnailWriter(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun sweepStaging() {
        paths.stagingRoot().deleteRecursively()
    }

    fun import(source: PickedSource): ImportOutcome {
        val id = newId()
        val staging = paths.stagingDir(id)

        if (!staging.mkdirs()) {
            return ImportOutcome.Failed(source.label, ImportFailure.StorageUnavailable)
        }

        val stagingDocument = File(staging, "document.pdf")
        val copyFailure = DocumentCopy.copyStream(source.open, stagingDocument)
        if (copyFailure != null) {
            staging.deleteRecursively()
            return ImportOutcome.Failed(source.label, ImportFailure.SourceUnavailable(copyFailure))
        }

        val probe = probeAndThumbnail(stagingDocument, File(staging, "thumb.png"))
        val pageCount = when (probe) {
            is ProbeOutcome.Success -> probe.pageCount

            is ProbeOutcome.NotReadable -> {
                staging.deleteRecursively()
                return ImportOutcome.Failed(source.label, ImportFailure.NotReadable(probe.failure))
            }

            ProbeOutcome.ThumbnailFailed -> {
                staging.deleteRecursively()
                return ImportOutcome.Failed(source.label, ImportFailure.StorageUnavailable)
            }
        }

        val bookId = BookId(id)
        if (!staging.renameTo(paths.bookDir(bookId))) {
            staging.deleteRecursively()
            return ImportOutcome.Failed(source.label, ImportFailure.StorageUnavailable)
        }

        val book = LibraryBook(bookId, titleFromLabel(source.label), pageCount, clock())
        catalog.append(book)
        return ImportOutcome.Imported(source.label, book)
    }

    private sealed class ProbeOutcome {
        data class Success(val pageCount: Int) : ProbeOutcome()
        data class NotReadable(val failure: PdfFailure) : ProbeOutcome()
        data object ThumbnailFailed : ProbeOutcome()
    }

    private fun probeAndThumbnail(documentFile: File, thumbnailFile: File): ProbeOutcome {
        val pdf = try {
            engine.open(PdfSource(documentFile.absolutePath))
        } catch (failure: PdfException) {
            return ProbeOutcome.NotReadable(failure.failure)
        }

        return try {
            val pageCount = pdf.pageCount
            if (pageCount <= 0) return ProbeOutcome.NotReadable(PdfFailure.Corrupt)

            val info = pdf.pageInfo(0)
            val displayList = pdf.buildDisplayList(0)
            val raster = try {
                displayList.render(thumbnailSpec(info))
            } finally {
                displayList.close()
            }

            if (thumbnails.write(raster, thumbnailFile)) ProbeOutcome.Success(pageCount) else ProbeOutcome.ThumbnailFailed
        } catch (failure: PdfException) {
            ProbeOutcome.NotReadable(failure.failure)
        } finally {
            pdf.close()
        }
    }

    private fun thumbnailSpec(info: PageInfo): RenderSpec {
        val aspect = info.width / info.height
        val (width, height) = if (aspect >= 1f) {
            THUMB_LONGEST_EDGE_PX to (THUMB_LONGEST_EDGE_PX / aspect).roundToInt().coerceAtLeast(1)
        } else {
            (THUMB_LONGEST_EDGE_PX * aspect).roundToInt().coerceAtLeast(1) to THUMB_LONGEST_EDGE_PX
        }
        return RenderSpec(width, height)
    }

    /**
     * The picked file's presentation label, sanitized into a title: control characters stripped
     * and the result trimmed, falling back to the label's last path segment sanitized the same
     * way, falling back to a generic title if both are blank.
     */
    private fun titleFromLabel(label: String): String {
        val sanitized = label.filterNot { it.isISOControl() }.trim()
        if (sanitized.isNotBlank()) return sanitized

        val lastSegment = label.substringAfterLast('/').filterNot { it.isISOControl() }.trim()
        if (lastSegment.isNotBlank()) return lastSegment

        return "Untitled document"
    }
}
