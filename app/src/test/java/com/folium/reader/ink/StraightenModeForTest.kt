package com.folium.reader.ink

import org.junit.Assert.assertEquals
import org.junit.Test

class StraightenModeForTest {

    @Test fun `the pen's own mode governs a pen stroke`() {
        assertEquals(
            InkStraightenMode.ALWAYS,
            straightenModeFor(InkSurfaceTool.PEN, penMode = InkStraightenMode.ALWAYS, highlighterMode = InkStraightenMode.NEVER)
        )
    }

    @Test fun `the highlighter's own mode governs a highlighter stroke, independent of the pen's`() {
        assertEquals(
            InkStraightenMode.ON_HOLD,
            straightenModeFor(InkSurfaceTool.HIGHLIGHTER, penMode = InkStraightenMode.NEVER, highlighterMode = InkStraightenMode.ON_HOLD)
        )
    }

    @Test fun `every other tool never straightens, whatever the two modes are set to`() {
        for (tool in listOf(InkSurfaceTool.SHAPE, InkSurfaceTool.ERASER, InkSurfaceTool.VIEW)) {
            assertEquals(
                InkStraightenMode.NEVER,
                straightenModeFor(tool, penMode = InkStraightenMode.ALWAYS, highlighterMode = InkStraightenMode.ALWAYS)
            )
        }
    }
}
