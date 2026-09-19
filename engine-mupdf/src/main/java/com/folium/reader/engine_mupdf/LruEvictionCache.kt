package com.folium.reader.engine_mupdf

/**
 * A fixed-capacity, least-recently-used map with no size accounting beyond entry count.
 *
 * [get] promotes the returned entry to most-recently-used. A [put] that grows the map past
 * [capacity] evicts the single least-recently-used entry and calls [onEvict] with it exactly once,
 * after the new entry has already been inserted. [clear] calls [onEvict] for every entry it
 * removes, in no particular order.
 *
 * Not thread-safe: every caller is expected to hold whatever lock already guards the values this
 * cache stores, the way [MuPdfDocument.renderPage] holds the document's own lock around every
 * access here.
 */
internal class LruEvictionCache<K, V>(
    private val capacity: Int,
    private val onEvict: (K, V) -> Unit
) {
    init { require(capacity > 0) { "capacity must be positive, was $capacity" } }

    private val entries = object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
            if (size <= capacity) return false
            onEvict(eldest.key, eldest.value)
            return true
        }
    }

    fun get(key: K): V? = entries[key]

    fun put(key: K, value: V) {
        entries[key] = value
    }

    fun clear() {
        val snapshot = entries.entries.map { it.key to it.value }
        entries.clear()
        snapshot.forEach { (key, value) -> onEvict(key, value) }
    }
}
