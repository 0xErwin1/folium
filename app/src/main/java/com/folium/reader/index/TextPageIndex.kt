package com.folium.reader.index

import com.folium.reader.core.library.BookId
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextPageMatch
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextSearchSpec
import com.folium.reader.core.text.MAX_TEXT_SEARCH_RESULTS
import com.folium.reader.core.ocr.OcrCancellationReason
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
    val engineVersion: TextEngineVersion,
    /**
     * The layout a reflowable book's pagination was extracted under, `null` for a fixed-layout
     * document or an unconfigured reflowable one. Normalized to `""` wherever it is written or
     * matched against storage — see [TextPageEntity.layoutVersion] — so a caller that never supplies
     * one keeps reading and writing exactly the rows it always has.
     */
    val layoutVersion: String? = null
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

internal data class TextPageSearchResult(
    val hits: List<TextPageSearchHit>,
    val truncated: Boolean = false,
    val maintenancePending: Boolean = false,
    val coverage: TextSearchCoverageSnapshot? = null
)

internal enum class TextSearchPageCoverage { PROCESSED, PENDING, FAILED, CANCELLED }

internal data class TextSearchCoverageSnapshot(
    val pages: Map<Int, TextSearchPageCoverage>,
    val revision: Long = 0L
)

internal data class DerivedMaintenanceResult(
    val morePending: Boolean,
    val coverage: TextSearchCoverageSnapshot? = null
)

internal data class OcrPlanningBatch(
    val pageIndexes: List<Int>,
    val nextCursor: Int,
    val rangeExhausted: Boolean,
    val queuedAvailable: Boolean = false,
    val pausedAvailable: Boolean = false
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
    fun searchCoverageIfCurrent(
        nativeKey: TextPageIndexKey,
        ocrKey: OcrPageKey?
    ): TextSearchCoverageSnapshot? = pageStatesIfCurrent(nativeKey)?.let { states ->
        TextSearchCoverageSnapshot(states.mapValues { (_, state) ->
            when (state) {
                TextPageIndexState.COMPLETE -> TextSearchPageCoverage.PROCESSED
                TextPageIndexState.FAILED -> TextSearchPageCoverage.FAILED
                TextPageIndexState.IN_PROGRESS -> TextSearchPageCoverage.PENDING
            }
        })
    }
    /** Performs one bounded derived-metadata slice; returns true when more work may remain. */
    fun maintainDerivedData(nativeKey: TextPageIndexKey, ocrKey: OcrPageKey?): Boolean =
        maintainDerivedDataBatch(nativeKey, ocrKey).morePending
    fun maintainDerivedDataBatch(
        nativeKey: TextPageIndexKey,
        ocrKey: OcrPageKey?
    ): DerivedMaintenanceResult = DerivedMaintenanceResult(false)
    fun markInProgress(key: TextPageIndexKey): TextPageIndexStartResult
    fun complete(key: TextPageIndexKey, page: TextPage): TextPageIndexWriteOutcome
    fun markFailed(key: TextPageIndexKey): TextPageIndexWriteOutcome
    fun prepareOcr(key: OcrPageKey): OcrTransitionOutcome = OcrTransitionOutcome.APPLIED
    fun ocrStatus(key: OcrPageKey): OcrPageStatus? = null
    fun planOcr(
        key: OcrPageKey,
        preferredPage: Int,
        afterPage: Int,
        beforePage: Int,
        limit: Int
    ): OcrPlanningBatch = OcrPlanningBatch(emptyList(), afterPage, rangeExhausted = true)
    fun completeNativeAndReconcile(
        key: TextPageIndexKey,
        page: TextPage,
        ocrKey: OcrPageKey
    ): TextPageIndexWriteOutcome = complete(key, page)
    fun claimOcr(key: OcrPageKey): OcrTransition = OcrTransition(OcrTransitionOutcome.INVALID_STATE)
    fun completeOcr(attempt: OcrAttempt, page: TextPage): OcrTransition =
        OcrTransition(OcrTransitionOutcome.INVALID_STATE)
    fun failOcr(attempt: OcrAttempt, failureKind: String, retryable: Boolean): OcrTransition =
        OcrTransition(OcrTransitionOutcome.INVALID_STATE)
    fun cancelOcr(
        attempt: OcrAttempt,
        reason: OcrCancellationReason = OcrCancellationReason.USER
    ): OcrTransition = OcrTransition(OcrTransitionOutcome.INVALID_STATE)
    fun resumePausedOcr(key: OcrPageKey): OcrTransition = OcrTransition(OcrTransitionOutcome.INVALID_STATE)
    fun retryOcr(key: OcrPageKey): OcrTransition = OcrTransition(OcrTransitionOutcome.INVALID_STATE)
    fun loadSelected(nativeKey: TextPageIndexKey, ocrKey: OcrPageKey): TextPage? =
        load(nativeKey) ?: load(ocrKey.textKey())
    fun <T> runPublicationCallback(publication: () -> T): T = publication()
    fun publishIfCurrent(key: TextPageIndexKey, publication: () -> Unit): TextPagePublicationOutcome
    fun publishIfSelected(key: TextPageIndexKey, publication: () -> Unit): TextPagePublicationOutcome =
        publishIfCurrent(key, publication)
    /**
     * [layoutVersion] scopes the search to the layout a reflowable book's [pageIndex][TextPageIndexKey.pageIndex]
     * currently means, `null` for a fixed-layout document — matched against storage the same way
     * [TextPageIndexKey.layoutVersion] is everywhere else. A row extracted under a different layout is
     * never returned as a hit or a coverage figure: the same page index names different text there.
     */
    fun searchIfCurrent(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        query: String,
        includeOcr: Boolean = true,
        limit: Int = MAX_TEXT_SEARCH_RESULTS,
        layoutVersion: String? = null,
        publication: (TextPageSearchResult) -> Unit
    ): TextPagePublicationOutcome
    fun searchIfCurrent(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        spec: TextSearchSpec,
        includeOcr: Boolean = true,
        limit: Int = MAX_TEXT_SEARCH_RESULTS,
        layoutVersion: String? = null,
        publication: (TextPageSearchResult) -> Unit
    ): TextPagePublicationOutcome = searchIfCurrent(
        bookId, documentVersion, spec.query, includeOcr, limit, layoutVersion, publication
    )
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
