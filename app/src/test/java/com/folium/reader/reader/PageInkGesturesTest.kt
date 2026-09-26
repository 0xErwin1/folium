package com.folium.reader.reader

import com.folium.reader.core.ink.PageInkExtent
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetRect
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.HorizontalViewportZoom
import com.folium.reader.core.pdf.PageFitMode
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.ink.InkSurfaceMode
import com.folium.reader.ink.PanZoomStep
import com.folium.reader.ink.SelectionEditKind
import com.folium.reader.ink.SelectionEditSession
import com.folium.reader.ink.ViewPoint
import com.folium.reader.ink.ViewRect
import com.folium.reader.ink.pageInkAcceptsDown
import com.folium.reader.ink.moveWithinPage
import com.folium.reader.ink.pageInkAllowsEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EPSILON = 1e-4f

class PageInkGesturesTest {

    private val cell = ReaderViewport(widthPx = 1000, heightPx = 2000)
    private val layout = ReaderGeometry.layout(cell, 0.5f, HorizontalViewportZoom(2f, PageSpacePoint(0.5f, 0.5f)), PageFitMode.PAGE)

    @Test fun aStepThatNeitherPansNorZoomsEmitsNothing() {
        val intents = panZoomIntents(PanZoomStep(0f, 0f, 1f, 500f, 1000f), 1000f, 2000f, layout)

        assertTrue(intents.isEmpty())
    }

    @Test fun aPanStepEmitsAPanByInCellFractions() {
        val intents = panZoomIntents(PanZoomStep(100f, -50f, 1f, 500f, 1000f), 1000f, 2000f, layout)

        assertEquals(listOf(GestureIntent.PanBy(0.1f, -0.025f)), intents)
    }

    @Test fun aZoomStepEmitsAZoomByAtThePagePointUnderTheFocalBeforeThePan() {
        val focal = ViewPoint(600f, 700f)

        val intents = panZoomIntents(PanZoomStep(20f, 40f, 1.5f, focal.x, focal.y), 1000f, 2000f, layout)

        val expectedFocal = ReaderGeometry.viewportToPage(layout, ViewportPoint(focal.x, focal.y), clampToPage = true)!!
        assertEquals(2, intents.size)
        val zoom = intents[0] as GestureIntent.ZoomBy
        assertEquals(1.5f, zoom.factor, EPSILON)
        assertEquals(expectedFocal.x, zoom.focal.x, EPSILON)
        assertEquals(expectedFocal.y, zoom.focal.y, EPSILON)
        assertEquals(GestureIntent.PanBy(0.02f, 0.02f), intents[1])
    }

    @Test fun aZoomFocalOffThePageIsClampedOntoIt() {
        val intents = panZoomIntents(PanZoomStep(0f, 0f, 0.8f, -500f, 5000f), 1000f, 2000f, layout)

        val zoom = intents.single() as GestureIntent.ZoomBy
        assertEquals(PageSpacePoint(0f, 1f), zoom.focal)
    }

    @Test fun aDownInsideOrOnTheEdgeOfThePageIsAccepted() {
        val page = ViewRect(100f, 200f, 900f, 1800f)

        assertTrue(pageInkAcceptsDown(ViewPoint(500f, 1000f), page))
        assertTrue(pageInkAcceptsDown(ViewPoint(100f, 200f), page))
        assertTrue(pageInkAcceptsDown(ViewPoint(900f, 1800f), page))
    }

    @Test fun aDownOutsideThePageIsRefused() {
        val page = ViewRect(100f, 200f, 900f, 1800f)

        assertFalse(pageInkAcceptsDown(ViewPoint(99f, 1000f), page))
        assertFalse(pageInkAcceptsDown(ViewPoint(500f, 1801f), page))
        assertFalse(pageInkAcceptsDown(ViewPoint(950f, 100f), page))
    }

    @Test fun anEditThatStaysOnThePageIsAllowed() {
        val extent = PageInkExtent(0.5f)

        assertTrue(pageInkAllowsEdit(extent, SheetRect(0.1f, 0.1f, 0.3f, 0.3f), SheetRect(0.5f, 1.5f, 0.9f, 1.9f)))
    }

    @Test fun anEditThatLeavesThePageIsRefused() {
        val extent = PageInkExtent(0.5f)

        assertFalse(pageInkAllowsEdit(extent, SheetRect(0.1f, 0.1f, 0.3f, 0.3f), SheetRect(0.8f, 0.1f, 1.1f, 0.3f)))
        assertFalse(pageInkAllowsEdit(extent, SheetRect(0.1f, 1.7f, 0.3f, 1.9f), SheetRect(0.1f, 1.9f, 0.3f, 2.1f)))
    }

    @Test fun anEditAlreadyOverflowingMayMoveBackButNotFurtherOut() {
        val extent = PageInkExtent(0.5f)
        val overflowing = SheetRect(-0.05f, 0.1f, 0.2f, 0.3f)

        assertTrue(pageInkAllowsEdit(extent, overflowing, SheetRect(-0.02f, 0.1f, 0.23f, 0.3f)))
        assertFalse(pageInkAllowsEdit(extent, overflowing, SheetRect(-0.08f, 0.1f, 0.17f, 0.3f)))
    }

    @Test fun pageModeConvertsMillimetresAgainstThePagesOwnPrintedWidth() {
        val mode = InkSurfaceMode.Page(pageWidthPt = 612f, pageHeightPt = 792f)

        assertEquals(PageInkExtent.mmToUnits(10f, 612f), mode.mmToUnits(10f), EPSILON)
        assertEquals(PageInkExtent.of(612f, 792f), mode.extent)
    }

    @Test fun sheetModeKeepsTheNominalSheetWidth() {
        assertEquals(10f / 210f, InkSurfaceMode.Sheet.mmToUnits(10f), EPSILON)
    }

    @Test fun aSelectionMoveOffThePageSlidesAlongTheEdgeItHits() {
        val extent = PageInkExtent(0.5f)
        val session = SelectionEditSession(SelectionEditKind.Move, SheetRect(0.6f, 0.5f, 0.8f, 0.7f), SheetPoint(0.7f, 0.6f))

        session.moveWithinPage(SheetPoint(1.0f, 0.9f), extent)

        assertEquals(SheetPoint(0.7f, 0.9f), session.currentSheetPoint)
        assertTrue(extent.allows(session.previewBounds()))
    }

    @Test fun aSelectionMoveThatFitsThePageIsTakenWhole() {
        val extent = PageInkExtent(0.5f)
        val session = SelectionEditSession(SelectionEditKind.Move, SheetRect(0.1f, 0.5f, 0.3f, 0.7f), SheetPoint(0.2f, 0.6f))

        session.moveWithinPage(SheetPoint(0.4f, 1.2f), extent)

        assertEquals(SheetPoint(0.4f, 1.2f), session.currentSheetPoint)
    }
}
