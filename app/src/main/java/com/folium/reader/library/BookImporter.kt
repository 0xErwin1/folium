package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.ImportFailure
import com.folium.reader.core.library.ImportOutcome
import com.folium.reader.core.library.LibraryBook
import com.folium.reader.core.pdf.PdfEngine
import com.folium.reader.core.pdf.DocumentMetadata
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

/**
 * The verdict for a probe that neither the engine nor app storage typed for us: an untyped engine
 * failure, or a raster the device could not allocate or encode. Retryable, because both causes are
 * pressure rather than a property of the file.
 */
private val UNREADABLE_RESOURCE = PdfFailure.Resource(retryable = true)

/** One file picked for import: a presentation [label] and a stream opened on demand. */
data class PickedSource(val label: String, val open: () -> InputStream)

/**
 * Imports one picked file at a time into app storage, all-or-nothing per file.
 *
 * Every step runs against a staging directory. A failure up to and including the rename deletes
 * that staging directory and leaves the catalog untouched; a failure to append after the rename
 * deletes the renamed book directory instead, so a partial import is never listed either way.
 * [sweepStaging] must be called once per batch, before the first [import] call, to clear staging
 * garbage a killed prior import left behind.
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

        val stagingDocument = paths.stagingDocumentFile(id)
        val copyFailure = DocumentCopy.copyStream(source.open, stagingDocument)
        if (copyFailure != null) {
            staging.deleteRecursively()
            return ImportOutcome.Failed(source.label, ImportFailure.SourceUnavailable(copyFailure))
        }

        val probe = probeAndThumbnail(stagingDocument, paths.stagingThumbnailFile(id))
        val declared = when (probe) {
            is ProbeOutcome.Success -> probe

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
        val bookDir = paths.bookDir(bookId)
        if (!staging.renameTo(bookDir)) {
            staging.deleteRecursively()
            return ImportOutcome.Failed(source.label, ImportFailure.StorageUnavailable)
        }

        val title = bookTitle(declared.metadata, source.label)
        val book = LibraryBook(
            bookId,
            title.text,
            declared.pageCount,
            clock(),
            declared.metadata.author,
            title.declared
        )
        if (!catalog.append(book)) {
            bookDir.deleteRecursively()
            return ImportOutcome.Failed(source.label, ImportFailure.StorageUnavailable)
        }

        return ImportOutcome.Imported(source.label, book)
    }

    private sealed class ProbeOutcome {
        data class Success(val pageCount: Int, val metadata: DocumentMetadata) : ProbeOutcome()
        data class NotReadable(val failure: PdfFailure) : ProbeOutcome()
        data object ThumbnailFailed : ProbeOutcome()
    }

    /**
     * Opens the staged copy, reads its page count and renders its first page into a thumbnail.
     *
     * Everything this stage can fail at describes the document, not app storage: the engine
     * rejecting it, the engine wrapper failing in an untyped way, or the raster being too large to
     * allocate or encode. All of those answer [ProbeOutcome.NotReadable]; only the thumbnail
     * writer's own `false` — a failed write inside `filesDir` — is a storage failure.
     * [OutOfMemoryError] is caught alongside the runtime exceptions because it is an `Error`, and
     * left uncaught it would abort the whole batch and leak this file's staging directory.
     */
    private fun probeAndThumbnail(documentFile: File, thumbnailFile: File): ProbeOutcome {
        val pdf = try {
            engine.open(PdfSource(documentFile.absolutePath))
        } catch (failure: PdfException) {
            return ProbeOutcome.NotReadable(failure.failure)
        } catch (_: RuntimeException) {
            return ProbeOutcome.NotReadable(UNREADABLE_RESOURCE)
        } catch (_: OutOfMemoryError) {
            return ProbeOutcome.NotReadable(UNREADABLE_RESOURCE)
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

            val metadata = runCatching { pdf.metadata() }.getOrDefault(DocumentMetadata.NONE)

            if (thumbnails.write(raster, thumbnailFile)) {
                ProbeOutcome.Success(pageCount, metadata)
            } else {
                ProbeOutcome.ThumbnailFailed
            }
        } catch (failure: PdfException) {
            ProbeOutcome.NotReadable(failure.failure)
        } catch (_: RuntimeException) {
            ProbeOutcome.NotReadable(UNREADABLE_RESOURCE)
        } catch (_: OutOfMemoryError) {
            ProbeOutcome.NotReadable(UNREADABLE_RESOURCE)
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
}

/**
 * What the document calls itself, or failing that what the file is called.
 *
 * A declared title is preferred because it is the only one an author wrote: file names arrive
 * slugged, URL-encoded and extension-bearing. It is rejected when it merely restates the file name,
 * which some producers do, because that is no better than the fallback and costs the reader the
 * impression that the app knows something it does not.
 */
internal fun bookTitle(metadata: DocumentMetadata, label: String): ImportedTitle {
    val fallback = ImportedTitle(titleFromLabel(label), declared = false)
    val declared = metadata.title?.trim()?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
        ?: return fallback

    val bare = fallback.text.substringBeforeLast('.')
    return if (declared.equals(bare, ignoreCase = true) || declared.equals(fallback.text, ignoreCase = true)) {
        fallback
    } else {
        ImportedTitle(declared, declared = true)
    }
}

/**
 * A title and where it came from. The origin travels with the text because the shelf draws the two
 * differently, and the importer is the last place that still knows which source won.
 */
internal data class ImportedTitle(val text: String, val declared: Boolean)

/**
 * The picked file's presentation label, sanitized into a title: control characters stripped and the
 * result trimmed. A label that is not path-like is used sanitized as-is; a path-like label — one
 * whose sanitized form still contains a `/` — yields only its sanitized last segment instead, e.g.
 * "/storage/emulated/0/Download/book.pdf" becomes "book.pdf". Either falls back to a generic title
 * when the result is still blank.
 */
internal fun titleFromLabel(label: String): String {
    val sanitized = sanitizedLabel(label)
    if (sanitized.isNotBlank() && !sanitized.contains('/')) return sanitized

    val lastSegment = sanitized.substringAfterLast('/')
    if (lastSegment.isNotBlank()) return lastSegment

    return "Untitled document"
}
