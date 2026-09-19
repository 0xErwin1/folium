package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.index.DocumentContentVersion
import java.io.File

/**
 * Bumped only when the hashing algorithm behind [DocumentContentVersion] itself changes, which
 * invalidates every stored record at once because a record's hash means nothing under a different
 * algorithm.
 */
private const val DOCUMENT_HASH_ALGORITHM_VERSION = "1"
private const val DOCUMENT_HASH_MARKER = "document-hash-v1"

private data class DocumentHashRecord(
    val canonicalPath: String,
    val length: Long,
    val lastModifiedMillis: Long,
    val algorithmVersion: String,
    val version: DocumentContentVersion
)

/**
 * Caches [DocumentContentVersion] per book so a document already opened once never has its whole
 * file read again just to name its identity — see
 * [com.folium.reader.reader.textIndexSessionPlan] for why that identity is needed at every open.
 *
 * A record is trusted only when the file's canonical path, length and last-modified time all still
 * match what was recorded, and only under the algorithm version the record itself claims. Anything
 * else — no record, a corrupt one, a record for a different file, an unreadable record — is treated
 * exactly like a first-ever open: [resolve] hashes the file and rewrites the record. A wrong
 * [DocumentContentVersion] would key the text index, reading positions and the disk page cache to
 * the wrong content, so trusting a record only ever saves time; it never changes the answer a fresh
 * hash would have given.
 */
internal class DocumentHashCache(private val paths: LibraryPaths) {

    fun resolve(id: BookId, file: File, hasher: (File) -> DocumentContentVersion): DocumentContentVersion {
        val recordFile = AtomicTextFile(paths.documentHashFile(id))
        val canonicalPath = runCatching { file.canonicalPath }.getOrNull()
        val cached = canonicalPath?.let { readMatching(recordFile, it, file) }
        if (cached != null) return cached.version

        val computed = hasher(file)
        canonicalPath?.let { recordFile.write(encode(it, file, computed)) }
        return computed
    }

    private fun readMatching(recordFile: AtomicTextFile, canonicalPath: String, file: File): DocumentHashRecord? =
        runCatching { decode(recordFile.readLines()) }.getOrNull()?.takeIf { record ->
            record.algorithmVersion == DOCUMENT_HASH_ALGORITHM_VERSION &&
                record.canonicalPath == canonicalPath &&
                record.length == file.length() &&
                record.lastModifiedMillis == file.lastModified()
        }

    private fun decode(lines: List<String>): DocumentHashRecord? {
        if (lines.size < 6 || lines[0] != DOCUMENT_HASH_MARKER) return null
        val length = lines[2].toLongOrNull() ?: return null
        val lastModified = lines[3].toLongOrNull() ?: return null
        return DocumentHashRecord(
            canonicalPath = lines[1],
            length = length,
            lastModifiedMillis = lastModified,
            algorithmVersion = lines[4],
            version = DocumentContentVersion(lines[5])
        )
    }

    private fun encode(canonicalPath: String, file: File, version: DocumentContentVersion): List<String> = listOf(
        DOCUMENT_HASH_MARKER,
        canonicalPath,
        file.length().toString(),
        file.lastModified().toString(),
        DOCUMENT_HASH_ALGORITHM_VERSION,
        version.value
    )
}
