package com.folium.reader.core.diskcache

/**
 * Persistent, whole-page raster cache: [read] and [containsKey] are ordinary blocking calls safe
 * from any thread, while [enqueueWrite] only ever hands its work to a dedicated background thread —
 * see [FileDiskPageCacheStore] for what that thread does and how it is bounded.
 *
 * [markOpen] and [markClosed] track which documents are currently being read, so budget eviction
 * never removes one out from under its own session — see [DiskCacheEviction].
 */
interface DiskPageCacheStore {
    fun markOpen(contentId: String)
    fun markClosed(contentId: String)

    /** Cheap existence check that does not validate or decode the entry — a true result is not a guarantee [read] will succeed. */
    fun containsKey(key: DiskPageCacheKey): Boolean

    /** Blocking read-through: null for anything not stored, unreadable, or that fails validation against [key]. */
    fun read(key: DiskPageCacheKey): DiskPageCacheEntry?

    /**
     * Offers [rgba] to be encoded and written under [key] on the store's own background thread.
     * Returns immediately; a full write queue silently drops the work rather than blocking the
     * caller, since a dropped write only costs a future cache miss, never correctness.
     */
    fun enqueueWrite(key: DiskPageCacheKey, rgba: ByteArray, pageAspect: Float)

    /**
     * Whether a write offered right now would likely be accepted rather than silently dropped.
     *
     * A snapshot, not a reservation — the answer can be stale by the time a caller actually calls
     * [enqueueWrite] — but it is enough for a caller whose own work is only worth doing if the
     * result can be persisted, such as a background fill that would otherwise burn engine time
     * rendering a page whose write is going to be thrown away.
     */
    fun hasWriteCapacity(): Boolean = true
}

/** Used wherever a store is required but persistence is not available or not wanted — every call is a no-op or a miss. */
object NoOpDiskPageCacheStore : DiskPageCacheStore {
    override fun markOpen(contentId: String) = Unit
    override fun markClosed(contentId: String) = Unit
    override fun containsKey(key: DiskPageCacheKey): Boolean = false
    override fun read(key: DiskPageCacheKey): DiskPageCacheEntry? = null
    override fun enqueueWrite(key: DiskPageCacheKey, rgba: ByteArray, pageAspect: Float) = Unit
}
