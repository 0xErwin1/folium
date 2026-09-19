package com.folium.reader.engine_mupdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LruEvictionCacheTest {
    @Test fun rejectsANonPositiveCapacity() {
        assertThrows(IllegalArgumentException::class.java) { LruEvictionCache<Int, String>(0) { _, _ -> } }
    }

    @Test fun aHitRefreshesRecencySoItSurvivesTheNextEviction() {
        val evicted = mutableListOf<Int>()
        val cache = LruEvictionCache<Int, String>(2) { key, _ -> evicted += key }

        cache.put(1, "one")
        cache.put(2, "two")
        cache.get(1)
        cache.put(3, "three")

        assertEquals(listOf(2), evicted)
        assertEquals("one", cache.get(1))
        assertEquals("three", cache.get(3))
        assertNull(cache.get(2))
    }

    @Test fun aMissOverCapacityEvictsTheLeastRecentlyUsedExactlyOnce() {
        val evicted = mutableListOf<Pair<Int, String>>()
        val cache = LruEvictionCache<Int, String>(2) { key, value -> evicted += key to value }

        cache.put(1, "one")
        cache.put(2, "two")
        cache.put(3, "three")

        assertEquals(listOf(1 to "one"), evicted)
        assertNull(cache.get(1))
        assertEquals("two", cache.get(2))
        assertEquals("three", cache.get(3))
    }

    @Test fun clearCallsTheCallbackForEveryEntry() {
        val evicted = mutableListOf<Int>()
        val cache = LruEvictionCache<Int, String>(4) { key, _ -> evicted += key }

        cache.put(1, "one")
        cache.put(2, "two")
        cache.put(3, "three")
        cache.clear()

        assertEquals(setOf(1, 2, 3), evicted.toSet())
        assertEquals(3, evicted.size)
        assertNull(cache.get(1))
        assertNull(cache.get(2))
        assertNull(cache.get(3))
    }

    /**
     * Mirrors the calling convention [MuPdfDocument] relies on: a value only ever reaches [put]
     * once building it has fully succeeded, so a build that throws must never leave a partial
     * result behind for a later [get] to return.
     */
    @Test fun aFailedBuildLeavesNothingBehind() {
        val evicted = mutableListOf<Int>()
        val cache = LruEvictionCache<Int, String>(2) { key, _ -> evicted += key }

        val error = assertThrows(RuntimeException::class.java) {
            val built = buildOrThrow()
            cache.put(1, built)
        }

        assertEquals("boom", error.message)
        assertNull(cache.get(1))
        assertTrue(evicted.isEmpty())
    }

    private fun buildOrThrow(): String = throw RuntimeException("boom")
}
