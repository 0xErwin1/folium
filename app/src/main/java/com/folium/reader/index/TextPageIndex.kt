package com.folium.reader.index

import com.folium.reader.core.library.BookId
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextPageMatch
import com.folium.reader.core.text.TextSource
import java.io.File
import java.security.MessageDigest

internal const val TEXT_PAGE_SCHEMA_VERSION = 2

@JvmInline
internal value class DocumentContentVersion(val value: String) {
    init { require(value.matches(Regex("[0-9a-f]{64}"))) }
}

internal data class TextPageIndexKey(
    val bookId: BookId,
    val documentVersion: DocumentContentVersion,
    val pageIndex: Int,
    val source: TextSource,
    val textSchemaVersion: Int,
    val engineVersion: TextEngineVersion
) {
    init {
        require(pageIndex >= 0)
        require(textSchemaVersion > 0)
    }
}

internal enum class TextPageIndexState { IN_PROGRESS, COMPLETE, FAILED }
internal enum class TextPageIndexWriteOutcome { APPLIED, STALE, REJECTED_DURING_PUBLICATION }
internal enum class TextPagePublicationOutcome { CURRENT, NOT_CURRENT, INVALIDATED_DURING_PUBLICATION }

internal data class TextPageIndexStartResult(
    val outcome: TextPageIndexWriteOutcome,
    val previousState: TextPageIndexState? = null
)

internal data class TextPageSearchHit(
    val pageIndex: Int,
    val source: TextSource,
    val occurrenceIndex: Int,
    val wordRange: IntRange,
    val boxes: List<com.folium.reader.core.pdf.PageSpaceRect>,
    val snippet: String
) {
    val identity: TextPageSearchIdentity = TextPageSearchIdentity(pageIndex, source, occurrenceIndex)
}

internal data class TextPageSearchIdentity(
    val pageIndex: Int,
    val source: TextSource,
    val occurrenceIndex: Int
)

internal fun TextPageMatch.toSearchHit(pageIndex: Int, source: TextSource, occurrenceIndex: Int) =
    TextPageSearchHit(pageIndex, source, occurrenceIndex, wordRange, boxes, snippet)

/**
 * Durable text storage shared by native extraction and OCR producers. Publication callbacks hold a
 * repository fence for their entire execution. A mutation reentered from such a callback is
 * rejected with [TextPageIndexWriteOutcome.REJECTED_DURING_PUBLICATION], or with
 * [IllegalStateException] for [prepareDocument], so already-delivered content cannot become stale
 * before the callback returns. Repository [close] remains permitted during publication.
 */
internal interface TextPageIndex : AutoCloseable {
    fun prepareDocument(bookId: BookId, documentVersion: DocumentContentVersion)
    fun prepareSource(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        source: TextSource,
        textSchemaVersion: Int,
        engineVersion: TextEngineVersion
    ): TextPageIndexWriteOutcome
    fun load(key: TextPageIndexKey): TextPage?
    fun state(key: TextPageIndexKey): TextPageIndexState?
    fun pageStatesIfCurrent(key: TextPageIndexKey): Map<Int, TextPageIndexState>?
    fun markInProgress(key: TextPageIndexKey): TextPageIndexStartResult
    fun complete(key: TextPageIndexKey, page: TextPage): TextPageIndexWriteOutcome
    fun markFailed(key: TextPageIndexKey): TextPageIndexWriteOutcome
    fun <T> runPublicationCallback(publication: () -> T): T = publication()
    fun publishIfCurrent(key: TextPageIndexKey, publication: () -> Unit): TextPagePublicationOutcome
    fun searchIfCurrent(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        query: String,
        publication: (List<TextPageSearchHit>) -> Unit
    ): TextPagePublicationOutcome
    override fun close() = Unit
}

internal fun sha256(file: File): DocumentContentVersion {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return DocumentContentVersion(digest.digest().joinToString("") { "%02x".format(it) })
}
