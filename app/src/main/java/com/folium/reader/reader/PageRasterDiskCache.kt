package com.folium.reader.reader

import android.content.Context
import com.folium.reader.core.diskcache.DiskPageCacheStore
import com.folium.reader.core.diskcache.FileDiskPageCacheStore
import java.io.File

private const val CACHE_DIR_NAME = "page-raster-cache"

/**
 * Resolves the single, process-wide [DiskPageCacheStore] every [ReaderSession] renders through.
 *
 * One store, not one per session: the budget it enforces and the writer thread it owns are both
 * process-wide resources, and a session opening a second document while another is still closing
 * must land in the same store so eviction sees the whole cache, not just its own document.
 */
internal object PageRasterDiskCache {
    @Volatile private var instance: DiskPageCacheStore? = null
    private val lock = Any()

    fun forApplication(context: Context): DiskPageCacheStore = instance ?: synchronized(lock) {
        instance ?: FileDiskPageCacheStore(
            rootDir = File(context.applicationContext.cacheDir, CACHE_DIR_NAME),
            onEvict = { traced({ "folium:disk:evict" }) {} }
        ).also { instance = it }
    }
}
