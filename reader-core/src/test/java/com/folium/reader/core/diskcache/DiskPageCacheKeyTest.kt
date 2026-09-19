package com.folium.reader.core.diskcache

import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.RenderSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiskPageCacheKeyTest {

    private fun key(
        formatVersion: Int = 1,
        engineId: String = "engine-1",
        contentId: String = "content-1",
        layoutVersion: String? = null,
        pageIndex: Int = 0,
        width: Int = 100,
        height: Int = 200,
        pageSpace: PageSpaceRect = PageSpaceRect(0f, 0f, 1f, 1f)
    ) = DiskPageCacheKey(formatVersion, engineId, contentId, layoutVersion, pageIndex, width, height, pageSpace)

    @Test fun fileNameIsDeterministicForTheSameKey() {
        assertEquals(diskPageCacheFileName(key()), diskPageCacheFileName(key()))
    }

    @Test fun fileNameDiffersWhenAnyFieldDiffers() {
        val base = diskPageCacheFileName(key())
        assertNotEquals(base, diskPageCacheFileName(key(formatVersion = 2)))
        assertNotEquals(base, diskPageCacheFileName(key(engineId = "engine-2")))
        assertNotEquals(base, diskPageCacheFileName(key(contentId = "content-2")))
        assertNotEquals(base, diskPageCacheFileName(key(layoutVersion = "layout-1")))
        assertNotEquals(base, diskPageCacheFileName(key(pageIndex = 1)))
        assertNotEquals(base, diskPageCacheFileName(key(width = 101)))
        assertNotEquals(base, diskPageCacheFileName(key(height = 201)))
        assertNotEquals(base, diskPageCacheFileName(key(pageSpace = PageSpaceRect(0f, 0f, 0.5f, 1f))))
    }

    @Test fun aWholePageSpecIsStorable() {
        val spec = RenderSpec(400, 283, PageSpaceRect(0f, 0f, 1f, 1f))
        val diskKey = DiskPageCacheKey.forWholePageSpec("engine", "content", null, 0, spec)
        assertTrue(spec.isWholePage())
        assertEquals(400, diskKey?.width)
        assertEquals(283, diskKey?.height)
    }

    @Test fun aCroppedSpecIsNeverStorable() {
        val spec = RenderSpec(400, 283, PageSpaceRect(0.1f, 0.2f, 0.9f, 0.8f))
        assertTrue(!spec.isWholePage())
        assertNull(DiskPageCacheKey.forWholePageSpec("engine", "content", null, 0, spec))
    }
}
