package com.folium.reader.core.ink

import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.ReadingPosition
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

private const val SHEET_META_MAGIC: Int = 0x464F_4C4D // "FOLM"
private const val SHEET_META_VERSION_PAGE_ONLY_ANCHOR: Int = 1
private const val SHEET_META_VERSION: Int = 2
private const val ANCHOR_KIND_NONE: Int = 0
private const val ANCHOR_KIND_PAGE: Int = 1
private const val ANCHOR_KIND_TEXT: Int = 2
private const val SHEET_META_TEMP_SUFFIX = ".tmp"

/**
 * [file] exists but does not parse as a [Sheet]. The file itself is left exactly as it was: this
 * store never deletes or overwrites a file it cannot read, since doing so could discard a user's
 * handwriting with no way to recover it.
 */
class SheetMetaCorruptException(val file: File, reason: String) :
    Exception("corrupt sheet metadata at $file: $reason")

/**
 * Reads and writes a [Sheet]'s small metadata file.
 *
 * [write] always emits the current version, which records the anchor as a kind byte followed by that
 * kind's fields and its rank. [read] also accepts version 1, written before a sheet could be tied to
 * a text position: its optional anchor was always a page, and it had no rank, so it reads as a
 * [SheetAnchor.Page] of rank 0.
 *
 * [write] is always atomic: the encoded bytes are written to a `.tmp` sibling first, fsynced, and
 * then renamed over the real file with [Files.move]'s atomic move, so a reader never observes a
 * half-written header and a crash mid-write leaves the previous, still-valid file in place with only
 * an orphaned `.tmp` sibling behind — a file [SheetStore] never looks at when reading a directory's
 * committed metadata.
 *
 * Blocking, synchronous I/O; the caller owns whatever thread it runs on.
 */
internal object SheetMetaFile {

    fun write(file: File, sheet: Sheet) = writeFileAtomically(file, encode(sheet))

    /** Reads [file], or throws [SheetMetaCorruptException] when it exists but does not parse. */
    fun read(file: File): Sheet {
        try {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                if (input.readInt() != SHEET_META_MAGIC) throw SheetMetaCorruptException(file, "bad magic")

                val version = input.readByte().toInt() and 0xFF
                if (version != SHEET_META_VERSION && version != SHEET_META_VERSION_PAGE_ONLY_ANCHOR) {
                    throw SheetMetaCorruptException(file, "unsupported version $version")
                }

                val id = SheetId(input.readUTF())
                val title = input.readUTF()
                val createdAt = input.readLong()
                val updatedAt = input.readLong()

                val templateOrdinal = input.readByte().toInt() and 0xFF
                val template = SheetTemplate.entries.getOrNull(templateOrdinal)
                    ?: throw SheetMetaCorruptException(file, "unknown template ordinal $templateOrdinal")

                val anchor = if (version == SHEET_META_VERSION_PAGE_ONLY_ANCHOR) {
                    readPageOnlyAnchor(input)
                } else {
                    readAnchor(file, input)
                }

                return Sheet(id, title, createdAt, updatedAt, template, anchor)
            }
        } catch (e: SheetMetaCorruptException) {
            throw e
        } catch (e: IOException) {
            throw SheetMetaCorruptException(file, e.message ?: "I/O error")
        } catch (e: IllegalArgumentException) {
            throw SheetMetaCorruptException(file, e.message ?: "invalid field")
        }
    }

    private fun encode(sheet: Sheet): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(SHEET_META_MAGIC)
            out.writeByte(SHEET_META_VERSION)
            out.writeUTF(sheet.id.value)
            out.writeUTF(sheet.title)
            out.writeLong(sheet.createdAtEpochMillis)
            out.writeLong(sheet.updatedAtEpochMillis)
            out.writeByte(sheet.template.ordinal)

            writeAnchor(out, sheet.anchor)
        }
        return buffer.toByteArray()
    }

    private fun writeAnchor(out: DataOutputStream, anchor: SheetAnchor?) {
        when (anchor) {
            null -> out.writeByte(ANCHOR_KIND_NONE)

            is SheetAnchor.Page -> {
                out.writeByte(ANCHOR_KIND_PAGE)
                out.writeUTF(anchor.bookId.value)
                out.writeInt(anchor.pageIndex)
                out.writeLong(anchor.rank)
            }

            is SheetAnchor.Text -> {
                out.writeByte(ANCHOR_KIND_TEXT)
                out.writeUTF(anchor.bookId.value)
                out.writeInt(anchor.position.chapterIndex)
                out.writeInt(anchor.position.characterOffset)
                out.writeLong(anchor.rank)
            }
        }
    }

    private fun readPageOnlyAnchor(input: DataInputStream): SheetAnchor? {
        if (!input.readBoolean()) return null

        return SheetAnchor.Page(BookId(input.readUTF()), input.readInt(), rank = 0L)
    }

    private fun readAnchor(file: File, input: DataInputStream): SheetAnchor? {
        return when (val kind = input.readByte().toInt() and 0xFF) {
            ANCHOR_KIND_NONE -> null

            ANCHOR_KIND_PAGE -> {
                val bookId = BookId(input.readUTF())
                val pageIndex = input.readInt()
                SheetAnchor.Page(bookId, pageIndex, rank = input.readLong())
            }

            ANCHOR_KIND_TEXT -> {
                val bookId = BookId(input.readUTF())
                val position = ReadingPosition(chapterIndex = input.readInt(), characterOffset = input.readInt())
                SheetAnchor.Text(bookId, position, rank = input.readLong())
            }

            else -> throw SheetMetaCorruptException(file, "unknown anchor kind $kind")
        }
    }
}

/**
 * Replaces [file]'s contents with [bytes] atomically: they are written to a `.tmp` sibling first,
 * fsynced, and then renamed over [file], so a reader never observes a half-written file and a crash
 * mid-write leaves the previous file in place with only an orphaned `.tmp` sibling behind. Creates
 * [file]'s directory when missing.
 */
internal fun writeFileAtomically(file: File, bytes: ByteArray) {
    val temp = File(file.parentFile, file.name + SHEET_META_TEMP_SUFFIX)

    file.parentFile?.mkdirs()
    RandomAccessFile(temp, "rw").use { raf ->
        raf.setLength(0)
        raf.write(bytes)
        raf.fd.sync()
    }

    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    syncDirectory(file.parentFile)
}

/**
 * Makes a rename inside [directory] durable. A rename is only a change to the directory's own
 * entries, so syncing the renamed file does not cover it; until the directory itself is synced, a
 * power loss may leave the old name in place. Best-effort: a platform that refuses to open a
 * directory for syncing leaves the rename exactly as durable as it was, and whichever of the two
 * files survives is complete, so nothing is lost either way.
 */
internal fun syncDirectory(directory: File?) {
    if (directory == null) return

    try {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    } catch (_: IOException) {
        return
    }
}
