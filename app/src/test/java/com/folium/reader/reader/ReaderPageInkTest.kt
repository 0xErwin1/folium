package com.folium.reader.reader

import com.folium.reader.core.ink.PageInkStore
import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.HorizontalViewportZoom
import com.folium.reader.core.pdf.PageFitMode
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.core.sequence.SequenceItem
import com.folium.reader.core.sequence.SpreadUnit
import com.folium.reader.ink.PanZoomStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val EPSILON = 1e-4f

/**
 * Which book pages the reader writes on live while writing, when their tools act, and what a page
 * surface's own pan and zoom requests ask the reader for.
 */
class ReaderPageInkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sheet = SequenceItem.Sheet(SheetId("sheet-a"), pageIndex = 4, ordinal = 1)
    private val cell = ReaderViewport(widthPx = 1000, heightPx = 2000)
    private val layout = ReaderGeometry.layout(cell, 0.5f, HorizontalViewportZoom(2f, PageSpacePoint(0.5f, 0.5f)), PageFitMode.PAGE)

    @Test fun `nothing is written on while reading`() {
        assertEquals(emptySet<Int>(), writablePages(SpreadUnit(SequenceItem.Page(4), SequenceItem.Page(5)), writing = false))
    }

    @Test fun `a single page is written on alone`() {
        assertEquals(setOf(4), writablePages(SpreadUnit(SequenceItem.Page(4), null), writing = true))
    }

    @Test fun `both pages of a spread are written on`() {
        assertEquals(setOf(4, 5), writablePages(SpreadUnit(SequenceItem.Page(4), SequenceItem.Page(5)), writing = true))
    }

    @Test fun `a page beside a sheet is written on and the sheet is left to its own pane`() {
        assertEquals(setOf(4), writablePages(SpreadUnit(SequenceItem.Page(4), sheet), writing = true))
        assertEquals(emptySet<Int>(), writablePages(SpreadUnit(sheet, null), writing = true))
    }

    @Test fun `no unit has no page to write on`() {
        assertEquals(emptySet<Int>(), writablePages(null, writing = true))
    }

    @Test fun `the tools act once any page on screen is live`() {
        val ink = PageInkState.Opening(5)

        assertFalse(pageInkToolsLive(emptyMap(), setOf(4)))
        assertFalse(pageInkToolsLive(mapOf(4 to PageInkState.Opening(4), 5 to ink), setOf(4, 5)))
        assertFalse(pageInkToolsLive(mapOf(4 to PageInkState.Unavailable(4, openElsewhere = true)), setOf(4)))
        assertFalse(pageInkToolsLive(mapOf(4 to PageInkState.Opening(4)), emptySet()))
    }

    @Test fun `the header explains the muted tools only while a page on screen is bound elsewhere`() {
        val boundElsewhere = PageInkState.Unavailable(4, openElsewhere = false, boundElsewhere = true)

        assertTrue(pageInkBoundElsewhere(mapOf(4 to boundElsewhere), setOf(4)))
        assertTrue(pageInkBoundElsewhere(mapOf(4 to boundElsewhere, 5 to PageInkState.Opening(5)), setOf(4, 5)))
        assertFalse(pageInkBoundElsewhere(mapOf(4 to boundElsewhere), setOf(6)))
        assertFalse(pageInkBoundElsewhere(mapOf(4 to PageInkState.Unavailable(4, openElsewhere = true)), setOf(4)))
        assertFalse(pageInkBoundElsewhere(mapOf(4 to PageInkState.Opening(4)), setOf(4)))
    }

    @Test fun `a live page on screen makes the tools act even while its neighbour opens`() {
        val store = PageInkStore(tempFolder.newFolder("page-ink"))
        val live = store.open(4)

        try {
            val states = mapOf(4 to PageInkState.Live(4, live), 5 to PageInkState.Opening(5))

            assertTrue(pageInkToolsLive(states, setOf(4, 5)))
            assertFalse(pageInkToolsLive(states, setOf(5)))
        } finally {
            live.close()
        }
    }

    @Test fun `a pinch on a page names that page as the one to zoom`() {
        val intents = pageSurfaceIntents(PanZoomStep(10f, 0f, 1.5f, 600f, 700f), 1000f, 2000f, layout, page = 5)

        val zoom = intents[0] as GestureIntent.ZoomBy
        assertEquals(1.5f, zoom.factor, EPSILON)
        assertEquals(5, zoom.focusPage)
        assertEquals(GestureIntent.PanBy(0.01f, 0f), intents[1])
    }

    @Test fun `a zoom asked from the view panel scales the page about the centre of its cell`() {
        val currentZoom = layout.pageWidth / cell.widthPx

        val intents = pageZoomIntents(targetZoom = currentZoom * 1.2f, layout = layout, page = 4)

        val zoom = intents.single() as GestureIntent.ZoomBy
        val centre = ReaderGeometry.viewportToPage(layout, ViewportPoint(500f, 1000f), clampToPage = true)!!
        assertEquals(1.2f, zoom.factor, EPSILON)
        assertEquals(centre.x, zoom.focal.x, EPSILON)
        assertEquals(centre.y, zoom.focal.y, EPSILON)
        assertEquals(4, zoom.focusPage)
    }

    @Test fun `a zoom the page is already at asks for nothing`() {
        val currentZoom = layout.pageWidth / cell.widthPx

        assertTrue(pageZoomIntents(targetZoom = currentZoom, layout = layout, page = 4).isEmpty())
    }

    @Test fun `a page with no width asks for nothing`() {
        val empty = ViewportLayout(cell, 0f, 0f, 0f, 0f)

        assertNull(pageZoomIntents(targetZoom = 2f, layout = empty, page = 4).firstOrNull())
    }

    @Test fun `the first page opened binds unbound ink to the document`() {
        val store = PageInkStore(tempFolder.newFolder("page-ink"))
        val open = pageInkOpener(store) { "doc-a" }

        open(4).close()

        assertEquals("doc-a", store.boundIdentity())
    }

    @Test fun `ink already bound to the document opens as it is`() {
        val store = PageInkStore(tempFolder.newFolder("page-ink"))
        store.bind("doc-a")
        val open = pageInkOpener(store) { "doc-a" }

        open(4).close()

        assertEquals("doc-a", store.boundIdentity())
    }

    @Test fun `ink bound to another document is never written on or rebound`() {
        val store = PageInkStore(tempFolder.newFolder("page-ink"))
        store.bind("doc-old")
        val open = pageInkOpener(store) { "doc-new" }

        val failure = runCatching { open(4) }.exceptionOrNull()

        assertTrue(failure is PageInkBoundElsewhereException)
        assertEquals("doc-old", store.boundIdentity())
        assertEquals(emptySet<Int>(), store.pagesWithInk())
    }
}
