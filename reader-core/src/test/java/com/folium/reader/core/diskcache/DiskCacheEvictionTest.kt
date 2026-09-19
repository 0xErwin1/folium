package com.folium.reader.core.diskcache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiskCacheEvictionTest {

    @Test fun nothingIsEvictedWhenAlreadyAtOrUnderBudget() {
        val dirs = listOf(DocumentDirUsage("a", 100, 1L), DocumentDirUsage("b", 100, 2L))
        assertEquals(emptyList<String>(), DiskCacheEviction.plan(dirs, maxBytes = 200, openContentIds = emptySet()))
    }

    @Test fun theLeastRecentlyUsedDirectoryIsEvictedFirst() {
        val dirs = listOf(
            DocumentDirUsage("oldest", 100, lastUsedMillis = 1L),
            DocumentDirUsage("middle", 100, lastUsedMillis = 2L),
            DocumentDirUsage("newest", 100, lastUsedMillis = 3L)
        )
        assertEquals(listOf("oldest"), DiskCacheEviction.plan(dirs, maxBytes = 250, openContentIds = emptySet()))
    }

    @Test fun evictionStopsAsSoonAsTheBudgetIsSatisfied() {
        val dirs = listOf(
            DocumentDirUsage("a", 100, 1L),
            DocumentDirUsage("b", 100, 2L),
            DocumentDirUsage("c", 100, 3L)
        )
        assertEquals(listOf("a"), DiskCacheEviction.plan(dirs, maxBytes = 200, openContentIds = emptySet()))
    }

    @Test fun theOpenDocumentIsNeverEvictedEvenWhenItAloneExceedsTheBudget() {
        val dirs = listOf(DocumentDirUsage("open", 1000, lastUsedMillis = 1L))
        assertEquals(emptyList<String>(), DiskCacheEviction.plan(dirs, maxBytes = 100, openContentIds = setOf("open")))
    }

    @Test fun theOpenDocumentIsSkippedInFavorOfOlderClosedOnes() {
        val dirs = listOf(
            DocumentDirUsage("open", 100, lastUsedMillis = 1L),
            DocumentDirUsage("closed", 100, lastUsedMillis = 2L)
        )
        assertEquals(listOf("closed"), DiskCacheEviction.plan(dirs, maxBytes = 100, openContentIds = setOf("open")))
    }

    @Test fun theResultingTotalEndsAtOrUnderBudgetWheneverEnoughCanBeEvicted() {
        val dirs = (1..5).map { DocumentDirUsage("doc-$it", bytes = 50L, lastUsedMillis = it.toLong()) }
        val plan = DiskCacheEviction.plan(dirs, maxBytes = 120, openContentIds = emptySet())
        val remaining = dirs.filter { it.contentId !in plan }.sumOf { it.bytes }
        assertTrue(remaining <= 120)
    }
}
