package com.folium.reader.engine_mupdf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationAppearanceTraitsTest {
    private fun square(hasAppearance: Boolean = false, hasInteriorColor: Boolean = false, borderWidth: Float = 0f) =
        AnnotationAppearanceTraits("Square", hasAppearance, hasInteriorColor, borderWidth)

    @Test fun autocadStyleSquareWithNoAppearanceNoInteriorColorAndZeroBorderIsInvisible() {
        assertTrue(square().synthesizesNoVisibleAppearance())
    }

    @Test fun circleWithNoAppearanceNoInteriorColorAndZeroBorderIsInvisible() {
        assertTrue(
            AnnotationAppearanceTraits("Circle", hasAppearance = false, hasInteriorColor = false, borderWidth = 0f)
                .synthesizesNoVisibleAppearance()
        )
    }

    @Test fun squareWithAnAppearanceStreamIsKept() {
        assertFalse(square(hasAppearance = true).synthesizesNoVisibleAppearance())
    }

    @Test fun squareWithAnInteriorColorIsKept() {
        assertFalse(square(hasInteriorColor = true).synthesizesNoVisibleAppearance())
    }

    @Test fun squareWithABorderWidthOfOneIsKept() {
        assertFalse(square(borderWidth = 1f).synthesizesNoVisibleAppearance())
    }

    @Test fun squareWithNoBorderInformationDefaultsToWidthOneAndIsKept() {
        assertFalse(square(borderWidth = 1f).synthesizesNoVisibleAppearance())
    }

    @Test fun squareWithExplicitBsWidthOfTwoIsKept() {
        assertFalse(square(borderWidth = 2f).synthesizesNoVisibleAppearance())
    }

    @Test fun linkAnnotationIsAlwaysKeptRegardlessOfAppearanceOrBorder() {
        assertFalse(AnnotationAppearanceTraits("Link", hasAppearance = false, hasInteriorColor = false, borderWidth = 0f).synthesizesNoVisibleAppearance())
    }

    @Test fun widgetAnnotationIsAlwaysKeptRegardlessOfAppearanceOrBorder() {
        assertFalse(AnnotationAppearanceTraits("Widget", hasAppearance = false, hasInteriorColor = false, borderWidth = 0f).synthesizesNoVisibleAppearance())
    }

    @Test fun textAnnotationIsAlwaysKeptRegardlessOfAppearanceOrBorder() {
        assertFalse(AnnotationAppearanceTraits("Text", hasAppearance = false, hasInteriorColor = false, borderWidth = 0f).synthesizesNoVisibleAppearance())
    }

    @Test fun freeTextAnnotationIsAlwaysKeptRegardlessOfAppearanceOrBorder() {
        assertFalse(AnnotationAppearanceTraits("FreeText", hasAppearance = false, hasInteriorColor = false, borderWidth = 0f).synthesizesNoVisibleAppearance())
    }
}
