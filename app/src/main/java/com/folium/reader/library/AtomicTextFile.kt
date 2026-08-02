package com.folium.reader.library

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * A text file written all at once and read back as lines, with the write made atomic against a
 * crash: the new content lands in a same-directory temporary file, is flushed and fsynced, and
 * only then takes the target's place with a single rename. A same-directory rename inside
 * app-private storage is atomic, so a crash mid-write leaves the previous complete file in place —
 * never a truncated one.
 */
class AtomicTextFile(private val file: File) {

    /** Empty when the file is missing or cannot be read, rather than throwing. */
    fun readLines(): List<String> =
        runCatching { if (file.isFile) file.readLines() else emptyList() }.getOrDefault(emptyList())

    /** Returns whether the write succeeded. The previous content survives on failure. */
    fun write(lines: List<String>): Boolean {
        val parent = file.parentFile ?: throw IOException("AtomicTextFile requires a parent directory")
        parent.mkdirs()

        val tmp = File(parent, "${file.name}.tmp")
        val renamed = runCatching {
            FileOutputStream(tmp).use { stream ->
                val writer = stream.bufferedWriter()
                lines.forEach { line ->
                    writer.write(line)
                    writer.write("\n")
                }
                writer.flush()
                stream.fd.sync()
            }
            tmp.renameTo(file)
        }.getOrDefault(false)

        if (!renamed) {
            tmp.delete()
        }

        return renamed
    }
}
