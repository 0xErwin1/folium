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
import com.folium.reader.core.text.TextWord
import java.util.concurrent.atomic.AtomicBoolean

internal class RoomTextPageIndex(
    private val database: TextPageDatabase,
    private val publicationFence: TextPagePublicationFence = TextPagePublicationFences.isolated(),
    private val closeDatabase: () -> Unit = database::close
) : TextPageIndex {
    private val dao = database.textPageDao()
    private val closed = AtomicBoolean()
    private val closeMonitor = Any()
    private var deferredClose: DeferredExclusiveCleanup? = null

    override fun prepareDocument(bookId: BookId, documentVersion: DocumentContentVersion) {
        check(!closed.get()) { "text index is closed" }
        rejectVoidMutationDuringPublication("prepareDocument")
        locked {
            check(!closed.get()) { "text index is closed" }
            transaction {
                val active = dao.activeDocument(bookId.value)
                if (active?.documentVersion == documentVersion.value) return@transaction

                dao.deleteRows(dao.bookPageIds(bookId.value))
                dao.deleteActiveSources(bookId.value)
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
                    dao.deleteRows(dao.documentPageIds(bookId.value, documentVersion.value))
                    dao.deleteActiveSources(bookId.value)
                    dao.upsertActiveDocument(activeDocument.copy(textSchemaVersion = textSchemaVersion))
                }
                dao.deleteRows(
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

    override fun load(key: TextPageIndexKey): TextPage? {
        if (closed.get()) return null
        return locked {
            if (closed.get()) return@locked null
            transaction {
                if (!isActive(key)) return@transaction null
                val entity = exact(key) ?: return@transaction null
                if (entity.state != TextPageIndexState.COMPLETE.name) return@transaction null
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
                    key.engineVersion.value
                ).associate {
                    it.pageIndex to TextPageIndexState.valueOf(it.state)
                }
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
                dao.deleteRows(
                    dao.pageIds(
                        key.bookId.value,
                        key.documentVersion.value,
                        key.textSchemaVersion,
                        key.pageIndex
                    )
                )
                val pageId = dao.insertPage(key.entity(TextPageIndexState.COMPLETE))
                val words = page.toWordEntities(pageId)
                dao.insertWords(words)
                dao.insertFonts(page.toFontEntities(pageId))
                dao.insertSearch(
                    TextPageSearchEntity(pageId, page.text, TextPageMatcher.normalizeLiteral(page.text))
                )
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

    override fun searchIfCurrent(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        query: String,
        publication: (List<TextPageSearchHit>) -> Unit
    ): TextPagePublicationOutcome {
        require(query.isNotBlank())
        if (closed.get()) return TextPagePublicationOutcome.NOT_CURRENT
        return locked {
            if (closed.get()) return@locked TextPagePublicationOutcome.NOT_CURRENT
            val snapshot = transaction { searchSnapshot(bookId, documentVersion, query) }
                ?: return@locked TextPagePublicationOutcome.NOT_CURRENT
            publicationFence.publishing { publication(snapshot.hits) }
            if (closed.get()) return@locked TextPagePublicationOutcome.INVALIDATED_DURING_PUBLICATION
            if (transaction { activeSearchToken(bookId, documentVersion) } == snapshot.token) {
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
        key.engineVersion.value
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
        isActive(key) && exact(key)?.state == TextPageIndexState.COMPLETE.name

    private fun searchSnapshot(
        bookId: BookId,
        documentVersion: DocumentContentVersion,
        query: String
    ): SearchSnapshot? {
        val token = activeSearchToken(bookId, documentVersion) ?: return null
        val candidates = dao.search(
            bookId.value,
            documentVersion.value,
            TextPageMatcher.normalizeLiteral(query)
        )
        val hits = candidates.flatMap { entity ->
            val page = restore(entity)
            TextPageMatcher.find(page, query).mapIndexed { occurrence, match ->
                match.toSearchHit(entity.pageIndex, TextSource.valueOf(entity.source), occurrence)
            }
        }
        return SearchSnapshot(token, hits)
    }

    private fun activeSearchToken(
        bookId: BookId,
        documentVersion: DocumentContentVersion
    ): ActiveSearchToken? {
        val document = dao.activeDocument(bookId.value)
        if (document?.documentVersion != documentVersion.value || document.textSchemaVersion == null) return null
        return ActiveSearchToken(document, dao.activeSources(bookId.value))
    }

    private fun restore(page: TextPageEntity): TextPage {
        val fonts = dao.fonts(page.id).groupBy { Triple(it.blockOrdinal, it.lineOrdinal, it.wordOrdinal) }
        val blocks = dao.words(page.id).groupBy(TextWordEntity::blockOrdinal).toSortedMap().map { (blockOrdinal, blockWords) ->
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
    val hits: List<TextPageSearchHit>
)

private fun keySource(
    bookId: BookId,
    documentVersion: DocumentContentVersion,
    source: TextSource,
    textSchemaVersion: Int,
    engineVersion: TextEngineVersion
) = ActiveTextSourceEntity(
    bookId.value,
    source.name,
    documentVersion.value,
    textSchemaVersion,
    engineVersion.value
)

private fun TextPageIndexKey.entity(state: TextPageIndexState) = TextPageEntity(
    bookId = bookId.value,
    documentVersion = documentVersion.value,
    pageIndex = pageIndex,
    source = source.name,
    textSchemaVersion = textSchemaVersion,
    engineVersion = engineVersion.value,
    state = state.name
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
