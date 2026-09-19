package com.folium.reader.core.preview

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReferenceArray

/** Bumped whenever the on-disk layout in this file changes in a way older files cannot be read back under. */
private const val PAGE_PREVIEW_FORMAT_VERSION: Int = 1

/** Marks the start of a preview file, so a foreign or unrelated file is rejected outright rather than misread. */
private const val MAGIC: Int = 0x464F_4C50 // "FOLP"

/** offset(8) + length(4) + width(4) + height(4), one of these per page, right after the header. */
private const val INDEX_ENTRY_BYTES: Int = 20

/**
 * Every one of a document's page previews, held both in memory and in one file on disk, at a fixed
 * path derived from [engineId], [contentId] and [layoutVersion] — see [pagePreviewFileName] — so a
 * preview made under a different engine version or a different reflow layout is simply a different
 * file, and a stale one from a retired layout is never opened, checked and discarded on every future
 * session; it is left for the same directory-level cleanup that already removes a document's other
 * stale disk cache files. The header inside the file repeats that same identity as a second guard
 * against a hash collision or a hand-placed file, so a mismatch there is still caught even though the
 * file name alone is expected to make it vanishingly rare.
 *
 * The file is a header, then a fixed-size index of [pageCount] entries, then a growing region of
 * pixel data. An index entry is a plain (offset, length, width, height) tuple rather than a fixed
 * maximum slot per page: pages of one document are usually all the same aspect ratio and so cost
 * about the same, but nothing here relies on that, and a fixed maximum slot sized for the tallest
 * page in a mixed-aspect document would waste that difference on every other page for the life of the
 * file. [addPreview] only ever appends new pixel data at the current end of the file and then
 * overwrites that one page's own fixed-position index entry — nothing already on disk is rewritten.
 *
 * A process kill can land at any point in [addPreview] without corrupting anything already readable:
 * the pixel data is written first, and only once that append is complete is the index entry updated to
 * point at it, so a kill before the index write leaves the new bytes present but unreferenced — dead
 * space, not corruption — and [load] never trusts an index entry without also checking that the bytes
 * it claims are actually there. Any inconsistency, at the file level or at a single entry's level, is
 * treated as "no preview" rather than surfaced as an error: a corrupt or half-written file is deleted
 * and replaced with an empty one, and a bad single entry is simply skipped.
 *
 * In-memory reads go through [entries], an [AtomicReferenceArray] rather than a lock: any number of
 * threads may call [previewFor] concurrently with a single writer calling [addPreview], and every
 * reader either sees a page's previous state or its new one, never a partially updated one. Disk
 * writes inside [addPreview] are additionally serialized by [writeLock], since more than one producer
 * thread calling it for different pages at once would otherwise race over the shared append position.
 */
class PagePreviewFile private constructor(
    private val file: File,
    private val engineId: String,
    private val contentId: String,
    private val layoutVersion: String?,
    val pageCount: Int,
    private val pixelFormat: PagePreviewPixelFormat,
    private val indexStart: Long,
    private val entries: AtomicReferenceArray<PagePreview?>
) {
    private val writeLock = Any()

    /** The preview held for [pageIndex], or null if none exists yet or [pageIndex] is out of range. */
    fun previewFor(pageIndex: Int): PagePreview? {
        if (pageIndex < 0 || pageIndex >= pageCount) return null
        return entries.get(pageIndex)
    }

    /**
     * Stores [preview] for [pageIndex]: a no-op, returning false, when [pageIndex] is out of range,
     * [preview] is not in this file's [pixelFormat], a preview for that page already exists, or the
     * write fails for any I/O reason. Every one of those is silent — no exception ever reaches the
     * caller — since a preview that fails to persist only costs a future placeholder, never a wrong
     * page shown to the reader.
     */
    fun addPreview(pageIndex: Int, preview: PagePreview): Boolean {
        if (pageIndex < 0 || pageIndex >= pageCount) return false
        if (preview.format != pixelFormat) return false
        if (entries.get(pageIndex) != null) return false

        val wrote = synchronized(writeLock) {
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    val offset = raf.length()
                    raf.seek(offset)
                    raf.write(preview.pixels)

                    raf.seek(indexStart + pageIndex.toLong() * INDEX_ENTRY_BYTES)
                    raf.writeLong(offset)
                    raf.writeInt(preview.pixels.size)
                    raf.writeInt(preview.width)
                    raf.writeInt(preview.height)
                }
                true
            } catch (_: IOException) {
                false
            }
        }

        if (wrote) entries.set(pageIndex, preview)
        return wrote
    }

    companion object {

        /**
         * Loads [file] if it exists, matches [engineId]/[contentId]/[layoutVersion]/[pageCount]/
         * [pixelFormat] exactly, and its index is internally consistent, or otherwise deletes whatever
         * is there — a missing file, a foreign one, one from a stale identity, or one that fails to
         * parse — and creates a fresh, empty one under the same identity. Never throws.
         */
        fun open(
            file: File,
            engineId: String,
            contentId: String,
            layoutVersion: String?,
            pageCount: Int,
            pixelFormat: PagePreviewPixelFormat = PagePreviewPixelFormat.RGB_565
        ): PagePreviewFile {
            val loaded = if (file.isFile) {
                tryLoad(file, engineId, contentId, layoutVersion, pageCount, pixelFormat)
            } else {
                null
            }
            if (loaded != null) return loaded

            file.delete()
            try {
                createEmpty(file, engineId, contentId, layoutVersion, pageCount, pixelFormat)
            } catch (_: IOException) {
                // Falls through to an in-memory-only instance: every read is a miss and every write
                // is silently dropped by addPreview's own IOException handling once it in turn fails
                // to recreate the same file, which is exactly the "no preview available" behavior a
                // caller already has to tolerate for any other page.
            }
            val indexStart = headerSize(engineId, contentId, layoutVersion)
            return PagePreviewFile(
                file, engineId, contentId, layoutVersion, pageCount, pixelFormat, indexStart,
                AtomicReferenceArray(pageCount)
            )
        }

        /** The file name identity maps to: a hash of every field two identities must agree on to share previews, mirroring [com.folium.reader.core.diskcache.diskPageCacheFileName]. */
        fun fileName(engineId: String, contentId: String, layoutVersion: String?): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(identityBytes(engineId, contentId, layoutVersion))
            return digest.joinToString("") { "%02x".format(it) } + ".pgv"
        }

        private fun identityBytes(engineId: String, contentId: String, layoutVersion: String?): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { out ->
                out.writeInt(PAGE_PREVIEW_FORMAT_VERSION)
                out.writeUTF(engineId)
                out.writeUTF(contentId)
                out.writeBoolean(layoutVersion != null)
                if (layoutVersion != null) out.writeUTF(layoutVersion)
            }
            return buffer.toByteArray()
        }

        private fun headerSize(engineId: String, contentId: String, layoutVersion: String?): Long {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { out -> writeHeader(out, engineId, contentId, layoutVersion, 0, PagePreviewPixelFormat.RGB_565) }
            return buffer.size().toLong()
        }

        private fun writeHeader(
            out: DataOutputStream,
            engineId: String,
            contentId: String,
            layoutVersion: String?,
            pageCount: Int,
            pixelFormat: PagePreviewPixelFormat
        ) {
            out.writeInt(MAGIC)
            out.writeInt(PAGE_PREVIEW_FORMAT_VERSION)
            out.writeUTF(engineId)
            out.writeUTF(contentId)
            out.writeBoolean(layoutVersion != null)
            if (layoutVersion != null) out.writeUTF(layoutVersion)
            out.writeInt(pageCount)
            out.writeByte(pixelFormat.id.toInt())
        }

        private fun createEmpty(
            file: File,
            engineId: String,
            contentId: String,
            layoutVersion: String?,
            pageCount: Int,
            pixelFormat: PagePreviewPixelFormat
        ) {
            val temp = File(file.parentFile, file.name + ".tmp")
            file.parentFile?.mkdirs()
            RandomAccessFile(temp, "rw").use { raf ->
                val buffer = ByteArrayOutputStream()
                DataOutputStream(buffer).use { out -> writeHeader(out, engineId, contentId, layoutVersion, pageCount, pixelFormat) }
                raf.write(buffer.toByteArray())
                repeat(pageCount) {
                    raf.writeLong(-1L)
                    raf.writeInt(0)
                    raf.writeInt(0)
                    raf.writeInt(0)
                }
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        private fun tryLoad(
            file: File,
            engineId: String,
            contentId: String,
            layoutVersion: String?,
            pageCount: Int,
            pixelFormat: PagePreviewPixelFormat
        ): PagePreviewFile? {
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    if (raf.readInt() != MAGIC) return null
                    if (raf.readInt() != PAGE_PREVIEW_FORMAT_VERSION) return null
                    if (raf.readUTF() != engineId) return null
                    if (raf.readUTF() != contentId) return null
                    val hasLayoutVersion = raf.readBoolean()
                    val fileLayoutVersion = if (hasLayoutVersion) raf.readUTF() else null
                    if (fileLayoutVersion != layoutVersion) return null
                    if (raf.readInt() != pageCount) return null
                    if (PagePreviewPixelFormat.fromId(raf.readByte()) != pixelFormat) return null

                    val indexStart = raf.filePointer
                    val dataStart = indexStart + pageCount.toLong() * INDEX_ENTRY_BYTES
                    val fileLength = raf.length()
                    if (dataStart > fileLength) return null

                    val entries = AtomicReferenceArray<PagePreview?>(pageCount)
                    val rawEntries = arrayOfNulls<LongArray>(pageCount)
                    for (pageIndex in 0 until pageCount) {
                        val offset = raf.readLong()
                        val length = raf.readInt()
                        val width = raf.readInt()
                        val height = raf.readInt()
                        rawEntries[pageIndex] = longArrayOf(offset, length.toLong(), width.toLong(), height.toLong())
                    }

                    for (pageIndex in 0 until pageCount) {
                        val (offset, length, width, height) = rawEntries[pageIndex]!!
                        if (offset < dataStart || length <= 0 || width <= 0 || height <= 0) continue
                        if (offset + length > fileLength) continue
                        if (length != width * height * pixelFormat.bytesPerPixel) continue

                        val pixels = ByteArray(length.toInt())
                        raf.seek(offset)
                        raf.readFully(pixels)
                        entries.set(pageIndex, PagePreview(width.toInt(), height.toInt(), pixelFormat, pixels))
                    }

                    PagePreviewFile(file, engineId, contentId, layoutVersion, pageCount, pixelFormat, indexStart, entries)
                }
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private operator fun LongArray.component1() = this[0]
        private operator fun LongArray.component2() = this[1]
        private operator fun LongArray.component3() = this[2]
        private operator fun LongArray.component4() = this[3]
    }
}
