package com.folium.reader.pdf

import android.content.ComponentCallbacks2
import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.PageCacheKey
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.RenderSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class PageCacheMemoryCallbacksTest {

    @Test fun targetBytesForRetainsEverythingAtLevelZero() {
        assertEquals(1000L, targetBytesFor(level = 0, maxBytes = 1000))
    }

    @Test fun targetBytesForFollowsTheDocumentedAndroidSeverityScale() {
        assertEquals(937L, targetBytesFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, maxBytes = 1000))
        assertEquals(875L, targetBytesFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, maxBytes = 1000))
        assertEquals(812L, targetBytesFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, maxBytes = 1000))
        assertEquals(750L, targetBytesFor(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, maxBytes = 1000))
        assertEquals(500L, targetBytesFor(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, maxBytes = 1000))
        assertEquals(250L, targetBytesFor(ComponentCallbacks2.TRIM_MEMORY_MODERATE, maxBytes = 1000))
        assertEquals(0L, targetBytesFor(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, maxBytes = 1000))
    }

    @Test fun targetBytesForClampsALevelPastTheMostSevereDocumentedOneToZero() {
        assertEquals(0L, targetBytesFor(level = 999, maxBytes = 1000))
    }

    @Test fun onTrimMemoryShedsTheCacheDownToTheMappedTarget() {
        val cache = ByteBoundedPageCache<String>(maxBytes = 1000)
        cache.put(key(0), RenderCandidate("a") {}, sizeBytes = 750)
        cache.put(key(1), RenderCandidate("b") {}, sizeBytes = 100)
        val callbacks = PageCacheMemoryCallbacks(cache)

        callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE)

        assertEquals(100L, cache.totalBytesTracked())
    }

    @Test fun onLowMemoryClearsTheCacheEntirely() {
        val cache = ByteBoundedPageCache<String>(maxBytes = 1000)
        cache.put(key(0), RenderCandidate("a") {}, sizeBytes = 900)
        val callbacks = PageCacheMemoryCallbacks(cache)

        callbacks.onLowMemory()

        assertEquals(0L, cache.totalBytesTracked())
        assertEquals(0, cache.entryCount())
    }

    private fun key(pageIndex: Int) = PageCacheKey("doc-0", pageIndex, generation = 0, spec = RenderSpec(width = 10, height = 10))
}
