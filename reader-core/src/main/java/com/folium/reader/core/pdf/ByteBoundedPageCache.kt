package com.folium.reader.core.pdf

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
    init { require(documentId.isNotBlank() && pageIndex >= 0) }
}

/**
 * Byte-bounded, engine-neutral cache of [RenderCandidate]s keyed by [PageCacheKey].
 *
 * Accounting is exact: every [put] that is actually retained adds its declared size to the
 * running total, and every removal — via eviction, [invalidateDocument], [invalidatePage],
 * [invalidateStaleGenerations], [trimToBytes] or [clear] — subtracts the same size back out.
 * [totalBytesTracked] always equals the sum of the sizes of the entries currently held, and
 * returns to zero once [clear] has run.
 *
 * Eviction is strict least-recently-used: [get] promotes the returned entry to
 * most-recently-used, and after every [put] that grows [totalBytesTracked] past [maxBytes],
 * entries are dropped starting with the least-recently-used until the bound is satisfied again.
 * A single entry whose declared size exceeds [maxBytes] on its own is never retained: it is
 * released immediately and does not disturb, or evict, anything else already cached.
 *
 * Cached values are released through [RenderCandidate.release], which is CAS-guarded against
 * double release — the same discipline [RenderPublicationPolicy] already relies on for a
 * candidate that both a rejection path and a genuine consumer error might try to free. This
 * cache never invents a second release mechanism: whichever caller — this cache on eviction, or
 * a consumer on its own error path — reaches [RenderCandidate.release] first wins, and the other
 * is a no-op.
 *
 * Every mutating operation releases evicted or invalidated candidates *after* leaving this
 * cache's internal monitor, never while holding it: a release callback may itself take time, or
 * call back into unrelated code, and a lock held across it would let a slow release stall every
 * other cache operation — the same shape of hazard [ViewportScheduler] avoids by publishing
 * outcomes and releasing candidates outside its own monitor. This cache's lock is also never
 * held at the same time as [ViewportScheduler]'s: a consumer wiring the two together (a
 * [SchedulerOutcome] callback that populates this cache) only ever calls into this cache from
 * [ViewportScheduler]'s `publish`, which runs after the scheduler's own `synchronized` block has
 * already been left — so no code path acquires both locks at once, and no ordering needs to be
 * declared between them.
 */
class ByteBoundedPageCache<T>(val maxBytes: Long) {

    init { require(maxBytes > 0) }

    private val lock = Any()
    private val entries = LinkedHashMap<PageCacheKey, Entry<T>>(16, 0.75f, true)
    private var totalBytes = 0L

    fun get(key: PageCacheKey): RenderCandidate<T>? = synchronized(lock) { entries[key]?.candidate }

    fun totalBytesTracked(): Long = synchronized(lock) { totalBytes }

    fun entryCount(): Int = synchronized(lock) { entries.size }

    /** Test-observable recomputation of the tracked total straight from the live entries, exposed to assert exactness. */
    internal fun sizeOfLiveEntries(): Long = synchronized(lock) { entries.values.sumOf { it.sizeBytes } }

    /**
     * Retains [candidate] under [key] if it fits, evicting least-recently-used entries as needed
     * to stay within [maxBytes]. Returns whether it was actually retained: an entry whose own
     * [sizeBytes] exceeds [maxBytes] is refused outright, released immediately, and `false` is
     * returned, without disturbing anything already cached. A [candidate] already cached under
     * [key] is replaced, and the replaced candidate is released.
     */
    fun put(key: PageCacheKey, candidate: RenderCandidate<T>, sizeBytes: Long): Boolean {
        require(sizeBytes >= 0)

        if (sizeBytes > maxBytes) {
            candidate.release()
            return false
        }

        val toRelease = mutableListOf<RenderCandidate<T>>()
        synchronized(lock) {
            entries.remove(key)?.let { replaced ->
                totalBytes -= replaced.sizeBytes
                toRelease.add(replaced.candidate)
            }
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

    /** Removes and releases every cached entry. */
    fun clear() {
        val toRelease = mutableListOf<RenderCandidate<T>>()
        synchronized(lock) {
            toRelease.addAll(entries.values.map { it.candidate })
            entries.clear()
            totalBytes = 0
        }
        toRelease.forEach { it.release() }
    }

    private fun invalidateWhere(matches: (PageCacheKey) -> Boolean) {
        val toRelease = mutableListOf<RenderCandidate<T>>()
        synchronized(lock) {
            val matching = entries.keys.filter(matches)
            matching.forEach { key ->
                val removed = entries.remove(key)!!
                totalBytes -= removed.sizeBytes
                toRelease.add(removed.candidate)
            }
        }
        toRelease.forEach { it.release() }
    }

    private fun evictWhileOverBudgetLocked(toRelease: MutableList<RenderCandidate<T>>, bound: Long) {
        while (totalBytes > bound && entries.isNotEmpty()) {
            val lruKey = entries.keys.first()
            val removed = entries.remove(lruKey)!!
            totalBytes -= removed.sizeBytes
            toRelease.add(removed.candidate)
        }
    }

    private class Entry<T>(val candidate: RenderCandidate<T>, val sizeBytes: Long)
}
