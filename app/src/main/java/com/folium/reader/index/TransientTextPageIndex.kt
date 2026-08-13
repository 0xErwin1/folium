package com.folium.reader.index

import com.folium.reader.core.library.BookId
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextPageMatcher
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextPageMatchResult
import com.folium.reader.core.text.TextSearchSpec
import com.folium.reader.core.text.hasUsableNativeText
import com.folium.reader.core.ocr.OcrFailureMetadata
import com.folium.reader.core.ocr.OcrPageStateReducer
import com.folium.reader.core.ocr.OcrStateTransition
import java.util.concurrent.atomic.AtomicBoolean
import java.util.TreeSet

/** Session-only fallback used when the derived Room index cannot be made ready. */
internal class TransientTextPageIndex(
    internal val fallbackFailure: Throwable? = null,
    private val publicationFence: TextPagePublicationFence = TextPagePublicationFences.isolated(),
    private val onOcrPlanRowsExamined: (Int) -> Unit = {}
) : TextPageIndex {
    private data class ActiveSource(
        val documentVersion: DocumentContentVersion,
        val schemaVersion: Int,
        val engineVersion: TextEngineVersion,
        val nativeEngineVersion: TextEngineVersion? = null,
        val usabilityPolicyVersion: String? = null
    )

    private data class OcrOwner(
        val bookId: BookId,
        val documentVersion: DocumentContentVersion,
        val textSchemaVersion: Int,
        val nativeEngineVersion: TextEngineVersion,
        val usabilityPolicyVersion: String,
        val ocrEngineVersion: TextEngineVersion
    )

    private val closed = AtomicBoolean()
    private val closeMonitor = Any()
    private var deferredClose: DeferredExclusiveCleanup? = null
    private val stateLock = Any()
    private val documents = mutableMapOf<BookId, Pair<DocumentContentVersion, Int?>>()
    private val sources = mutableMapOf<Pair<BookId, TextSource>, ActiveSource>()
    private val states = mutableMapOf<TextPageIndexKey, TextPageIndexState>()
    private val pages = mutableMapOf<TextPageIndexKey, TextPage>()
    private val ocrStates = mutableMapOf<OcrPageKey, OcrPageStatus>()
    private val plannableOcrPagesByOwner = mutableMapOf<OcrOwner, TreeSet<Int>>()

    override fun prepareDocument(bookId: BookId, documentVersion: DocumentContentVersion) {
        check(!closed.get()) { "text index is closed" }
        rejectVoidMutationDuringPublication("prepareDocument")
        locked {
            check(!closed.get()) { "text index is closed" }
            synchronized(stateLock) {
                val active = documents[bookId]
                if (active?.first == documentVersion) return@synchronized
                ocrStates.keys.filter { it.bookId == bookId && it.documentVersion != documentVersion }.forEach {
                    OcrPageStateReducer.stale(ocrStates.getValue(it)).status?.let { stale ->
                        putOcrState(it, stale)
                    }
                }
                removeBook(bookId)
                documents[bookId] = documentVersion to null
            }
        }
    }

    override fun prepareSource(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        source: TextSource,
        textSchemaVersion: Int,
        engineVersion: TextEngineVersion
    ): TextPageIndexWriteOutcome {
        if (closed.get()) return TextPageIndexWriteOutcome.STALE
        rejectWriteDuringPublication()?.let { return it }
        return locked {
            synchronized(stateLock) {
                val document = documents[bookId]
                if (closed.get() || document?.first != documentVersion) {
                    return@synchronized TextPageIndexWriteOutcome.STALE
                }
                if (document.second != null && document.second != textSchemaVersion) {
                    removePages(bookId)
                    sources.keys.removeAll { it.first == bookId }
                }
                documents[bookId] = documentVersion to textSchemaVersion
                val sourceKey = bookId to source
                val active = ActiveSource(documentVersion, textSchemaVersion, engineVersion)
                if (sources[sourceKey] != active) {
                    val stale = states.keys.filter { it.bookId == bookId && it.source == source }
                    stale.forEach { states.remove(it); pages.remove(it) }
                }
                sources[sourceKey] = active
                TextPageIndexWriteOutcome.APPLIED
            }
        }
    }

    override fun load(key: TextPageIndexKey): TextPage? {
        if (closed.get()) return null
        return locked {
            if (closed.get()) return@locked null
            synchronized(stateLock) {
                pages[key].takeIf {
                    isCurrent(key) && states[key] == TextPageIndexState.COMPLETE &&
                        (key.source != TextSource.OCR || isCompletedOcrTextCurrent(key))
                }
            }
        }
    }

    override fun state(key: TextPageIndexKey): TextPageIndexState? {
        if (closed.get()) return null
        return locked {
            if (closed.get()) return@locked null
            synchronized(stateLock) { states[key].takeIf { isCurrent(key) } }
        }
    }

    override fun pageStatesIfCurrent(key: TextPageIndexKey): Map<Int, TextPageIndexState>? {
        if (closed.get()) return null
        return locked {
            synchronized(stateLock) {
                if (!isCurrent(key)) return@synchronized null
                states.filterKeys {
                    isCurrent(it) && it.bookId == key.bookId && it.documentVersion == key.documentVersion &&
                        it.source == key.source && it.textSchemaVersion == key.textSchemaVersion &&
                        it.engineVersion == key.engineVersion
                }.entries
                    .sortedBy { it.key.pageIndex }
                    .associate { it.key.pageIndex to it.value }
            }
        }
    }

    override fun searchCoverageIfCurrent(
        nativeKey: TextPageIndexKey,
        ocrKey: OcrPageKey?
    ): TextSearchCoverageSnapshot? = locked {
        synchronized(stateLock) {
            if (!isCurrent(nativeKey) || ocrKey != null && !isOcrOwnerCurrent(ocrKey)) {
                return@synchronized null
            }
            val pageIndexes = states.keys.asSequence()
                .filter { key ->
                    key.bookId == nativeKey.bookId && key.documentVersion == nativeKey.documentVersion &&
                        key.pageIndex >= 0
                }
                .map(TextPageIndexKey::pageIndex)
                .toSortedSet()
            TextSearchCoverageSnapshot(pageIndexes.associateWith { pageIndex ->
                val native = nativeKey.copy(pageIndex = pageIndex)
                val nativeState = states[native]
                val nativePage = pages[native]
                val status = ocrKey?.copy(pageIndex = pageIndex)?.let(ocrStates::get)
                when {
                    nativeState == TextPageIndexState.FAILED -> TextSearchPageCoverage.FAILED
                    nativeState != TextPageIndexState.COMPLETE -> TextSearchPageCoverage.PENDING
                    nativePage?.hasUsableNativeText() == true -> TextSearchPageCoverage.PROCESSED
                    status?.state == OcrPageState.COMPLETED -> TextSearchPageCoverage.PROCESSED
                    status?.state == OcrPageState.FAILED -> TextSearchPageCoverage.FAILED
                    status?.state == OcrPageState.CANCELLED &&
                        status.cancellationReason != com.folium.reader.core.ocr.OcrCancellationReason.NATIVE_TEXT ->
                        TextSearchPageCoverage.CANCELLED
                    else -> TextSearchPageCoverage.PENDING
                }
            })
        }
    }

    override fun markInProgress(key: TextPageIndexKey): TextPageIndexStartResult {
        if (closed.get()) return TextPageIndexStartResult(TextPageIndexWriteOutcome.STALE)
        rejectWriteDuringPublication()?.let { return TextPageIndexStartResult(it) }
        return locked {
            synchronized(stateLock) {
                if (!isCurrent(key)) return@synchronized TextPageIndexStartResult(TextPageIndexWriteOutcome.STALE)
                val previous = states[key]
                if (previous != TextPageIndexState.COMPLETE) states[key] = TextPageIndexState.IN_PROGRESS
                TextPageIndexStartResult(TextPageIndexWriteOutcome.APPLIED, previous)
            }
        }
    }

    override fun complete(key: TextPageIndexKey, page: TextPage): TextPageIndexWriteOutcome {
        require(page.source == key.source)
        if (closed.get()) return TextPageIndexWriteOutcome.STALE
        rejectWriteDuringPublication()?.let { return it }
        return locked {
            synchronized(stateLock) {
                if (!isCurrent(key)) return@synchronized TextPageIndexWriteOutcome.STALE
                states.keys.filter { it.bookId == key.bookId && it.documentVersion == key.documentVersion &&
                    it.textSchemaVersion == key.textSchemaVersion && it.pageIndex == key.pageIndex &&
                    it.source == key.source }
                    .forEach { states.remove(it); pages.remove(it) }
                states[key] = TextPageIndexState.COMPLETE
                pages[key] = page
                TextPageIndexWriteOutcome.APPLIED
            }
        }
    }

    override fun markFailed(key: TextPageIndexKey): TextPageIndexWriteOutcome {
        if (closed.get()) return TextPageIndexWriteOutcome.STALE
        rejectWriteDuringPublication()?.let { return it }
        return locked {
            synchronized(stateLock) {
                if (!isCurrent(key)) return@synchronized TextPageIndexWriteOutcome.STALE
                if (states[key] != TextPageIndexState.COMPLETE) states[key] = TextPageIndexState.FAILED
                TextPageIndexWriteOutcome.APPLIED
            }
        }
    }

    override fun prepareOcr(key: OcrPageKey): OcrTransitionOutcome {
        if (closed.get()) return OcrTransitionOutcome.STALE
        if (publicationFence.isPublishingOnCurrentThread()) return OcrTransitionOutcome.REJECTED_DURING_PUBLICATION
        return locked {
            synchronized(stateLock) {
                if (!isOcrBaseOwnerCurrent(key)) return@synchronized OcrTransitionOutcome.STALE
                ocrStates.keys.filter {
                    it.bookId == key.bookId && (
                        it.documentVersion != key.documentVersion ||
                            it.textSchemaVersion != key.textSchemaVersion ||
                            it.nativeEngineVersion != key.nativeEngineVersion ||
                            it.usabilityPolicyVersion != key.usabilityPolicyVersion ||
                            it.ocrEngineVersion != key.ocrEngineVersion
                        )
                }.forEach { stale ->
                    val previous = ocrStates.getValue(stale)
                    OcrPageStateReducer.stale(previous).status?.let { putOcrState(stale, it) }
                }
                sources[key.bookId to TextSource.OCR] = ActiveSource(
                    key.documentVersion, key.textSchemaVersion, key.ocrEngineVersion,
                    key.nativeEngineVersion, key.usabilityPolicyVersion
                )
                pages.filter { (textKey, _) ->
                    textKey.bookId == key.bookId && textKey.documentVersion == key.documentVersion &&
                        textKey.source == TextSource.NATIVE_PDF && textKey.textSchemaVersion == key.textSchemaVersion &&
                        textKey.engineVersion == key.nativeEngineVersion && states[textKey] == TextPageIndexState.COMPLETE
                }.forEach { (textKey, nativePage) ->
                    val pageKey = key.copy(pageIndex = textKey.pageIndex)
                    val recovered = ocrStates[pageKey]?.let(OcrPageStateReducer::recover)?.status
                    OcrPageStateReducer.reconcile(recovered, nativePage.hasUsableNativeText()).status?.let {
                        putOcrState(pageKey, it)
                    }
                }
                OcrTransitionOutcome.APPLIED
            }
        }
    }

    override fun ocrStatus(key: OcrPageKey): OcrPageStatus? = locked {
        synchronized(stateLock) {
            ocrStates[key].takeIf { isOcrOwnerCurrent(key) && it?.state != OcrPageState.STALE }
        }
    }

    override fun planOcr(
        key: OcrPageKey,
        preferredPage: Int,
        afterPage: Int,
        beforePage: Int,
        limit: Int
    ): OcrPlanningBatch {
        require(limit > 0)
        require(afterPage in -1 until beforePage)
        return locked {
            synchronized(stateLock) {
                if (!isOcrOwnerCurrent(key)) {
                    return@synchronized OcrPlanningBatch(emptyList(), afterPage, rangeExhausted = true)
                }
                val preferred = ocrStates[key.copy(pageIndex = preferredPage)]
                    ?.takeIf(OcrPageStatus::isSearchPlannable)
                    ?.let { preferredPage }
                val rangeLimit = limit - if (preferred == null) 0 else 1
                val rows = if (rangeLimit == 0) emptyList() else {
                    plannableOcrPagesByOwner[key.owner()]
                        ?.subSet(afterPage + 1, true, beforePage, false)
                        .orEmpty()
                        .asSequence()
                        .take(rangeLimit)
                        .toList()
                }
                onOcrPlanRowsExamined(rows.size + if (preferred == null) 0 else 1)
                val ordered = listOfNotNull(preferred) + rows.filter { pageIndex ->
                    pageIndex != preferredPage &&
                        ocrStates[key.copy(pageIndex = pageIndex)]?.isSearchPlannable() == true
                }
                OcrPlanningBatch(
                    ordered,
                    rows.lastOrNull() ?: afterPage,
                    rangeExhausted = rows.size < rangeLimit,
                    queuedAvailable = ordered.any { pageIndex ->
                        ocrStates[key.copy(pageIndex = pageIndex)]?.state == OcrPageState.QUEUED
                    },
                    pausedAvailable = ordered.any { pageIndex ->
                        ocrStates[key.copy(pageIndex = pageIndex)]?.cancellationReason ==
                            com.folium.reader.core.ocr.OcrCancellationReason.SEARCH_PAUSE
                    }
                )
            }
        }
    }

    override fun completeNativeAndReconcile(
        key: TextPageIndexKey,
        page: TextPage,
        ocrKey: OcrPageKey
    ): TextPageIndexWriteOutcome {
        require(key.source == TextSource.NATIVE_PDF && page.source == TextSource.NATIVE_PDF)
        if (!key.isCompatibleNativeOwner(ocrKey)) return TextPageIndexWriteOutcome.STALE
        if (closed.get()) return TextPageIndexWriteOutcome.STALE
        rejectWriteDuringPublication()?.let { return it }
        return locked {
            synchronized(stateLock) {
                if (!isCurrent(key) || !isOcrOwnerCurrent(ocrKey)) return@synchronized TextPageIndexWriteOutcome.STALE
                replacePage(key, page)
                val usable = page.hasUsableNativeText()
                val transition = OcrPageStateReducer.reconcile(ocrStates[ocrKey], usable)
                transition.status?.let { putOcrState(ocrKey, it) }
                if (usable) removePage(ocrKey.textKey())
                TextPageIndexWriteOutcome.APPLIED
            }
        }
    }

    override fun claimOcr(key: OcrPageKey): OcrTransition = mutateOcr(key) {
        OcrPageStateReducer.claim(it).toAppTransition(key)
    }

    override fun completeOcr(attempt: OcrAttempt, page: TextPage): OcrTransition {
        require(page.source == TextSource.OCR)
        return mutateOcr(attempt.key) { current ->
            val reported = OcrPageStateReducer.complete(
                current, attempt.generation,
                pages[nativeKey(attempt.key)]?.hasUsableNativeText() == true
            ).toAppTransition()
            if (reported.outcome == OcrTransitionOutcome.APPLIED) {
                replacePage(attempt.key.textKey(), page)
            }
            if (reported.outcome == OcrTransitionOutcome.NOT_ELIGIBLE) removePage(attempt.key.textKey())
            reported
        }
    }

    override fun failOcr(attempt: OcrAttempt, failureKind: String, retryable: Boolean): OcrTransition =
        mutateOcr(attempt.key) {
            OcrPageStateReducer.fail(
                it, attempt.generation, OcrFailureMetadata(failureKind, retryable)
            ).toAppTransition()
        }

    override fun cancelOcr(
        attempt: OcrAttempt,
        reason: com.folium.reader.core.ocr.OcrCancellationReason
    ): OcrTransition = mutateOcr(attempt.key) {
        OcrPageStateReducer.cancel(it, attempt.generation, reason).toAppTransition()
    }

    override fun resumePausedOcr(key: OcrPageKey): OcrTransition = mutateOcr(key) { current ->
        OcrPageStateReducer.resumeSearch(
            current, pages[nativeKey(key)]?.hasUsableNativeText() == true
        ).toAppTransition(key)
    }

    override fun retryOcr(key: OcrPageKey): OcrTransition = mutateOcr(key) { current ->
        OcrPageStateReducer.retry(
            current, pages[nativeKey(key)]?.hasUsableNativeText() == true
        ).toAppTransition()
    }

    override fun loadSelected(nativeKey: TextPageIndexKey, ocrKey: OcrPageKey): TextPage? = locked {
        synchronized(stateLock) {
            if (!isCurrent(nativeKey) || !isOcrOwnerCurrent(ocrKey)) return@synchronized null
            val native = pages[nativeKey]?.takeIf { states[nativeKey] == TextPageIndexState.COMPLETE }
            native?.takeIf { it.hasUsableNativeText() }
                ?: pages[ocrKey.textKey()]?.takeIf {
                    states[ocrKey.textKey()] == TextPageIndexState.COMPLETE &&
                        ocrStates[ocrKey]?.state == OcrPageState.COMPLETED
                } ?: native
        }
    }

    override fun <T> runPublicationCallback(publication: () -> T): T =
        publicationFence.publishing(publication)

    override fun publishIfCurrent(
        key: TextPageIndexKey,
        publication: () -> Unit
    ): TextPagePublicationOutcome {
        if (closed.get()) return TextPagePublicationOutcome.NOT_CURRENT
        return locked {
            if (closed.get()) return@locked TextPagePublicationOutcome.NOT_CURRENT
            if (synchronized(stateLock) { !isPublishable(key) }) {
                return@locked TextPagePublicationOutcome.NOT_CURRENT
            }
            publicationFence.publishing(publication)
            if (synchronized(stateLock) { isPublishable(key) }) TextPagePublicationOutcome.CURRENT
            else TextPagePublicationOutcome.INVALIDATED_DURING_PUBLICATION
        }
    }

    override fun publishIfSelected(
        key: TextPageIndexKey,
        publication: () -> Unit
    ): TextPagePublicationOutcome {
        if (closed.get()) return TextPagePublicationOutcome.NOT_CURRENT
        return locked {
            if (closed.get()) return@locked TextPagePublicationOutcome.NOT_CURRENT
            if (synchronized(stateLock) { !isSelected(key) }) {
                return@locked TextPagePublicationOutcome.NOT_CURRENT
            }
            publicationFence.publishing(publication)
            if (synchronized(stateLock) { isSelected(key) }) TextPagePublicationOutcome.CURRENT
            else TextPagePublicationOutcome.INVALIDATED_DURING_PUBLICATION
        }
    }

    override fun searchIfCurrent(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        query: String,
        includeOcr: Boolean,
        limit: Int,
        publication: (TextPageSearchResult) -> Unit
    ): TextPagePublicationOutcome = searchIfCurrent(
        bookId, documentVersion, TextSearchSpec(query), includeOcr, limit, publication
    )

    override fun searchIfCurrent(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        spec: TextSearchSpec,
        includeOcr: Boolean,
        limit: Int,
        publication: (TextPageSearchResult) -> Unit
    ): TextPagePublicationOutcome {
        require(spec.query.isNotBlank())
        if (closed.get()) return TextPagePublicationOutcome.NOT_CURRENT
        return locked {
            if (closed.get()) return@locked TextPagePublicationOutcome.NOT_CURRENT
            val hits = synchronized(stateLock) {
                if (documents[bookId]?.first != documentVersion || closed.get()) {
                    return@locked TextPagePublicationOutcome.NOT_CURRENT
                }
                var remaining = limit
                var truncated = false
                val found = pages.filterKeys { isCurrent(it) && states[it] == TextPageIndexState.COMPLETE }
                    .toList().groupBy { it.first.pageIndex }.toSortedMap().values
                    .mapNotNull { candidates ->
                        val native = candidates.firstOrNull { it.first.source == TextSource.NATIVE_PDF }
                        native?.takeIf { it.second.hasUsableNativeText() }
                            ?: candidates.firstOrNull {
                                includeOcr && it.first.source == TextSource.OCR &&
                                    isCompletedOcrTextCurrent(it.first)
                            } ?: native
                    }
                    .flatMap { (key, page) ->
                        val matches = when (val result = TextPageMatcher.find(page, spec, limit = remaining)) {
                            is TextPageMatchResult.Success -> {
                                truncated = truncated || result.truncated
                                result.matches
                            }
                            is TextPageMatchResult.Failure -> emptyList()
                        }
                        remaining -= matches.size
                        matches.mapIndexed { occurrence, match ->
                            match.toSearchHit(key.pageIndex, key.source, occurrence)
                        }
                    }
                TextPageSearchResult(found, truncated)
            }
            publicationFence.publishing { publication(hits) }
            if (synchronized(stateLock) { documents[bookId]?.first == documentVersion && !closed.get() }) {
                TextPagePublicationOutcome.CURRENT
            } else TextPagePublicationOutcome.INVALIDATED_DURING_PUBLICATION
        }
    }

    override fun close() {
        val publicationCallback = publicationFence.isPublishingOnCurrentThread()
        var schedule = false
        val cleanup = synchronized(closeMonitor) {
            deferredClose ?: DeferredExclusiveCleanup {
                synchronized(stateLock) {
                    documents.clear(); sources.clear(); states.clear(); pages.clear(); ocrStates.clear()
                    plannableOcrPagesByOwner.clear()
                }
            }.also {
                closed.set(true)
                deferredClose = it
                schedule = true
            }
        }
        if (schedule) publicationFence.runOrDefer(cleanup)
        if (!publicationCallback) cleanup.await()
    }

    internal fun isClosed(): Boolean = closed.get()

    private fun isCurrent(key: TextPageIndexKey): Boolean {
        if (closed.get() || documents[key.bookId] != (key.documentVersion to key.textSchemaVersion)) return false
        val source = sources[key.bookId to key.source] ?: return false
        return source.documentVersion == key.documentVersion &&
            source.schemaVersion == key.textSchemaVersion && source.engineVersion == key.engineVersion
    }

    private fun isPublishable(key: TextPageIndexKey): Boolean =
        isCurrent(key) && states[key] == TextPageIndexState.COMPLETE &&
            (key.source != TextSource.OCR || isCompletedOcrTextCurrent(key))

    private fun isSelected(key: TextPageIndexKey): Boolean {
        if (!isCurrent(key) || states[key] != TextPageIndexState.COMPLETE) return false
        val native = nativeKeyFor(key)
        pages[native]?.takeIf { states[native] == TextPageIndexState.COMPLETE }
            ?.takeIf(TextPage::hasUsableNativeText)?.let { return key == native }
        val ocr = ocrTextKeyFor(key) ?: return false
        val completedOcr = states[ocr] == TextPageIndexState.COMPLETE && isCompletedOcrTextCurrent(ocr)
        return if (completedOcr) key == ocr else key == native && states[native] == TextPageIndexState.COMPLETE
    }

    private fun nativeKeyFor(key: TextPageIndexKey): TextPageIndexKey {
        val native = sources[key.bookId to TextSource.NATIVE_PDF]
        return TextPageIndexKey(key.bookId, key.documentVersion, key.pageIndex,
            TextSource.NATIVE_PDF, key.textSchemaVersion,
            native?.engineVersion ?: key.engineVersion)
    }

    private fun ocrTextKeyFor(key: TextPageIndexKey): TextPageIndexKey? {
        val ocr = sources[key.bookId to TextSource.OCR] ?: return null
        return TextPageIndexKey(key.bookId, key.documentVersion, key.pageIndex,
            TextSource.OCR, key.textSchemaVersion, ocr.engineVersion)
    }

    private fun removeBook(bookId: BookId) {
        documents.remove(bookId)
        sources.keys.removeAll { it.first == bookId }
        removePages(bookId)
    }

    private fun removePages(bookId: BookId) {
        states.keys.filter { it.bookId == bookId }.forEach { states.remove(it); pages.remove(it) }
    }

    private fun isOcrOwnerCurrent(key: OcrPageKey): Boolean =
        isOcrBaseOwnerCurrent(key) &&
            sources[key.bookId to TextSource.OCR] == ActiveSource(
                key.documentVersion, key.textSchemaVersion, key.ocrEngineVersion,
                key.nativeEngineVersion, key.usabilityPolicyVersion
            )

    private fun isOcrBaseOwnerCurrent(key: OcrPageKey): Boolean =
        documents[key.bookId] == (key.documentVersion to key.textSchemaVersion) &&
            sources[key.bookId to TextSource.NATIVE_PDF] == ActiveSource(
                key.documentVersion, key.textSchemaVersion, key.nativeEngineVersion
            )

    private fun isCompletedOcrTextCurrent(key: TextPageIndexKey): Boolean {
        val native = sources[key.bookId to TextSource.NATIVE_PDF] ?: return false
        val ocr = sources[key.bookId to TextSource.OCR] ?: return false
        return ocrStates.any { (ocrKey, status) ->
            ocrKey.bookId == key.bookId && ocrKey.documentVersion == key.documentVersion &&
                ocrKey.pageIndex == key.pageIndex && ocrKey.textSchemaVersion == key.textSchemaVersion &&
                ocrKey.nativeEngineVersion == native.engineVersion &&
                ocrKey.usabilityPolicyVersion == ocr.usabilityPolicyVersion &&
                ocrKey.ocrEngineVersion == key.engineVersion &&
                status.state == OcrPageState.COMPLETED
        }
    }

    private fun mutateOcr(key: OcrPageKey, block: (OcrPageStatus?) -> OcrTransition): OcrTransition {
        if (closed.get()) return OcrTransition(OcrTransitionOutcome.STALE)
        if (publicationFence.isPublishingOnCurrentThread()) {
            return OcrTransition(OcrTransitionOutcome.REJECTED_DURING_PUBLICATION)
        }
        return locked {
            synchronized(stateLock) {
                if (!isOcrOwnerCurrent(key)) return@synchronized OcrTransition(OcrTransitionOutcome.STALE)
                block(ocrStates[key]).also { result -> result.status?.let { putOcrState(key, it) } }
            }
        }
    }

    private fun replacePage(key: TextPageIndexKey, page: TextPage) {
        removePage(key)
        states[key] = TextPageIndexState.COMPLETE
        pages[key] = page
    }

    private fun putOcrState(key: OcrPageKey, status: OcrPageStatus) {
        ocrStates[key] = status
        val pages = plannableOcrPagesByOwner.getOrPut(key.owner(), ::TreeSet)
        pages.remove(key.pageIndex)
        if (status.isSearchPlannable()) pages.add(key.pageIndex)
    }

    private fun OcrPageKey.owner() = OcrOwner(
        bookId,
        documentVersion,
        textSchemaVersion,
        nativeEngineVersion,
        usabilityPolicyVersion,
        ocrEngineVersion
    )

    private fun removePage(key: TextPageIndexKey) {
        states.remove(key)
        pages.remove(key)
    }

    private fun nativeKey(key: OcrPageKey) = TextPageIndexKey(
        key.bookId, key.documentVersion, key.pageIndex, TextSource.NATIVE_PDF,
        key.textSchemaVersion, key.nativeEngineVersion
    )

    private fun <T> locked(block: () -> T): T = publicationFence.locked(block)

    private fun rejectWriteDuringPublication(): TextPageIndexWriteOutcome? =
        TextPageIndexWriteOutcome.REJECTED_DURING_PUBLICATION
            .takeIf { publicationFence.isPublishingOnCurrentThread() }

    private fun rejectVoidMutationDuringPublication(operation: String) {
        check(!publicationFence.isPublishingOnCurrentThread()) {
            "$operation cannot run reentrantly from a text publication callback"
        }
    }
}

private fun OcrStateTransition.toAppTransition(key: OcrPageKey? = null): OcrTransition = OcrTransition(
    outcome,
    status,
    key?.takeIf { outcome == OcrTransitionOutcome.APPLIED && status?.state == OcrPageState.RUNNING }
        ?.let { OcrAttempt(it, requireNotNull(status).generation) }
)
