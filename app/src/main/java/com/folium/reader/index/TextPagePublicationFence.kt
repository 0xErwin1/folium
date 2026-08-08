package com.folium.reader.index

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class TextPagePublicationFence internal constructor(private val lock: ReentrantLock) {
    private val publicationDepth = ThreadLocal<Int>()

    fun <T> locked(block: () -> T): T = lock.withLock(block)

    /**
     * Publication callbacks may close their repository, but must not mutate any repository sharing
     * this fence. Reentrant writes would make values already delivered by the callback stale before
     * the callback returns. Depth is thread-local because [ReentrantLock] permits same-thread entry.
     */
    fun <T> publishing(block: () -> T): T {
        publicationDepth.set((publicationDepth.get() ?: 0) + 1)
        return try {
            block()
        } finally {
            val remaining = requireNotNull(publicationDepth.get()) - 1
            if (remaining == 0) publicationDepth.remove() else publicationDepth.set(remaining)
        }
    }

    fun isPublishingOnCurrentThread(): Boolean = (publicationDepth.get() ?: 0) > 0
}

internal object TextPagePublicationFences {
    private val registryLock = Any()
    private val named = mutableMapOf<String, TextPagePublicationFence>()

    /**
     * Named locks intentionally live for the process lifetime. The practical cost is one tiny lock
     * for Folium's single production index path, and retaining it prevents a reentrant repository
     * close from removing the lock while another instance for the same path is being created.
     */
    fun named(databaseIdentity: String): TextPagePublicationFence {
        require(databaseIdentity.isNotBlank())
        return synchronized(registryLock) {
            named.getOrPut(databaseIdentity) { TextPagePublicationFence(ReentrantLock(true)) }
        }
    }

    /** In-memory databases are unrelated unless a test explicitly supplies the same named fence. */
    fun isolated(): TextPagePublicationFence = TextPagePublicationFence(ReentrantLock(true))
}
