package com.folium.reader.core.diskcache

/** One document's on-disk footprint, as seen from outside the filesystem it lives in. */
data class DocumentDirUsage(val contentId: String, val bytes: Long, val lastUsedMillis: Long)

/**
 * Pure least-recently-used eviction over whole document directories: never a single entry within
 * one, since a document is the unit [FileDiskPageCacheStore] deletes and the unit whose "currently
 * open" status protects it from eviction.
 *
 * Kept free of any file I/O so the exact ordering — least-recently-used first, the open document
 * skipped outright regardless of how stale it looks — is checked without a filesystem in the way.
 */
object DiskCacheEviction {

    /**
     * The [DocumentDirUsage.contentId]s to remove so the sum of what remains is at or under
     * [maxBytes], evicting the least-recently-used directory not in [openContentIds] first. Returns
     * an empty list once the budget is satisfied, even if [openContentIds] alone already exceeds it —
     * the open document is never a candidate, no matter how far over budget that leaves the cache.
     */
    fun plan(dirs: List<DocumentDirUsage>, maxBytes: Long, openContentIds: Set<String>): List<String> {
        var total = dirs.sumOf { it.bytes }
        if (total <= maxBytes) return emptyList()

        val evictionOrder = dirs.filter { it.contentId !in openContentIds }.sortedBy { it.lastUsedMillis }
        val toEvict = mutableListOf<String>()

        for (dir in evictionOrder) {
            if (total <= maxBytes) break
            toEvict += dir.contentId
            total -= dir.bytes
        }

        return toEvict
    }
}
