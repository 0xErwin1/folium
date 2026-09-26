package com.folium.reader.reader

import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.ink.InkSurfaceMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PageInkTextScaleTest {

    @Test fun `the live surface and the cached layer size a page's text through the same mode`() {
        val info = PageInfo(index = 4, width = 360f, height = 576f, rotationDegrees = 0)

        val live = pageInkMode(info)

        assertEquals(InkSurfaceMode.Page(pageWidthPt = 360f, pageHeightPt = 576f), live)
        assertEquals(live.textDesignPxPerPoint, pageInkTextDesignPxPerPoint(info), 0f)
    }
}
