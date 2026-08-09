package com.folium.reader.index

import com.folium.reader.core.library.BookId
import com.folium.reader.core.text.TextEngineVersion
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextPageMatcher
import com.folium.reader.core.text.TextSource
import java.util.concurrent.atomic.AtomicBoolean

/** Session-only fallback used when the derived Room index cannot be made ready. */
internal class TransientTextPageIndex(
    internal val fallbackFailure: Throwable? = null,
    private val publicationFence: TextPagePublicationFence = TextPagePublicationFences.isolated()
) : TextPageIndex {
    private data class ActiveSource(
        val documentVersion: DocumentContentVersion,
        val schemaVersion: Int,
        val engineVersion: TextEngineVersion
    )

    private val closed = AtomicBoolean()
    private val closeMonitor = Any()
    private var deferredClose: DeferredExclusiveCleanup? = null
    private val stateLock = Any()
    private val documents = mutableMapOf<BookId, Pair<DocumentContentVersion, Int?>>()
    private val sources = mutableMapOf<Pair<BookId, TextSource>, ActiveSource>()
    private val states = mutableMapOf<TextPageIndexKey, TextPageIndexState>()
    private val pages = mutableMapOf<TextPageIndexKey, TextPage>()

    override fun prepareDocument(bookId: BookId, documentVersion: DocumentContentVersion) {
        check(!closed.get()) { "text index is closed" }
        rejectVoidMutationDuringPublication("prepareDocument")
        locked {
            check(!closed.get()) { "text index is closed" }
            synchronized(stateLock) {
                val active = documents[bookId]
                if (active?.first == documentVersion) return@synchronized
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
                pages[key].takeIf { isCurrent(key) && states[key] == TextPageIndexState.COMPLETE }
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
                    it.textSchemaVersion == key.textSchemaVersion && it.pageIndex == key.pageIndex }
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
            val hits = synchronized(stateLock) {
                if (documents[bookId]?.first != documentVersion || closed.get()) {
                    return@locked TextPagePublicationOutcome.NOT_CURRENT
                }
                pages.filterKeys { isCurrent(it) && states[it] == TextPageIndexState.COMPLETE }
                    .toList()
                    .sortedWith(compareBy({ it.first.pageIndex }, { it.first.source.ordinal }))
                    .flatMap { (key, page) ->
                        TextPageMatcher.find(page, query).mapIndexed { occurrence, match ->
                            match.toSearchHit(key.pageIndex, key.source, occurrence)
                        }
                    }
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
                    documents.clear(); sources.clear(); states.clear(); pages.clear()
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
        return sources[key.bookId to key.source] == ActiveSource(key.documentVersion, key.textSchemaVersion, key.engineVersion)
    }

    private fun isPublishable(key: TextPageIndexKey): Boolean =
        isCurrent(key) && states[key] == TextPageIndexState.COMPLETE

    private fun removeBook(bookId: BookId) {
        documents.remove(bookId)
        sources.keys.removeAll { it.first == bookId }
        removePages(bookId)
    }

    private fun removePages(bookId: BookId) {
        states.keys.filter { it.bookId == bookId }.forEach { states.remove(it); pages.remove(it) }
    }

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
