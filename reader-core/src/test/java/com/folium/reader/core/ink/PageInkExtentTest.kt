package com.folium.reader.core.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageInkExtentTest {
    private val a4WidthPt = 595f
    private val a4HeightPt = 842f
    private val tolerance = 1e-4f

    private val portrait = PageInkExtent.of(a4WidthPt, a4HeightPt)
    private val landscape = PageInkExtent.of(a4HeightPt, a4WidthPt)

    @Test fun portraitA4IsOneUnitWideAndTallerThanWide() {
        assertEquals(a4WidthPt / a4HeightPt, portrait.aspect, tolerance)
        assertEquals(a4HeightPt / a4WidthPt, portrait.heightUnits, tolerance)
        assertEquals(SheetRect(0f, 0f, 1f, portrait.heightUnits), portrait.bounds)
    }

    @Test fun landscapeA4IsOneUnitWideAndShorterThanWide() {
        assertEquals(a4WidthPt / a4HeightPt, landscape.heightUnits, tolerance)
        assertTrue(landscape.heightUnits < 1f)
    }

    @Test fun clampPullsAPointOutsideThePageOntoItsNearestEdge() {
        assertEquals(SheetPoint(0f, 0f), portrait.clamp(SheetPoint(-0.3f, -2f)))
        assertEquals(SheetPoint(1f, portrait.heightUnits), portrait.clamp(SheetPoint(1.5f, 9f)))
        assertEquals(SheetPoint(0f, 0.5f), portrait.clamp(SheetPoint(-0.01f, 0.5f)))
    }

    @Test fun clampLeavesAPointOnThePageUnchanged() {
        val inside = SheetPoint(0.25f, 1.2f)
        assertEquals(inside, portrait.clamp(inside))

        val corner = SheetPoint(1f, portrait.heightUnits)
        assertEquals(corner, portrait.clamp(corner))
    }

    @Test fun allowsARectFullyInsideThePageIncludingOneTouchingItsEdges() {
        assertTrue(portrait.allows(SheetRect(0.1f, 0.1f, 0.4f, 0.3f)))
        assertTrue(portrait.allows(portrait.bounds))
    }

    @Test fun refusesARectCrossingAnyEdge() {
        assertFalse(portrait.allows(SheetRect(-0.01f, 0.1f, 0.4f, 0.3f)))
        assertFalse(portrait.allows(SheetRect(0.8f, 0.1f, 1.01f, 0.3f)))
        assertFalse(portrait.allows(SheetRect(0.1f, -0.2f, 0.4f, 0.3f)))
        assertFalse(portrait.allows(SheetRect(0.1f, 1.3f, 0.4f, portrait.heightUnits + 0.01f)))
    }

    @Test fun landscapeRefusesARectThatWouldFitAPortraitPage() {
        assertFalse(landscape.allows(SheetRect(0.1f, 0.5f, 0.2f, 0.9f)))
    }

    @Test fun pageSpaceMapsTheFarCornerToOneOnBothAxes() {
        val farCorner = SheetPoint(1f, portrait.heightUnits)
        val page = portrait.toPageSpace(farCorner)

        assertEquals(1f, page.x, tolerance)
        assertEquals(1f, page.y, tolerance)
    }

    @Test fun pageSpaceRoundTripsInBothDirections() {
        for (extent in listOf(portrait, landscape)) {
            val ink = SheetPoint(0.37f, extent.heightUnits * 0.61f)
            val backToInk = extent.fromPageSpace(extent.toPageSpace(ink))
            assertEquals(ink.x, backToInk.x, tolerance)
            assertEquals(ink.y, backToInk.y, tolerance)

            val page = SheetPoint(0.2f, 0.9f)
            val backToPage = extent.toPageSpace(extent.fromPageSpace(page))
            assertEquals(page.x, backToPage.x, tolerance)
            assertEquals(page.y, backToPage.y, tolerance)
        }
    }

    @Test fun fromPageSpaceKeepsUnitsIsotropic() {
        val halfWidth = portrait.fromPageSpace(SheetPoint(0.5f, 0f)).x
        val sameLengthDown = portrait.fromPageSpace(SheetPoint(0f, (0.5f * a4WidthPt) / a4HeightPt)).y

        assertEquals(halfWidth, sameLengthDown, tolerance)
    }

    @Test fun nominalWidthOfAnA4PageIsAbout210Mm() {
        assertEquals(209.9f, PageInkExtent.nominalWidthMm(a4WidthPt), 0.1f)
        assertEquals(215.9f, PageInkExtent.nominalWidthMm(612f), 0.1f)
    }

    @Test fun millimetresConvertToUnitsOfThePageWidth() {
        val widthPt = 612f
        val units = PageInkExtent.mmToUnits(0.5f, widthPt)

        assertEquals(0.5f / (612f * 25.4f / 72f), units, 1e-7f)
        assertEquals(0.5f, PageInkExtent.unitsToMm(units, widthPt), tolerance)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNonPositiveAspectIsRejected() {
        PageInkExtent(0f)
    }
}
