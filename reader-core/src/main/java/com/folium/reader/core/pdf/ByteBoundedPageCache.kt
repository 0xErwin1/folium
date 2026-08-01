package com.folium.reader.core.pdf

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Identifies a single cached render: a page at a specific size within a specific document and
 * generation. Two requests for the same page at different [RenderSpec]s, or from different
 * document generations, are distinct entries — this mirrors [ViewportRenderRequest], which tags
 * every request with the generation active when it was submitted.
 */
data class PageCacheKey(
    val documentId: String,
    val pageIndex: Int,
    val generation: Long,
    val spec: RenderSpec
) {
    init {
        require(documentId.isNotBlank() && pageIndex >= 0) {
            "documentId must be non-blank and pageIndex must be non-negative, got documentId=\"$documentId\" pageIndex=$pageIndex"
        }
    }
}

/**
 * Byte-bounded, engine-neutral cache of [RenderCandidate]s keyed by [PageCacheKey].
 *
 * Accounting is exact: every [put] that is actually retained adds its declared size to the
 * running total, and every removal — via eviction, [invalidateDocument], [invalidatePage],
 * [invalidateStaleGenerations], [trimToBytes] or [clear] — subtracts the same size back out once
 * the entry is actually released (immediately if nothing has [acquire]d it, deferred while
 * borrowed — see below). [totalBytesTracked] always equals the sum of the sizes of the entries
 * currently held (cached or pinned-and-awaiting-release), and returns to zero once [clear] has
 * run and every borrow outstanding at that time has been released.
 *
 * Eviction is strict least-recently-used: [acquire] promotes the returned entry to
 * most-recently-used, and after every [put] that grows [totalBytesTracked] past [maxBytes],
 * entries are dropped starting with the least-recently-used until the bound is satisfied again.
 * A single entry whose declared size exceeds [maxBytes] on its own is never retained: it is
 * released immediately and does not disturb, or evict, anything else already cached.
 *
 * Cached values are released through [RenderCandidate.release], which is CAS-guarded against
 * double release — the same discipline [RenderPublicationPolicy] already relies on for a
 * candidate that both a rejection path and a genuine consumer error might try to free. This
 * cache never invents a second release mechanism: [CachedPage.release] only lifts a borrow's
 * pin, it never itself calls [RenderCandidate.release] — that call is always made by this
 * cache's own eviction, invalidation, trim or clear machinery, whichever of them ends up being
 * the one to observe the entry both unreachable and unpinned.
 *
 * **Borrowing.** [acquire] pins the entry it returns: for as long as the returned [CachedPage] is
 * not [CachedPage.release]d, the underlying [RenderCandidate] is guaranteed never to be released,
 * even though every other cache operation still treats the entry as normally evictable —
 * eviction, invalidation, trim and clear all remove a pinned entry from lookup immediately (so a
 * later [put] for the same key is unaffected), but defer the actual [RenderCandidate.release]
 * until the last outstanding borrow on it is released. A pinned entry's bytes stay counted in
 * [totalBytesTracked] for as long as it is pinned, so [totalBytesTracked] can temporarily exceed
 * [maxBytes] by exactly the sum of the sizes of entries a consumer currently holds pinned — the
 * excess is bounded by how many borrows a consumer holds concurrently and how large the borrowed
 * pages are, never by cache activity. A borrow that is never released leaks: its entry's bytes
 * stay counted forever and its resource is never returned to [RenderCandidate.release]. This
 * cache cannot prevent that — nothing can, short of a borrow-checker this codebase does not have
 * — but it makes it detectable: [pinnedAwaitingReleaseCount] reports how many evicted entries are
 * still waiting on their last unpin, and a value that keeps growing rather than returning to zero
 * indicates a leaked borrow.
 *
 * Every mutating operation releases evicted or invalidated candidates *after* leaving this
 * cache's internal monitor, never while holding it: a release callback may itself take time, or
 * call back into unrelated code, and a lock held across it would let a slow release stall every
 * other cache operation — the same shape of hazard [ViewportScheduler] avoids by publishing
 * outcomes and releasing candidates outside its own monitor. Concretely, this cache is a strict
 * leaf lock: every one of its `synchronized(lock)` blocks executes only `LinkedHashMap`
 * operations, arithmetic on its own counters, and pure field comparisons — no consumer-supplied
 * code, and in particular no [RenderCandidate.release] call, ever runs while [lock] is held. That
 * property does not depend on how or when a consumer calls into this cache, so this cache's lock
 * can never be held at the same time as [ViewportScheduler]'s monitor, regardless of what call
 * shape a future consumer wires between the two.
 */
class ByteBoundedPageCache<T>(val maxBytes: Long) {

    init { require(maxBytes > 0) { "maxBytes must be positive, was $maxBytes" } }

    private val lock = Any()
    private val entries = LinkedHashMap<PageCacheKey, Entry<T>>(16, 0.75f, true)
    private val pinnedAwaitingRelease = mutableSetOf<Entry<T>>()
    private var totalBytes = 0L

    /**
     * Borrows the entry cached under [key], pinning it so its underlying resource is not released
     * while the returned [CachedPage] is held — see the class doc for the exact guarantee. Returns
     * `null` if nothing is cached under [key]. The caller MUST eventually call
     * [CachedPage.release]; see the class doc for what happens if it does not.
     */
    fun acquire(key: PageCacheKey): CachedPage<T>? = synchronized(lock) {
        val entry = entries[key] ?: return@synchronized null
        entry.pinCount++
        CachedPage(this, entry)
    }

    internal fun unpin(entry: Entry<T>) {
        var toRelease: RenderCandidate<T>? = null
        synchronized(lock) {
            entry.pinCount--
            if (entry.evicted && entry.pinCount == 0) {
                pinnedAwaitingRelease.remove(entry)
                totalBytes -= entry.sizeBytes
                toRelease = entry.candidate
            }
        }
        toRelease?.release()
    }

    fun totalBytesTracked(): Long = synchronized(lock) { totalBytes }

    fun entryCount(): Int = synchronized(lock) { entries.size }

    /** Test-observable recomputation of the tracked total straight from the live entries, exposed to assert exactness. */
    internal fun sizeOfLiveEntries(): Long = synchronized(lock) { entries.values.sumOf { it.sizeBytes } }

    /**
     * Test-observable count of entries that were evicted, invalidated, trimmed or cleared while
     * still pinned and are still waiting on their last [CachedPage.release]. See the class doc's
     * borrowing section: a steady-state non-zero value here indicates a leaked borrow.
     */
    internal fun pinnedAwaitingReleaseCount(): Int = synchronized(lock) { pinnedAwaitingRelease.size }

    /** Test-observable sum of the sizes of the entries counted by [pinnedAwaitingReleaseCount], exposed to assert the documented bound-under-pinning exactly. */
    internal fun pinnedAwaitingReleaseBytes(): Long = synchronized(lock) { pinnedAwaitingRelease.sumOf { it.sizeBytes } }

    /**
     * Retains [candidate] under [key] if it fits, evicting least-recently-used entries as needed
     * to stay within [maxBytes]. Returns whether it was actually retained: an entry whose own
     * [sizeBytes] exceeds [maxBytes] is refused outright, released immediately, and `false` is
     * returned, without disturbing anything already cached. A [candidate] already cached under
     * [key] is replaced; the replaced candidate is released immediately, or deferred if still
     * borrowed — see the class doc's borrowing section. [sizeBytes] must be strictly positive: a
     * zero-size entry would never be selected by any byte-budget eviction path (they all compare
     * the running total against a bound, which a zero contribution can never push over), so it
     * would survive every [trimToBytes] call, including a trim to zero, and only [clear] would
     * ever free it.
     */
    fun put(key: PageCacheKey, candidate: RenderCandidate<T>, sizeBytes: Long): Boolean {
        require(sizeBytes > 0) { "sizeBytes must be positive, was $sizeBytes" }

        if (sizeBytes > maxBytes) {
            candidate.release()
            return false
        }

        val toRelease = mutableListOf<RenderCandidate<T>>()
        synchronized(lock) {
            entries.remove(key)?.let { replaced -> releaseOrDeferLocked(replaced, toRelease) }
            entries[key] = Entry(candidate, sizeBytes)
            totalBytes += sizeBytes
            evictWhileOverBudgetLocked(toRelease, maxBytes)
        }
        toRelease.forEach { it.release() }
        return true
    }

    /** Removes and releases every entry belonging to [documentId], regardless of page, generation or spec. */
    fun invalidateDocument(documentId: String) = invalidateWhere { it.documentId == documentId }

    /** Removes and releases every cached entry for [documentId]'s [pageIndex], across every generation and spec. */
    fun invalidatePage(documentId: String, pageIndex: Int) =
        invalidateWhere { it.documentId == documentId && it.pageIndex == pageIndex }

    /** Removes and releases every entry for [documentId] whose [PageCacheKey.generation] is older than [currentGeneration]. */
    fun invalidateStaleGenerations(documentId: String, currentGeneration: Long) =
        invalidateWhere { it.documentId == documentId && it.generation < currentGeneration }

    /** Evicts least-recently-used entries until [totalBytesTracked] is at or below [targetBytes]. */
    fun trimToBytes(targetBytes: Long) {
        require(targetBytes >= 0)
        val toRelease = mutableListOf<RenderCandidate<T>>()
        synchronized(lock) { evictWhileOverBudgetLocked(toRelease, targetBytes) }
        toRelease.forEach { it.release() }
    }

    /** Removes every cached entry, releasing each immediately, or deferring it if still borrowed. */
    fun clear() {
        val toRelease = mutableListOf<RenderCandidate<T>>()
        synchronized(lock) {
            entries.values.forEach { entry -> releaseOrDeferLocked(entry, toRelease) }
            entries.clear()
        }
        toRelease.forEach { it.release() }
    }

    private fun invalidateWhere(matches: (PageCacheKey) -> Boolean) {
        val toRelease = mutableListOf<RenderCandidate<T>>()
        synchronized(lock) {
            val matching = entries.keys.filter(matches)
            matching.forEach { key ->
                val removed = entries.remove(key)!!
                releaseOrDeferLocked(removed, toRelease)
            }
        }
        toRelease.forEach { it.release() }
    }

    private fun evictWhileOverBudgetLocked(toRelease: MutableList<RenderCandidate<T>>, bound: Long) {
        while (totalBytes > bound && entries.isNotEmpty()) {
            val lruKey = entries.keys.first()
            val removed = entries.remove(lruKey)!!
            releaseOrDeferLocked(removed, toRelease)
        }
    }

    /**
     * Removes [entry] from live accounting and either queues it for immediate release, or, if it
     * is still pinned, defers that release: [entry] is marked [Entry.evicted] and tracked in
     * [pinnedAwaitingRelease] so [unpin] can find it and release it once the last borrow drops it.
     * [totalBytes] is only decremented at the point the entry actually becomes releasable — see
     * the class doc's borrowing section for why a pinned entry's bytes stay counted until then.
     */
    private fun releaseOrDeferLocked(entry: Entry<T>, toRelease: MutableList<RenderCandidate<T>>) {
        if (entry.pinCount > 0) {
            entry.evicted = true
            pinnedAwaitingRelease.add(entry)
        } else {
            totalBytes -= entry.sizeBytes
            toRelease.add(entry.candidate)
        }
    }

    internal class Entry<T>(val candidate: RenderCandidate<T>, val sizeBytes: Long) {
        var pinCount: Int = 0
        var evicted: Boolean = false
    }
}

/**
 * A borrow on an entry held by a [ByteBoundedPageCache], obtained from [ByteBoundedPageCache.acquire].
 * [value] is safe to use for as long as this borrow is not [release]d: the cache defers releasing
 * the underlying resource until then, no matter what else happens to the entry in the meantime.
 * [release] itself is idempotent — a second call is a no-op, mirroring [RenderCandidate.release]'s
 * own CAS guard — and only lifts the pin; it never releases the underlying resource directly.
 */
class CachedPage<T> internal constructor(
    private val cache: ByteBoundedPageCache<T>,
    private val entry: ByteBoundedPageCache.Entry<T>
) {
    val value: T get() = entry.candidate.value

    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) cache.unpin(entry)
    }
}
