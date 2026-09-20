package com.folium.reader.core.ink

import com.folium.reader.core.library.BookId
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
private const val SHEET_META_VERSION: Int = 1
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
 * [write] is always atomic: the encoded bytes are written to a `.tmp` sibling first, fsynced, and
 * then renamed over the real file with [Files.move]'s atomic move, so a reader never observes a
 * half-written header and a crash mid-write leaves the previous, still-valid file in place with only
 * an orphaned `.tmp` sibling behind — a file [SheetStore] never looks at when reading a directory's
 * committed metadata.
 *
 * Blocking, synchronous I/O; the caller owns whatever thread it runs on.
 */
internal object SheetMetaFile {

    fun write(file: File, sheet: Sheet) {
        val payload = encode(sheet)
        val temp = File(file.parentFile, file.name + SHEET_META_TEMP_SUFFIX)

        file.parentFile?.mkdirs()
        RandomAccessFile(temp, "rw").use { raf ->
            raf.setLength(0)
            raf.write(payload)
            raf.fd.sync()
        }

        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(file.parentFile)
    }

    /** Reads [file], or throws [SheetMetaCorruptException] when it exists but does not parse. */
    fun read(file: File): Sheet {
        try {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                if (input.readInt() != SHEET_META_MAGIC) throw SheetMetaCorruptException(file, "bad magic")

                val version = input.readByte().toInt() and 0xFF
                if (version != SHEET_META_VERSION) throw SheetMetaCorruptException(file, "unsupported version $version")

                val id = SheetId(input.readUTF())
                val title = input.readUTF()
                val createdAt = input.readLong()
                val updatedAt = input.readLong()

                val templateOrdinal = input.readByte().toInt() and 0xFF
                val template = SheetTemplate.entries.getOrNull(templateOrdinal)
                    ?: throw SheetMetaCorruptException(file, "unknown template ordinal $templateOrdinal")

                val anchor = if (input.readBoolean()) {
                    SheetAnchor(BookId(input.readUTF()), input.readInt())
                } else {
                    null
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

            val anchor = sheet.anchor
            out.writeBoolean(anchor != null)
            if (anchor != null) {
                out.writeUTF(anchor.bookId.value)
                out.writeInt(anchor.pageIndex)
            }
        }
        return buffer.toByteArray()
    }
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
