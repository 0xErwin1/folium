package com.folium.reader.reader

import com.folium.reader.core.pdf.ReflowPageColors
import com.folium.reader.ui.FoliumPaper
import com.folium.reader.ui.toBackgroundColor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [resolvePlaceholderColor] in isolation from Compose: the loading placeholder must carry a
 * reflowable document's own resolved page colour rather than a fixed paper tone, but a fixed-layout
 * document — which has no page colour of its own — must keep exactly the paper it always drew.
 */
class ResolvePlaceholderColorTest {

    private val colors = ReflowPageColors(foregroundHex = "F2F2F2", backgroundHex = "0B0B0B", accentHex = "D9543C")

    @Test fun `a reflowable document with resolved colours is drawn in its own background`() {
        assertEquals(colors.toBackgroundColor(), resolvePlaceholderColor(reflowable = true, pageColors = colors))
    }

    @Test fun `a reflowable document with no colours resolved yet keeps the paper placeholder`() {
        assertEquals(FoliumPaper, resolvePlaceholderColor(reflowable = true, pageColors = null))
    }

    @Test fun `a fixed-layout document always keeps the paper placeholder`() {
        assertEquals(FoliumPaper, resolvePlaceholderColor(reflowable = false, pageColors = null))
        assertEquals(FoliumPaper, resolvePlaceholderColor(reflowable = false, pageColors = colors))
    }
}
