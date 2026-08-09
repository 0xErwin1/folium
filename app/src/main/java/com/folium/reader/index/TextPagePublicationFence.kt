package com.folium.reader.index

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

internal class TextPagePublicationFence internal constructor(private val lock: ReentrantLock) {
    private val publicationDepth = ThreadLocal<Int>()
    private val deferredExclusive = ConcurrentLinkedQueue<DeferredExclusiveCleanup>()

    fun <T> locked(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            if (lock.holdCount == 1) drainDeferredExclusive()
            lock.unlock()
        }
    }

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

    /**
     * Orders cleanup after the current outermost exclusive operation. A publication callback may
     * enqueue without acquiring [lock], which lets a different callback thread return to the lock
     * owner. Every other caller executes synchronously under the same lock.
     */
    fun closeOrDefer(cleanup: () -> Unit): DeferredExclusiveCleanup {
        val deferred = DeferredExclusiveCleanup(cleanup)
        runOrDefer(deferred)
        return deferred
    }

    fun runOrDefer(deferred: DeferredExclusiveCleanup) {
        if (isPublishingOnCurrentThread() && lock.isLocked) {
            deferredExclusive.add(deferred)
        } else {
            locked { deferred.run() }
        }
    }

    private fun drainDeferredExclusive() {
        while (true) deferredExclusive.poll()?.run() ?: return
    }
}

internal class DeferredExclusiveCleanup(private val cleanup: () -> Unit) {
    private val completed = CountDownLatch(1)
    private val failure = AtomicReference<Throwable>()

    fun await() {
        var interrupted = false
        while (completed.count > 0L) {
            try {
                completed.await()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        failure.get()?.let { throw it }
    }

    internal fun isComplete(): Boolean = completed.count == 0L

    internal fun run() {
        if (completed.count == 0L) return
        try {
            cleanup()
        } catch (cleanupFailure: Throwable) {
            failure.compareAndSet(null, cleanupFailure)
        } finally {
            completed.countDown()
        }
    }
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
