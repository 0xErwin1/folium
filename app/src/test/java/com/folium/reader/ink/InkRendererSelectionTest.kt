package com.folium.reader.ink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkRendererSelectionTest {

    @Test
    fun belowApi33AlwaysPrefersTheStandardRenderer() {
        assertTrue(prefersStandardInkRenderer(sdkInt = 32, frontBufferSupported = true))
        assertTrue(prefersStandardInkRenderer(sdkInt = 21, frontBufferSupported = true))
    }

    @Test
    fun atOrAboveApi33PrefersTheStandardRendererOnlyWithoutFrontBufferSupport() {
        assertTrue(prefersStandardInkRenderer(sdkInt = 33, frontBufferSupported = false))
        assertFalse(prefersStandardInkRenderer(sdkInt = 33, frontBufferSupported = true))
        assertFalse(prefersStandardInkRenderer(sdkInt = 35, frontBufferSupported = true))
    }

    @Test
    fun onlyTheStandardRendererMaskingToAPageNeedsAnOffscreenInProgressLayer() {
        assertTrue(needsOffscreenInProgressLayer(standardRenderer = true, masksToPage = true))
        assertFalse(needsOffscreenInProgressLayer(standardRenderer = true, masksToPage = false))
        assertFalse(needsOffscreenInProgressLayer(standardRenderer = false, masksToPage = true))
        assertFalse(needsOffscreenInProgressLayer(standardRenderer = false, masksToPage = false))
    }
}
