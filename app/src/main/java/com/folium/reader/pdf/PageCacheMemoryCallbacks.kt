package com.folium.reader.pdf

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.folium.reader.core.pdf.ByteBoundedPageCache

/**
 * Wires Android's low-memory signalling to [cache]'s trim policy. This is the only Android-facing
 * piece of the byte-bounded page cache: every accounting, eviction and invalidation decision is
 * made by [ByteBoundedPageCache] itself, in `:reader-core`, with no knowledge of Android at all.
 * This class only translates a [ComponentCallbacks2] level into a target byte budget and calls
 * [ByteBoundedPageCache.trimToBytes].
 *
 * Registering an instance against `Application.registerComponentCallbacks` (or an `Activity`) is
 * left to the caller, so construction itself has no side effect.
 */
class PageCacheMemoryCallbacks(private val cache: ByteBoundedPageCache<*>) : ComponentCallbacks2 {

    override fun onTrimMemory(level: Int) {
        cache.trimToBytes(targetBytesFor(level, cache.maxBytes))
    }

    override fun onLowMemory() {
        cache.trimToBytes(0)
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
}

/**
 * Maps a [ComponentCallbacks2] trim level to the number of bytes of [maxBytes] the cache should
 * retain. Android's documented trim levels are already an ordinal severity scale from `0`
 * (untrimmed) to [ComponentCallbacks2.TRIM_MEMORY_COMPLETE] (`80`, about to be killed), so the
 * retained fraction is simply that scale inverted and clamped: `0` retains everything,
 * [ComponentCallbacks2.TRIM_MEMORY_COMPLETE] retains nothing, and every documented level in
 * between retains proportionally less. A level below `0` clamps to fully retained; a level above
 * [ComponentCallbacks2.TRIM_MEMORY_COMPLETE] clamps to fully released, since an unrecognized
 * signal past the most severe documented one should never be assumed harmless.
 */
internal fun targetBytesFor(level: Int, maxBytes: Long): Long {
    val retainFraction = (1.0 - level.toDouble() / ComponentCallbacks2.TRIM_MEMORY_COMPLETE).coerceIn(0.0, 1.0)
    return (maxBytes * retainFraction).toLong()
}
