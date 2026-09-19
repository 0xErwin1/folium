package com.folium.reader.index

import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.text.TextBlock
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextFont
import com.folium.reader.core.text.TextLine
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextPageMatcher
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextSearchMode
import com.folium.reader.core.text.TextSearchProgram
import com.folium.reader.core.text.TextSearchSpec
import com.folium.reader.core.text.TextWord
import com.folium.reader.core.text.MAX_TEXT_SEARCH_RESULTS
import com.folium.reader.core.text.hasUsableNativeText
import com.folium.reader.core.ocr.OcrCancellationReason
import com.folium.reader.core.ocr.OcrFailureMetadata
import com.folium.reader.core.ocr.OcrPageStateReducer
import com.folium.reader.core.ocr.OcrStateTransition
import com.folium.reader.reader.traced
import java.util.concurrent.atomic.AtomicBoolean

private const val SQLITE_BIND_CHUNK_SIZE = 900
private const val GRAM_BACKFILL_SLICE = 32
private const val NATIVE_USABILITY_BACKFILL_SLICE = 32
private const val SEARCH_PAGE_CHUNK_SIZE = 128
private const val LEGACY_SEARCH_CHUNK_SIZE = 128

internal class RoomTextPageIndex(
    private val database: TextPageDatabase,
    private val publicationFence: TextPagePublicationFence = TextPagePublicationFences.isolated(),
    private val closeDatabase: () -> Unit = database::close,
    private val beforeSearchPublication: () -> Unit = {},
    private val onGramMutation: (Int) -> Unit = {},
    private val onLegacySearchChunk: (Int) -> Unit = {},
    private val onOcrPlanRowsExamined: (Int) -> Unit = {},
    private val beforeCoverageLock: () -> Unit = {},
    private val beforeDerivedMaintenance: () -> Unit = {},
    private val onDerivedMaintenanceRead: () -> Unit = {},
    private val afterDerivedMaintenanceMutation: () -> Unit = {}
) : TextPageIndex {
    private val dao = database.textPageDao()
    private val closed = AtomicBoolean()
    private val closeMonitor = Any()
    private var deferredClose: DeferredExclusiveCleanup? = null
    private var derivedRevision = 0L

    override fun prepareDocument(bookId: BookId, documentVersion: DocumentContentVersion) {
        check(!closed.get()) { "text index is closed" }
        rejectVoidMutationDuringPublication("prepareDocument")
        locked {
            check(!closed.get()) { "text index is closed" }
            transaction {
                val active = dao.activeDocument(bookId.value)
                if (active?.documentVersion == documentVersion.value) return@transaction

                staleOcrStates(bookId) { it.documentVersion != documentVersion.value }
                deleteRowsChunked(dao.bookPageIds(bookId.value))
                dao.deleteActiveSources(bookId.value)
                dao.deleteLayoutUsageForBook(bookId.value)
                dao.upsertActiveDocument(ActiveTextDocumentEntity(bookId.value, documentVersion.value, null))
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
            if (closed.get()) return@locked TextPageIndexWriteOutcome.STALE
            transaction {
                val activeDocument = dao.activeDocument(bookId.value)
                if (activeDocument?.documentVersion != documentVersion.value) {
                    return@transaction TextPageIndexWriteOutcome.STALE
                }

                if (activeDocument.textSchemaVersion != textSchemaVersion) {
                    deleteRowsChunked(dao.documentPageIds(bookId.value, documentVersion.value))
                    dao.deleteActiveSources(bookId.value)
                    dao.upsertActiveDocument(activeDocument.copy(textSchemaVersion = textSchemaVersion))
                }
                deleteRowsChunked(
                    dao.staleSourceIds(
                        bookId.value,
                        documentVersion.value,
                        source.name,
                        textSchemaVersion,
                        engineVersion.value
                    )
                )
                dao.upsertActiveSource(keySource(bookId, documentVersion, source, textSchemaVersion, engineVersion))
                TextPageIndexWriteOutcome.APPLIED
            }
        }
    }

    /**
     * A no-op for a fixed-layout document ([layoutVersion] empty) and while [documentVersion] is
     * not the book's active one — a session repaginating a document that [prepareDocument] or
     * [prepareSource] has since moved past must not resurrect or evict anything for it.
     *
     * Recording [layoutVersion]'s use and reading back which layouts rank beyond
     * [RETAINED_LAYOUTS_PER_BOOK] each run inside their own transaction, and every stale layout is
     * evicted in a further transaction of its own, so a process kill between any two of these steps
     * leaves every already-committed step durable and every not-yet-evicted layout fully intact —
     * never a layout with some of its pages gone and some still there.
     */
    override fun retainRecentLayouts(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        layoutVersion: String
    ) {
        if (layoutVersion.isEmpty() || closed.get()) return
        locked {
            if (closed.get()) return@locked
            val active = transaction { dao.activeDocument(bookId.value) }
            if (active?.documentVersion != documentVersion.value) return@locked

            transaction { recordLayoutUsage(bookId.value, documentVersion.value, layoutVersion) }
            traced({ "folium:text:index:evict-layouts" }) {
                val stale = layoutsBeyondRetention(
                    transaction { dao.layoutVersionsByRecency(bookId.value, documentVersion.value) }
                )
                stale.forEach { staleLayout ->
                    transaction { evictLayout(bookId.value, documentVersion.value, staleLayout) }
                }
            }
        }
    }

    private fun recordLayoutUsage(bookId: String, documentVersion: String, layoutVersion: String) {
        val sequence = dao.nextLayoutUsageSequence(bookId, documentVersion)
        dao.upsertLayoutUsage(TextLayoutUsageEntity(bookId, documentVersion, layoutVersion, sequence))
    }

    private fun evictLayout(bookId: String, documentVersion: String, layoutVersion: String) {
        deleteRowsChunked(dao.layoutPageIds(bookId, documentVersion, layoutVersion))
        dao.deleteLayoutUsage(bookId, documentVersion, layoutVersion)
    }

    override fun load(key: TextPageIndexKey): TextPage? {
        if (closed.get()) return null
        return locked {
            if (closed.get()) return@locked null
            transaction {
                if (!isActive(key)) return@transaction null
                val entity = exact(key) ?: return@transaction null
                if (entity.state != TextPageIndexState.COMPLETE.name) return@transaction null
                if (key.source == TextSource.OCR && !isCompletedOcrTextCurrent(entity)) return@transaction null
                restore(entity)
            }
        }
    }

    override fun state(key: TextPageIndexKey): TextPageIndexState? {
        if (closed.get()) return null
        return locked {
            if (closed.get()) return@locked null
            transaction {
                if (!isActive(key)) return@transaction null
                exact(key)?.state?.let(TextPageIndexState::valueOf)
            }
        }
    }

    override fun pageStatesIfCurrent(key: TextPageIndexKey): Map<Int, TextPageIndexState>? {
        if (closed.get()) return null
        return locked {
            if (closed.get()) return@locked null
            transaction {
                if (!isActive(key)) return@transaction null
                dao.pageStates(
                    key.bookId.value,
                    key.documentVersion.value,
                    key.source.name,
                    key.textSchemaVersion,
                    key.engineVersion.value,
                    key.layoutVersion.orEmpty()
                ).associate {
                    it.pageIndex to TextPageIndexState.valueOf(it.state)
                }
            }
        }
    }

    override fun searchCoverageIfCurrent(
        nativeKey: TextPageIndexKey,
        ocrKey: OcrPageKey?
    ): TextSearchCoverageSnapshot? {
        if (closed.get()) return null
        beforeCoverageLock()
        return locked {
            if (closed.get()) return@locked null
            transaction {
                if (!isActive(nativeKey) || ocrKey != null && !isOcrOwnerCurrent(ocrKey)) {
                    return@transaction null
                }
                searchCoverageSnapshot(nativeKey, ocrKey)
            }
        }
    }

    override fun maintainDerivedDataBatch(
        nativeKey: TextPageIndexKey,
        ocrKey: OcrPageKey?
    ): DerivedMaintenanceResult {
        if (closed.get()) return DerivedMaintenanceResult(false)
        beforeDerivedMaintenance()
        return locked {
            if (closed.get()) return@locked DerivedMaintenanceResult(false)
            transaction {
                if (!isActive(nativeKey)) {
                    return@transaction DerivedMaintenanceResult(false)
                }
                onDerivedMaintenanceRead()
                val unknownPages = dao.unknownNativePages(
                    nativeKey.bookId.value, nativeKey.documentVersion.value,
                    nativeKey.textSchemaVersion, nativeKey.engineVersion.value,
                    nativeKey.layoutVersion.orEmpty(), NATIVE_USABILITY_BACKFILL_SLICE
                )
                val normalizedNativeById = if (unknownPages.isNotEmpty()) {
                    onDerivedMaintenanceRead()
                    dao.searchTextForPages(unknownPages.map(TextPageEntity::id))
                        .associate { row -> row.rowId to row.normalizedText }
                } else emptyMap()
                val ocrOwnerCurrent = ocrKey == null || run {
                    onDerivedMaintenanceRead()
                    isOcrOwnerCurrent(ocrKey)
                }
                val ocrByPage = if (ocrKey != null && ocrOwnerCurrent && unknownPages.isNotEmpty()) {
                    onDerivedMaintenanceRead()
                    dao.exactOcrStatesForPages(
                        ocrKey.bookId.value,
                        ocrKey.documentVersion.value,
                        ocrKey.textSchemaVersion,
                        ocrKey.nativeEngineVersion.value,
                        ocrKey.usabilityPolicyVersion,
                        ocrKey.ocrEngineVersion.value,
                        unknownPages.map(TextPageEntity::pageIndex)
                    ).associateBy(OcrPageStateEntity::pageIndex)
                } else emptyMap()
                unknownPages.forEach { unknown ->
                    val usable = normalizedNativeById[unknown.id]
                        ?.codePoints()?.anyMatch(Character::isLetterOrDigit) == true
                    dao.setNativeUsabilityIfUnknown(unknown.id, usable.toNativeUsability().name)
                    ocrKey?.copy(pageIndex = unknown.pageIndex)?.takeIf { ocrOwnerCurrent }?.let { key ->
                        val recovered = ocrByPage[unknown.pageIndex]
                            ?.toStatus()?.let(OcrPageStateReducer::recover)?.status
                        OcrPageStateReducer.reconcile(recovered, usable).status?.let {
                            dao.upsertOcrState(key.entity(it))
                        }
                    }
                }
                onDerivedMaintenanceRead()
                val missingGrams = dao.searchTextMissingGrams(
                    nativeKey.bookId.value, nativeKey.documentVersion.value, GRAM_BACKFILL_SLICE
                )
                missingGrams.forEach(::persistGrams)
                if (unknownPages.isNotEmpty()) derivedRevision++
                afterDerivedMaintenanceMutation()
                DerivedMaintenanceResult(
                    morePending = unknownPages.size == NATIVE_USABILITY_BACKFILL_SLICE ||
                        missingGrams.size == GRAM_BACKFILL_SLICE,
                    coverage = if (unknownPages.isNotEmpty() && ocrOwnerCurrent) {
                        searchCoverageSnapshot(nativeKey, ocrKey)
                    } else null
                )
            }
        }
    }

    override fun markInProgress(key: TextPageIndexKey): TextPageIndexStartResult {
        if (closed.get()) return TextPageIndexStartResult(TextPageIndexWriteOutcome.STALE)
        rejectWriteDuringPublication()?.let { return TextPageIndexStartResult(it) }
        return locked {
            if (closed.get()) return@locked TextPageIndexStartResult(TextPageIndexWriteOutcome.STALE)
            transaction {
                if (!isActive(key)) return@transaction TextPageIndexStartResult(TextPageIndexWriteOutcome.STALE)
                val existing = exact(key)
                val previous = existing?.state?.let(TextPageIndexState::valueOf)
                if (previous == TextPageIndexState.COMPLETE) {
                    return@transaction TextPageIndexStartResult(TextPageIndexWriteOutcome.APPLIED, previous)
                }
                if (existing == null) {
                    dao.insertPage(key.entity(TextPageIndexState.IN_PROGRESS))
                } else {
                    dao.deleteSearch(listOf(existing.id))
                    dao.deleteWords(existing.id)
                    dao.deleteFonts(existing.id)
                    dao.updateState(existing.id, TextPageIndexState.IN_PROGRESS.name)
                }
                TextPageIndexStartResult(TextPageIndexWriteOutcome.APPLIED, previous)
            }
        }
    }

    override fun complete(key: TextPageIndexKey, page: TextPage): TextPageIndexWriteOutcome {
        require(page.source == key.source)
        if (closed.get()) return TextPageIndexWriteOutcome.STALE
        rejectWriteDuringPublication()?.let { return it }
        return locked {
            if (closed.get()) return@locked TextPageIndexWriteOutcome.STALE
            transaction {
                if (!isActive(key)) return@transaction TextPageIndexWriteOutcome.STALE
                persistPage(key, page)
                TextPageIndexWriteOutcome.APPLIED
            }
        }
    }

    override fun markFailed(key: TextPageIndexKey): TextPageIndexWriteOutcome {
        if (closed.get()) return TextPageIndexWriteOutcome.STALE
        rejectWriteDuringPublication()?.let { return it }
        return locked {
            if (closed.get()) return@locked TextPageIndexWriteOutcome.STALE
            transaction {
                if (!isActive(key)) return@transaction TextPageIndexWriteOutcome.STALE
                val existing = exact(key)
                if (existing == null) dao.insertPage(key.entity(TextPageIndexState.FAILED))
                else if (existing.state != TextPageIndexState.COMPLETE.name) {
                    dao.updateState(existing.id, TextPageIndexState.FAILED.name)
                }
                TextPageIndexWriteOutcome.APPLIED
            }
        }
    }

    override fun prepareOcr(key: OcrPageKey): OcrTransitionOutcome {
        if (closed.get()) return OcrTransitionOutcome.STALE
        if (publicationFence.isPublishingOnCurrentThread()) return OcrTransitionOutcome.REJECTED_DURING_PUBLICATION
        return locked {
            transaction {
                if (!isOcrBaseOwnerCurrent(key)) return@transaction OcrTransitionOutcome.STALE
                staleOcrStates(key.bookId) { entity -> !entity.matchesOwnership(key) }
                dao.upsertActiveSource(keySource(key.bookId, key.documentVersion, TextSource.OCR,
                    key.textSchemaVersion, key.ocrEngineVersion, key.nativeEngineVersion,
                    key.usabilityPolicyVersion))
                dao.completedNativePages(key.bookId.value, key.documentVersion.value,
                    key.textSchemaVersion, key.nativeEngineVersion.value).forEach { native ->
                    val usability = NativeTextUsability.valueOf(native.nativeUsability)
                    if (usability == NativeTextUsability.UNKNOWN) return@forEach
                    val pageKey = key.copy(pageIndex = native.pageIndex)
                    val recovered = exactOcr(pageKey)?.toStatus()?.let(OcrPageStateReducer::recover)?.status
                    val reconciled = OcrPageStateReducer.reconcile(
                        recovered, usability == NativeTextUsability.USABLE
                    )
                    reconciled.status?.let { dao.upsertOcrState(pageKey.entity(it)) }
                }
                OcrTransitionOutcome.APPLIED
            }
        }
    }

    override fun ocrStatus(key: OcrPageKey): OcrPageStatus? {
        if (closed.get()) return null
        return locked { transaction {
            if (!isOcrOwnerCurrent(key)) return@transaction null
            exactOcr(key)?.takeIf { it.state != OcrPageState.STALE.name }?.toStatus()
        } }
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
        if (closed.get()) return OcrPlanningBatch(emptyList(), afterPage, rangeExhausted = true)
        return locked {
            transaction {
                if (!isOcrOwnerCurrent(key)) {
                    return@transaction OcrPlanningBatch(emptyList(), afterPage, rangeExhausted = true)
                }
                val preferredStatus = exactOcr(key.copy(pageIndex = preferredPage))
                    ?.toStatus()
                    ?.takeIf(OcrPageStatus::isSearchPlannable)
                val preferred = preferredStatus?.let { preferredPage }
                val rangeLimit = limit - if (preferred == null) 0 else 1
                val queued = if (rangeLimit == 0) emptyList() else planningSlice(
                    key, afterPage, beforePage, rangeLimit, dao::queuedOcrPlanningSlice
                )
                val paused = if (rangeLimit == 0) emptyList() else planningSlice(
                    key, afterPage, beforePage, rangeLimit, dao::pausedOcrPlanningSlice
                )
                onOcrPlanRowsExamined(
                    queued.size + paused.size + if (preferred == null) 0 else 1
                )
                val availableProgressPages = (queued + paused).asSequence()
                    .map(OcrPageStateEntity::pageIndex)
                    .filter { it != preferredPage }
                    .distinct()
                    .sorted()
                    .toList()
                val progressPages = availableProgressPages.asSequence()
                    .take(rangeLimit)
                    .toList()
                val pages = listOfNotNull(preferred) + progressPages
                OcrPlanningBatch(
                    pages,
                    progressPages.lastOrNull() ?: afterPage,
                    rangeExhausted = rangeLimit > 0 &&
                        availableProgressPages.size <= rangeLimit &&
                        queued.size < rangeLimit && paused.size < rangeLimit,
                    queuedAvailable = preferredStatus?.state == OcrPageState.QUEUED ||
                        queued.isNotEmpty(),
                    pausedAvailable = preferredStatus?.cancellationReason ==
                        OcrCancellationReason.SEARCH_PAUSE || paused.isNotEmpty()
                )
            }
        }
    }

    private fun planningSlice(
        key: OcrPageKey,
        afterPage: Int,
        beforePage: Int,
        limit: Int,
        query: (String, String, Int, String, String, String, Int, Int, Int) -> List<OcrPageStateEntity>
    ): List<OcrPageStateEntity> = query(
        key.bookId.value,
        key.documentVersion.value,
        key.textSchemaVersion,
        key.nativeEngineVersion.value,
        key.usabilityPolicyVersion,
        key.ocrEngineVersion.value,
        afterPage,
        beforePage,
        limit
    )

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
            transaction {
                if (!isActive(key) || !isOcrOwnerCurrent(ocrKey)) return@transaction TextPageIndexWriteOutcome.STALE
                persistPage(key, page)
                val transition = OcrPageStateReducer.reconcile(
                    exactOcr(ocrKey)?.toStatus(), page.hasUsableNativeText()
                )
                transition.status?.let { dao.upsertOcrState(ocrKey.entity(it)) }
                if (page.hasUsableNativeText()) removeText(ocrKey.textKey())
                TextPageIndexWriteOutcome.APPLIED
            }
        }
    }

    override fun claimOcr(key: OcrPageKey): OcrTransition = mutateOcr(key) { current ->
        OcrPageStateReducer.claim(current).toAppTransition(key)
    }

    override fun completeOcr(attempt: OcrAttempt, page: TextPage): OcrTransition {
        require(page.source == TextSource.OCR)
        return mutateOcr(attempt.key) { current ->
            val nativeUsable = currentNativeUsability(attempt.key) == NativeTextUsability.USABLE
            val transition = OcrPageStateReducer.complete(
                current, attempt.generation, nativeUsable
            ).toAppTransition()
            if (transition.outcome == OcrTransitionOutcome.APPLIED) persistPage(attempt.key.textKey(), page)
            if (transition.outcome == OcrTransitionOutcome.NOT_ELIGIBLE) removeText(attempt.key.textKey())
            transition
        }
    }

    override fun failOcr(attempt: OcrAttempt, failureKind: String, retryable: Boolean): OcrTransition =
        mutateOcr(attempt.key) { current ->
            OcrPageStateReducer.fail(
                current, attempt.generation, OcrFailureMetadata(failureKind, retryable)
            ).toAppTransition()
        }

    override fun cancelOcr(
        attempt: OcrAttempt,
        reason: OcrCancellationReason
    ): OcrTransition = mutateOcr(attempt.key) { current ->
        OcrPageStateReducer.cancel(current, attempt.generation, reason).toAppTransition()
    }

    override fun resumePausedOcr(key: OcrPageKey): OcrTransition = mutateOcr(key) { current ->
        OcrPageStateReducer.resumeSearch(
            current, currentNativeUsability(key) == NativeTextUsability.USABLE
        ).toAppTransition(key)
    }

    override fun retryOcr(key: OcrPageKey): OcrTransition = mutateOcr(key) { current ->
        OcrPageStateReducer.retry(
            current, currentNativeUsability(key) == NativeTextUsability.USABLE
        ).toAppTransition()
    }

    override fun loadSelected(nativeKey: TextPageIndexKey, ocrKey: OcrPageKey): TextPage? {
        if (closed.get()) return null
        return locked {
            transaction {
                if (!isActive(nativeKey) || !isOcrOwnerCurrent(ocrKey)) return@transaction null
                selectedCurrentPage(
                    nativeKey.bookId.value,
                    nativeKey.documentVersion.value,
                    nativeKey.pageIndex,
                    nativeKey.textSchemaVersion,
                    nativeKey.layoutVersion.orEmpty()
                )?.page
            }
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
            if (!transaction { isPublishable(key) }) return@locked TextPagePublicationOutcome.NOT_CURRENT
            publicationFence.publishing(publication)
            if (closed.get()) return@locked TextPagePublicationOutcome.INVALIDATED_DURING_PUBLICATION
            if (transaction { isPublishable(key) }) {
                TextPagePublicationOutcome.CURRENT
            } else {
                TextPagePublicationOutcome.INVALIDATED_DURING_PUBLICATION
            }
        }
    }

    override fun publishIfSelected(
        key: TextPageIndexKey,
        publication: () -> Unit
    ): TextPagePublicationOutcome {
        if (closed.get()) return TextPagePublicationOutcome.NOT_CURRENT
        return locked {
            if (closed.get()) return@locked TextPagePublicationOutcome.NOT_CURRENT
            if (!transaction { isSelected(key) }) return@locked TextPagePublicationOutcome.NOT_CURRENT
            publicationFence.publishing(publication)
            if (closed.get()) return@locked TextPagePublicationOutcome.INVALIDATED_DURING_PUBLICATION
            if (transaction { isSelected(key) }) TextPagePublicationOutcome.CURRENT
            else TextPagePublicationOutcome.INVALIDATED_DURING_PUBLICATION
        }
    }

    override fun searchIfCurrent(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        query: String,
        includeOcr: Boolean,
        limit: Int,
        layoutVersion: String?,
        publication: (TextPageSearchResult) -> Unit
    ): TextPagePublicationOutcome = searchIfCurrent(
        bookId, documentVersion, TextSearchSpec(query), includeOcr, limit, layoutVersion, publication
    )

    override fun searchIfCurrent(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        spec: TextSearchSpec,
        includeOcr: Boolean,
        limit: Int,
        layoutVersion: String?,
        publication: (TextPageSearchResult) -> Unit
    ): TextPagePublicationOutcome {
        require(spec.query.isNotBlank())
        val normalizedLayout = layoutVersion.orEmpty()
        if (closed.get()) return TextPagePublicationOutcome.NOT_CURRENT
        return locked {
            if (closed.get()) return@locked TextPagePublicationOutcome.NOT_CURRENT
            val snapshot = transaction {
                searchSnapshot(bookId, documentVersion, spec, includeOcr, limit, normalizedLayout)
            } ?: return@locked TextPagePublicationOutcome.NOT_CURRENT
            beforeSearchPublication()
            if (!transaction { snapshot.winners.all { isWinnerCurrent(it, normalizedLayout) } }) {
                return@locked TextPagePublicationOutcome.NOT_CURRENT
            }
            publicationFence.publishing {
                publication(TextPageSearchResult(
                    snapshot.hits,
                    snapshot.truncated,
                    snapshot.maintenancePending,
                    snapshot.coverage
                ))
            }
            if (closed.get()) return@locked TextPagePublicationOutcome.INVALIDATED_DURING_PUBLICATION
            if (transaction {
                    activeSearchToken(bookId, documentVersion) == snapshot.token &&
                        snapshot.winners.all { isWinnerCurrent(it, normalizedLayout) }
                }) {
                TextPagePublicationOutcome.CURRENT
            } else {
                TextPagePublicationOutcome.INVALIDATED_DURING_PUBLICATION
            }
        }
    }

    override fun close() {
        val publicationCallback = publicationFence.isPublishingOnCurrentThread()
        var schedule = false
        val cleanup = synchronized(closeMonitor) {
            deferredClose ?: DeferredExclusiveCleanup(closeDatabase).also {
                closed.set(true)
                deferredClose = it
                schedule = true
            }
        }
        if (schedule) publicationFence.runOrDefer(cleanup)
        if (!publicationCallback) cleanup.await()
    }

    private fun exact(key: TextPageIndexKey) = dao.exact(
        key.bookId.value,
        key.documentVersion.value,
        key.pageIndex,
        key.source.name,
        key.textSchemaVersion,
        key.engineVersion.value,
        key.layoutVersion.orEmpty()
    )

    private fun isActive(key: TextPageIndexKey): Boolean {
        val document = dao.activeDocument(key.bookId.value)
        if (document?.documentVersion != key.documentVersion.value ||
            document.textSchemaVersion != key.textSchemaVersion
        ) return false
        val source = dao.activeSource(key.bookId.value, key.source.name)
        return source?.documentVersion == key.documentVersion.value &&
            source.textSchemaVersion == key.textSchemaVersion &&
            source.engineVersion == key.engineVersion.value
    }

    private fun isPublishable(key: TextPageIndexKey): Boolean =
        isActive(key) && exact(key)?.let { entity ->
            entity.state == TextPageIndexState.COMPLETE.name &&
                (key.source != TextSource.OCR || isCompletedOcrTextCurrent(entity))
        } == true

    private fun isSelected(key: TextPageIndexKey): Boolean {
        if (!isActive(key)) return false
        val expected = exact(key)?.takeIf { it.state == TextPageIndexState.COMPLETE.name } ?: return false
        return selectedCurrentPage(
            key.bookId.value,
            key.documentVersion.value,
            key.pageIndex,
            key.textSchemaVersion,
            key.layoutVersion.orEmpty()
        )?.entity?.id == expected.id
    }

    private fun searchSnapshot(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        spec: TextSearchSpec,
        includeOcr: Boolean,
        limit: Int,
        layoutVersion: String
    ): SearchSnapshot? {
        require(limit in 0..MAX_TEXT_SEARCH_RESULTS)
        val token = activeSearchToken(bookId, documentVersion) ?: return null
        val activeNative = token.sources.firstOrNull { it.source == TextSource.NATIVE_PDF.name }
        val unresolvedNative = activeNative?.let { source ->
            dao.unknownNativePages(
                bookId.value, documentVersion.value, source.textSchemaVersion,
                source.engineVersion, layoutVersion, 1
            ).isNotEmpty()
        } == true
        val program = TextPageMatcher.compile(spec)
        if (program !is TextSearchProgram.Compiled) {
            return SearchSnapshot(
                token,
                emptyList(),
                emptyList(),
                false,
                unresolvedNative,
                searchCoverageSnapshot(token, includeOcr, layoutVersion)
            )
        }
        val candidates = searchCandidates(bookId, documentVersion, spec)
        if (candidates.isEmpty()) {
            return SearchSnapshot(
                token,
                emptyList(),
                emptyList(),
                false,
                unresolvedNative,
                searchCoverageSnapshot(token, includeOcr, layoutVersion)
            )
        }
        val candidateIds = candidates.mapTo(mutableSetOf(), TextPageEntity::id)
        val pageIndexes = candidates.map(TextPageEntity::pageIndex).distinct()
        val nativeSource = token.sources.firstOrNull { it.source == TextSource.NATIVE_PDF.name }
        val ocrSource = token.sources.firstOrNull { it.source == TextSource.OCR.name }
        val hits = mutableListOf<TextPageSearchHit>()
        val winners = mutableListOf<SelectedWinnerToken>()
        var maintenancePending = unresolvedNative
        var truncated = false
        pageIndexes.chunked(SEARCH_PAGE_CHUNK_SIZE).forEach { indexes ->
            if (truncated) return@forEach
            // Rows of another layout are excluded in SQL: a candidate whose only hit is under a
            // stale layout's row simply finds nothing here and drops out, harmlessly — see
            // [TextPageDao.completePagesForIndexes].
            val pages = dao.completePagesForIndexes(bookId.value, documentVersion.value, indexes, layoutVersion)
            val unknownPages = pages.filter {
                it.source == TextSource.NATIVE_PDF.name && it.usability() == NativeTextUsability.UNKNOWN
            }
            if (unknownPages.isNotEmpty()) maintenancePending = true
            val knownPages = pages - unknownPages.toSet()
            val restored = restorePages(knownPages)
            val completedOcrStates = dao.completedOcrStatesForPages(
                bookId.value, documentVersion.value, indexes
            ).groupBy(OcrPageStateEntity::pageIndex)
            pages.groupBy(TextPageEntity::pageIndex).toSortedMap().values.forEach { pageSources ->
                if (truncated) return@forEach
                val nativeEntity = pageSources.firstOrNull { it.source == TextSource.NATIVE_PDF.name }
                if (nativeEntity?.usability() == NativeTextUsability.UNKNOWN) return@forEach
                val native = nativeEntity?.let {
                    SelectedCurrentPage(it, requireNotNull(restored[it.id]), usability = it.usability())
                }
                val completedOcr = pageSources.firstOrNull { entity ->
                    includeOcr && entity.source == TextSource.OCR.name &&
                        completedOcrStates[entity.pageIndex].orEmpty().any { state ->
                            state.matchesCurrent(entity, nativeSource, ocrSource)
                        }
                }?.let { entity ->
                    val generation = completedOcrStates[entity.pageIndex].orEmpty().firstOrNull { state ->
                        state.matchesCurrent(entity, nativeSource, ocrSource)
                    }?.generation ?: return@let null
                    SelectedCurrentPage(entity, requireNotNull(restored[entity.id]), generation,
                        NativeTextUsability.UNUSABLE)
                }
                val selected = selectCurrentPage(native, completedOcr, includeOcr) ?: return@forEach
                val entity = selected.entity
                winners += SelectedWinnerToken(entity.bookId, entity.documentVersion, entity.pageIndex,
                    entity.textSchemaVersion, entity.source, entity.id, selected.ocrGeneration)
                if (entity.id !in candidateIds) return@forEach
                val remaining = (limit - hits.size).coerceAtLeast(0)
                when (val result = program.find(selected.page, limit = remaining)) {
                    is com.folium.reader.core.text.TextPageMatchResult.Success -> {
                        hits += result.matches.mapIndexed { occurrence, match ->
                            match.toSearchHit(entity.pageIndex, TextSource.valueOf(entity.source), occurrence)
                        }
                        truncated = result.truncated
                    }
                    is com.folium.reader.core.text.TextPageMatchResult.Failure -> Unit
                }
            }
        }
        return SearchSnapshot(
            token,
            winners,
            hits,
            truncated,
            maintenancePending,
            searchCoverageSnapshot(token, includeOcr, layoutVersion)
        )
    }

    private fun searchCoverageSnapshot(
        nativeKey: TextPageIndexKey,
        ocrKey: OcrPageKey?
    ): TextSearchCoverageSnapshot {
        val ocrByPage = ocrKey?.let { key ->
            dao.ocrCoverage(
                key.bookId.value,
                key.documentVersion.value,
                key.textSchemaVersion,
                key.nativeEngineVersion.value,
                key.usabilityPolicyVersion,
                key.ocrEngineVersion.value
            ).associateBy(OcrPageStateEntity::pageIndex)
        }.orEmpty()
        val pages = dao.nativeCoverage(
            nativeKey.bookId.value,
            nativeKey.documentVersion.value,
            nativeKey.textSchemaVersion,
            nativeKey.engineVersion.value,
            nativeKey.layoutVersion.orEmpty()
        ).associate { native ->
            native.pageIndex to native.searchCoverage(ocrByPage[native.pageIndex])
        }
        return TextSearchCoverageSnapshot(pages, derivedRevision)
    }

    private fun searchCoverageSnapshot(
        token: ActiveSearchToken,
        includeOcr: Boolean,
        layoutVersion: String
    ): TextSearchCoverageSnapshot {
        val native = token.sources.first { it.source == TextSource.NATIVE_PDF.name }
        val ocr = token.sources.firstOrNull { includeOcr && it.source == TextSource.OCR.name }
        val nativeKey = TextPageIndexKey(
            BookId(token.document.bookId),
            DocumentContentVersion(token.document.documentVersion),
            0,
            TextSource.NATIVE_PDF,
            native.textSchemaVersion,
            TextEngineVersion(native.engineVersion),
            layoutVersion
        )
        val ocrKey = ocr?.let {
            OcrPageKey(
                nativeKey.bookId,
                nativeKey.documentVersion,
                0,
                nativeKey.textSchemaVersion,
                nativeKey.engineVersion,
                requireNotNull(it.usabilityPolicyVersion),
                TextEngineVersion(it.engineVersion)
            )
        }
        return searchCoverageSnapshot(nativeKey, ocrKey)
    }

    private fun searchCandidates(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        spec: TextSearchSpec
    ): List<TextPageEntity> {
        if (spec.mode == TextSearchMode.REGEX) {
            // One compile for the whole sweep, and one chunk of page text resident at a time: the
            // previous shape compiled and preflighted the pattern twice per page and materialized
            // every page's text before filtering any of it.
            val program = TextPageMatcher.compile(spec) as? TextSearchProgram.Compiled ?: return emptyList()
            val current = dao.allCurrentCompletePages(bookId.value, documentVersion.value)
            val matchingIds = current.map(TextPageEntity::id)
                .chunked(SQLITE_BIND_CHUNK_SIZE)
                .flatMapTo(mutableSetOf()) { chunk ->
                    dao.searchTextForPages(chunk)
                        .filter { program.contains(it.pageText) }
                        .map(TextPageDao.SearchTextRow::rowId)
                }
            return current.filter { it.id in matchingIds }
        }
        val normalized = TextPageMatcher.normalizeLiteral(spec.query)
        if (normalized.codePointCount(0, normalized.length) < 3) {
            return dao.search(bookId.value, documentVersion.value, normalized)
        }
        val hashes = normalizedTrigramHashes(normalized).toList()
        val indexedIds = hashes.chunked(SQLITE_BIND_CHUNK_SIZE).let { chunks ->
            // Every page must contain every distinct gram. Intersect chunk results in memory so the
            // SQLite bind limit never makes long queries invalid.
            chunks.map { chunk ->
                dao.pageIdsMatchingAllGrams(
                    bookId.value, documentVersion.value, chunk, chunk.size
                ).toSet()
            }
                .reduceOrNull(Set<Long>::intersect).orEmpty()
        }
        val legacyMatches = mutableListOf<TextPageEntity>()
        var afterPage = -1
        var afterId = -1L
        while (true) {
            val chunk = dao.matchingPagesMissingGramsAfter(
                bookId.value,
                documentVersion.value,
                normalized,
                afterPage,
                afterId,
                LEGACY_SEARCH_CHUNK_SIZE
            )
            if (chunk.isEmpty()) break
            onLegacySearchChunk(chunk.size)
            legacyMatches += chunk
            val last = chunk.last()
            afterPage = last.pageIndex
            afterId = last.id
            if (chunk.size < LEGACY_SEARCH_CHUNK_SIZE) break
        }
        return (indexedIds.chunked(SQLITE_BIND_CHUNK_SIZE).flatMap(dao::pagesByIds) + legacyMatches)
            .distinctBy(TextPageEntity::id)
            .sortedWith(compareBy(TextPageEntity::pageIndex, TextPageEntity::source))
    }

    private fun activeSearchToken(
        bookId: BookId,
        documentVersion: DocumentContentVersion
    ): ActiveSearchToken? {
        val document = dao.activeDocument(bookId.value)
        if (document?.documentVersion != documentVersion.value || document.textSchemaVersion == null) return null
        return ActiveSearchToken(document, dao.activeSources(bookId.value))
    }

    private fun exactOcr(key: OcrPageKey) = dao.exactOcrState(
        key.bookId.value, key.documentVersion.value, key.pageIndex, key.textSchemaVersion,
        key.nativeEngineVersion.value, key.usabilityPolicyVersion, key.ocrEngineVersion.value
    )

    private fun isOcrOwnerCurrent(key: OcrPageKey): Boolean {
        if (!isOcrBaseOwnerCurrent(key)) return false
        val ocr = dao.activeSource(key.bookId.value, TextSource.OCR.name)
        return ocr?.documentVersion == key.documentVersion.value &&
            ocr.textSchemaVersion == key.textSchemaVersion &&
            ocr.engineVersion == key.ocrEngineVersion.value &&
            ocr.nativeEngineVersion == key.nativeEngineVersion.value &&
            ocr.usabilityPolicyVersion == key.usabilityPolicyVersion
    }

    private fun isOcrBaseOwnerCurrent(key: OcrPageKey): Boolean {
        val document = dao.activeDocument(key.bookId.value)
        val native = dao.activeSource(key.bookId.value, TextSource.NATIVE_PDF.name)
        return document?.documentVersion == key.documentVersion.value &&
            document.textSchemaVersion == key.textSchemaVersion &&
            native?.documentVersion == key.documentVersion.value &&
            native.textSchemaVersion == key.textSchemaVersion &&
            native.engineVersion == key.nativeEngineVersion.value
    }

    private fun isCompletedOcrTextCurrent(entity: TextPageEntity): Boolean {
        return completedOcrStatusFor(entity) != null
    }

    private fun completedOcrStatusFor(entity: TextPageEntity): OcrPageStatus? {
        val native = dao.activeSource(entity.bookId, TextSource.NATIVE_PDF.name) ?: return null
        val ocr = dao.activeSource(entity.bookId, TextSource.OCR.name) ?: return null
        if (ocr.documentVersion != entity.documentVersion ||
            ocr.textSchemaVersion != entity.textSchemaVersion ||
            ocr.engineVersion != entity.engineVersion ||
            ocr.nativeEngineVersion != native.engineVersion
        ) return null
        return dao.completedOcrState(entity.bookId, entity.documentVersion, entity.pageIndex,
            entity.textSchemaVersion, native.engineVersion,
            ocr.usabilityPolicyVersion ?: return null, entity.engineVersion)?.toStatus()
    }

    private fun isWinnerCurrent(token: SelectedWinnerToken, layoutVersion: String): Boolean {
        val selected = selectedCurrentPage(
            token.bookId,
            token.documentVersion,
            token.pageIndex,
            token.textSchemaVersion,
            layoutVersion
        ) ?: return false
        return selected.entity.id == token.pageId &&
            selected.entity.source == token.source &&
            selected.ocrGeneration == token.ocrGeneration
    }

    /**
     * [layoutVersion] must be the layout of the page being asked about, not [ActiveTextSourceEntity]'s
     * own — that row names a book's active engine/schema pointer, shared by every layout a reflowable
     * book has ever been indexed under, and never carried a layout of its own. Looking a page up under
     * the wrong layout's rows would either miss text that is actually there, or — worse — return
     * another layout's text for this page index, since page indexes mean different text per layout.
     */
    private fun selectedCurrentPage(
        bookId: String,
        documentVersion: String,
        pageIndex: Int,
        textSchemaVersion: Int,
        layoutVersion: String
    ): SelectedCurrentPage? {
        val document = dao.activeDocument(bookId)
        if (document?.documentVersion != documentVersion ||
            document.textSchemaVersion != textSchemaVersion
        ) return null
        val nativeSource = dao.activeSource(bookId, TextSource.NATIVE_PDF.name)?.takeIf {
            it.documentVersion == documentVersion && it.textSchemaVersion == textSchemaVersion
        }
        val native = nativeSource?.let { source ->
            dao.exact(bookId, documentVersion, pageIndex, TextSource.NATIVE_PDF.name,
                textSchemaVersion, source.engineVersion, layoutVersion)
        }?.takeIf { it.state == TextPageIndexState.COMPLETE.name }
            ?.let { entity ->
                val usability = ensureNativeUsability(entity)
                SelectedCurrentPage(entity.copy(nativeUsability = usability.name), restore(entity), usability = usability)
            }
        val ocrSource = dao.activeSource(bookId, TextSource.OCR.name)?.takeIf {
            nativeSource != null &&
                it.documentVersion == documentVersion &&
                it.textSchemaVersion == textSchemaVersion &&
                it.nativeEngineVersion == nativeSource.engineVersion &&
                it.usabilityPolicyVersion != null
        }
        val completedOcr = ocrSource?.let { source ->
            dao.exact(bookId, documentVersion, pageIndex, TextSource.OCR.name,
                textSchemaVersion, source.engineVersion, layoutVersion)
        }?.takeIf { it.state == TextPageIndexState.COMPLETE.name }
            ?.let { entity ->
                completedOcrStatusFor(entity)?.let { status ->
                    SelectedCurrentPage(entity, restore(entity), status.generation, NativeTextUsability.UNUSABLE)
                }
            }
        return selectCurrentPage(native, completedOcr)
    }

    private fun mutateOcr(key: OcrPageKey, transition: (OcrPageStatus?) -> OcrTransition): OcrTransition {
        if (closed.get()) return OcrTransition(OcrTransitionOutcome.STALE)
        if (publicationFence.isPublishingOnCurrentThread()) {
            return OcrTransition(OcrTransitionOutcome.REJECTED_DURING_PUBLICATION)
        }
        return locked {
            transaction {
                if (!isOcrOwnerCurrent(key)) return@transaction OcrTransition(OcrTransitionOutcome.STALE)
                transition(exactOcr(key)?.toStatus()).also { result ->
                    result.status?.takeIf {
                        result.outcome == OcrTransitionOutcome.APPLIED ||
                            result.outcome == OcrTransitionOutcome.NOT_ELIGIBLE
                    }
                        ?.let { dao.upsertOcrState(key.entity(it)) }
                }
            }
        }
    }

    private fun currentNativeUsability(key: OcrPageKey): NativeTextUsability? = exact(TextPageIndexKey(
        key.bookId, key.documentVersion, key.pageIndex, TextSource.NATIVE_PDF,
        key.textSchemaVersion, key.nativeEngineVersion
    ))?.takeIf { it.state == TextPageIndexState.COMPLETE.name }?.let(::ensureNativeUsability)

    private fun staleOcrStates(bookId: BookId, predicate: (OcrPageStateEntity) -> Boolean) {
        dao.ocrStatesForBook(bookId.value).filter(predicate).forEach { entity ->
            if (entity.state != OcrPageState.STALE.name) {
                OcrPageStateReducer.stale(entity.toStatus()).status?.let { status ->
                    dao.upsertOcrState(entity.toKey().entity(status))
                }
            }
        }
    }

    private fun persistPage(key: TextPageIndexKey, page: TextPage) {
        removeText(key)
        val usability = if (page.source == TextSource.NATIVE_PDF) page.hasUsableNativeText().toNativeUsability()
        else NativeTextUsability.UNUSABLE
        val pageId = dao.insertPage(key.entity(TextPageIndexState.COMPLETE, usability))
        dao.insertWords(page.toWordEntities(pageId))
        dao.insertFonts(page.toFontEntities(pageId))
        val search = TextPageSearchEntity(pageId, page.text, TextPageMatcher.normalizeLiteral(page.text))
        dao.insertSearch(search)
        persistGrams(TextPageDao.SearchTextRow(search.rowId, search.pageText, search.normalizedText))
    }

    private fun persistGrams(search: TextPageDao.SearchTextRow) {
        val grams = normalizedTrigramHashes(search.normalizedText).map {
            TextPageGramEntity(search.rowId, it)
        }
        if (grams.isNotEmpty()) {
            dao.insertGrams(grams)
            onGramMutation(1)
        }
    }

    private fun ensureNativeUsability(entity: TextPageEntity): NativeTextUsability {
        val current = entity.usability()
        if (current != NativeTextUsability.UNKNOWN) return current
        val computed = compactNativeUsability(entity).toNativeUsability()
        dao.setNativeUsabilityIfUnknown(entity.id, computed.name)
        return computed
    }

    private fun removeText(key: TextPageIndexKey) {
        deleteRowsChunked(dao.sourcePageIds(key.bookId.value, key.documentVersion.value,
            key.textSchemaVersion, key.pageIndex, key.source.name))
    }

    private fun compactNativeUsability(entity: TextPageEntity): Boolean =
        dao.searchTextForPages(listOf(entity.id)).firstOrNull()?.normalizedText
            ?.codePoints()?.anyMatch(Character::isLetterOrDigit) == true

    private fun deleteRowsChunked(ids: List<Long>) {
        ids.chunked(SQLITE_BIND_CHUNK_SIZE).forEach(dao::deleteRows)
    }

    private fun restore(page: TextPageEntity): TextPage {
        return restore(page, dao.words(page.id), dao.fonts(page.id))
    }

    private fun restorePages(pages: List<TextPageEntity>): Map<Long, TextPage> {
        if (pages.isEmpty()) return emptyMap()
        val ids = pages.map(TextPageEntity::id)
        val words = ids.chunked(SQLITE_BIND_CHUNK_SIZE).flatMap(dao::wordsForPages)
            .groupBy(TextWordEntity::pageId)
        val fonts = ids.chunked(SQLITE_BIND_CHUNK_SIZE).flatMap(dao::fontsForPages)
            .groupBy(TextFontEntity::pageId)
        return pages.associate { page ->
            page.id to restore(page, words[page.id].orEmpty(), fonts[page.id].orEmpty())
        }
    }

    private fun restore(
        page: TextPageEntity,
        pageWords: List<TextWordEntity>,
        pageFonts: List<TextFontEntity>
    ): TextPage {
        val fonts = pageFonts.groupBy { Triple(it.blockOrdinal, it.lineOrdinal, it.wordOrdinal) }
        val blocks = pageWords.groupBy(TextWordEntity::blockOrdinal).toSortedMap().map { (blockOrdinal, blockWords) ->
            val lines = blockWords.groupBy(TextWordEntity::lineOrdinal).toSortedMap().map { (lineOrdinal, lineWords) ->
                TextLine(lineWords.map { it.toModel(fonts) }, lineOrdinal)
            }
            TextBlock(lines, blockOrdinal)
        }
        return TextPage(blocks, TextSource.valueOf(page.source))
    }

    private fun <T> transaction(block: () -> T): T = database.runInTransaction<T>(block)
    private fun <T> locked(block: () -> T): T = publicationFence.locked(block)

    private fun rejectWriteDuringPublication(): TextPageIndexWriteOutcome? =
        TextPageIndexWriteOutcome.REJECTED_DURING_PUBLICATION
            .takeIf { publicationFence.isPublishingOnCurrentThread() }

    private fun rejectVoidMutationDuringPublication(operation: String) {
        check(!publicationFence.isPublishingOnCurrentThread()) {
            "$operation cannot run reentrantly from a text publication callback"
        }
    }

    companion object {
        fun named(
            database: TextPageDatabase,
            databaseIdentity: String,
            closeDatabase: () -> Unit = database::close
        ): RoomTextPageIndex = RoomTextPageIndex(
            database,
            TextPagePublicationFences.named(databaseIdentity),
            closeDatabase
        )
    }
}

private data class ActiveSearchToken(
    val document: ActiveTextDocumentEntity,
    val sources: List<ActiveTextSourceEntity>
)

private data class SearchSnapshot(
    val token: ActiveSearchToken,
    val winners: List<SelectedWinnerToken>,
    val hits: List<TextPageSearchHit>,
    val truncated: Boolean,
    val maintenancePending: Boolean,
    val coverage: TextSearchCoverageSnapshot
)

private data class SelectedCurrentPage(
    val entity: TextPageEntity,
    val page: TextPage,
    val ocrGeneration: Long? = null,
    val usability: NativeTextUsability
)

private fun selectCurrentPage(
    native: SelectedCurrentPage?,
    completedOcr: SelectedCurrentPage?,
    includeOcr: Boolean = true
): SelectedCurrentPage? =
    native?.takeIf { it.usability == NativeTextUsability.USABLE }
        ?: completedOcr?.takeIf { includeOcr }
        ?: native?.takeIf { it.usability == NativeTextUsability.UNUSABLE }

private data class SelectedWinnerToken(
    val bookId: String,
    val documentVersion: String,
    val pageIndex: Int,
    val textSchemaVersion: Int,
    val source: String,
    val pageId: Long,
    val ocrGeneration: Long?
)

private fun keySource(
    bookId: BookId,
    documentVersion: DocumentContentVersion,
    source: TextSource,
    textSchemaVersion: Int,
    engineVersion: TextEngineVersion,
    nativeEngineVersion: TextEngineVersion? = null,
    usabilityPolicyVersion: String? = null
) = ActiveTextSourceEntity(
    bookId.value,
    source.name,
    documentVersion.value,
    textSchemaVersion,
    engineVersion.value,
    nativeEngineVersion?.value,
    usabilityPolicyVersion
)

private fun TextPageIndexKey.entity(
    state: TextPageIndexState,
    usability: NativeTextUsability = NativeTextUsability.UNKNOWN
) = TextPageEntity(
    bookId = bookId.value,
    documentVersion = documentVersion.value,
    pageIndex = pageIndex,
    source = source.name,
    textSchemaVersion = textSchemaVersion,
    engineVersion = engineVersion.value,
    state = state.name,
    nativeUsability = usability.name,
    layoutVersion = layoutVersion.orEmpty()
)

private fun TextPageEntity.usability(): NativeTextUsability = NativeTextUsability.valueOf(nativeUsability)
private fun Boolean.toNativeUsability() =
    if (this) NativeTextUsability.USABLE else NativeTextUsability.UNUSABLE

private fun TextPageDao.NativeCoverageRow.searchCoverage(
    ocr: OcrPageStateEntity?
): TextSearchPageCoverage = when {
    state == TextPageIndexState.FAILED.name -> TextSearchPageCoverage.FAILED
    state != TextPageIndexState.COMPLETE.name -> TextSearchPageCoverage.PENDING
    nativeUsability == NativeTextUsability.UNKNOWN.name -> TextSearchPageCoverage.PENDING
    nativeUsability == NativeTextUsability.USABLE.name -> TextSearchPageCoverage.PROCESSED
    ocr?.state == OcrPageState.COMPLETED.name -> TextSearchPageCoverage.PROCESSED
    ocr?.state == OcrPageState.FAILED.name -> TextSearchPageCoverage.FAILED
    ocr?.state == OcrPageState.CANCELLED.name &&
        ocr.cancellationReason != OcrCancellationReason.NATIVE_TEXT.name -> TextSearchPageCoverage.CANCELLED
    else -> TextSearchPageCoverage.PENDING
}

/**
 * FNV-1a over the UTF-8 bytes of each code point in a sliding window of three.
 *
 * These hashes are persisted in text_page_grams and matched against hashes derived from the live
 * query, so the output is a storage format: changing it would silently stop every already indexed
 * page from matching. The encoding is therefore folded byte by byte in place rather than going
 * through a String and a ByteArray per code point per window, which allocated on the order of six
 * objects per character of every page being indexed.
 */
internal fun normalizedTrigramHashes(normalized: String): Set<Long> {
    val points = normalized.codePoints().toArray()
    if (points.size < 3) return emptySet()
    return buildSet {
        for (index in 0..points.size - 3) {
            var hash = FNV_OFFSET_BASIS
            for (pointIndex in index..index + 2) {
                hash = foldUtf8(hash, points[pointIndex])
            }
            add(hash)
        }
    }
}

private const val FNV_OFFSET_BASIS = -3750763034362895579L // FNV-1a 64-bit offset basis as signed long.
private const val FNV_PRIME = 1099511628211L

/**
 * Surrogates fold as '?', matching what the JDK's UTF-8 encoder substitutes for a code point it
 * cannot represent. An unpaired surrogate should not survive text normalization, but hashing it
 * differently from the previous implementation would invalidate stored grams for any page where
 * one did.
 */
private fun foldUtf8(seed: Long, codePoint: Int): Long = when {
    codePoint < 0x80 -> seed.foldByte(codePoint)
    codePoint < 0x800 -> seed
        .foldByte(0xC0 or (codePoint shr 6))
        .foldByte(0x80 or (codePoint and 0x3F))
    Character.isSurrogate(codePoint.toChar()) -> seed.foldByte('?'.code)
    codePoint < 0x10000 -> seed
        .foldByte(0xE0 or (codePoint shr 12))
        .foldByte(0x80 or ((codePoint shr 6) and 0x3F))
        .foldByte(0x80 or (codePoint and 0x3F))
    else -> seed
        .foldByte(0xF0 or (codePoint shr 18))
        .foldByte(0x80 or ((codePoint shr 12) and 0x3F))
        .foldByte(0x80 or ((codePoint shr 6) and 0x3F))
        .foldByte(0x80 or (codePoint and 0x3F))
}

private fun Long.foldByte(byte: Int): Long = (this xor (byte.toLong() and 0xff)) * FNV_PRIME

private fun OcrPageKey.entity(status: OcrPageStatus) = OcrPageStateEntity(
    bookId.value,
    documentVersion.value,
    pageIndex,
    textSchemaVersion,
    nativeEngineVersion.value,
    usabilityPolicyVersion,
    ocrEngineVersion.value,
    status.generation,
    status.state.name,
    status.cancellationReason?.name,
    status.failure?.kind,
    status.failure?.retryable
)

private fun OcrPageStateEntity.toStatus() = OcrPageStatus(
    OcrPageState.valueOf(state),
    generation,
    cancellationReason?.let(OcrCancellationReason::valueOf),
    failureKind?.let { OcrFailureMetadata(it, requireNotNull(retryable)) }
)

private fun OcrPageStateEntity.toKey() = OcrPageKey(
    BookId(bookId),
    DocumentContentVersion(documentVersion),
    pageIndex,
    textSchemaVersion,
    TextEngineVersion(nativeEngineVersion),
    usabilityPolicyVersion,
    TextEngineVersion(ocrEngineVersion)
)

private fun OcrPageStateEntity.matchesOwnership(key: OcrPageKey): Boolean =
    documentVersion == key.documentVersion.value &&
        textSchemaVersion == key.textSchemaVersion &&
        nativeEngineVersion == key.nativeEngineVersion.value &&
        usabilityPolicyVersion == key.usabilityPolicyVersion &&
        ocrEngineVersion == key.ocrEngineVersion.value

private fun OcrPageStateEntity.matchesCurrent(
    page: TextPageEntity,
    nativeSource: ActiveTextSourceEntity?,
    ocrSource: ActiveTextSourceEntity?
): Boolean =
    state == OcrPageState.COMPLETED.name &&
        page.source == TextSource.OCR.name &&
        bookId == page.bookId &&
        documentVersion == page.documentVersion &&
        pageIndex == page.pageIndex &&
        textSchemaVersion == page.textSchemaVersion &&
        ocrEngineVersion == page.engineVersion &&
        nativeSource?.documentVersion == documentVersion &&
        nativeSource.textSchemaVersion == textSchemaVersion &&
        nativeSource.engineVersion == nativeEngineVersion &&
        ocrSource?.documentVersion == documentVersion &&
        ocrSource.textSchemaVersion == textSchemaVersion &&
        ocrSource.engineVersion == ocrEngineVersion &&
        ocrSource.nativeEngineVersion == nativeEngineVersion &&
        ocrSource.usabilityPolicyVersion == usabilityPolicyVersion

private fun OcrStateTransition.toAppTransition(key: OcrPageKey? = null): OcrTransition = OcrTransition(
    outcome,
    status,
    key?.takeIf { outcome == OcrTransitionOutcome.APPLIED && status?.state == OcrPageState.RUNNING }
        ?.let { OcrAttempt(it, requireNotNull(status).generation) }
)

private fun TextPage.toWordEntities(pageId: Long): List<TextWordEntity> = blocks.flatMapIndexed { blockOrdinal, block ->
    block.lines.flatMapIndexed { lineOrdinal, line ->
        line.words.mapIndexed { wordOrdinal, word ->
            TextWordEntity(pageId, blockOrdinal, lineOrdinal, wordOrdinal, word.text, word.box.left, word.box.top,
                word.box.right, word.box.bottom, word.languageTag, word.confidence)
        }
    }
}

private fun TextPage.toFontEntities(pageId: Long): List<TextFontEntity> = blocks.flatMapIndexed { blockOrdinal, block ->
    block.lines.flatMapIndexed { lineOrdinal, line ->
        line.words.flatMapIndexed { wordOrdinal, word ->
            word.fonts.mapIndexed { fontOrdinal, font ->
                TextFontEntity(pageId, blockOrdinal, lineOrdinal, wordOrdinal, fontOrdinal, font.name,
                    font.bold, font.italic, font.serif, font.monospaced)
            }
        }
    }
}

private fun TextWordEntity.toModel(fonts: Map<Triple<Int, Int, Int>, List<TextFontEntity>>) = TextWord(
    text = text,
    box = PageSpaceRect(left, top, right, bottom),
    readingOrder = wordOrdinal,
    fonts = fonts[Triple(blockOrdinal, lineOrdinal, wordOrdinal)].orEmpty().map {
        TextFont(it.name, it.bold, it.italic, it.serif, it.monospaced)
    },
    languageTag = languageTag,
    confidence = confidence
)
