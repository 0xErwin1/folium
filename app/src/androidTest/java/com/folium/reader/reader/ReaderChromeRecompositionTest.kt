package com.folium.reader.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.R
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.HorizontalViewportReducer
import com.folium.reader.core.pdf.HorizontalViewportState
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.perf.ProbedComposition
import com.folium.reader.perf.RecompositionProbe
import com.folium.reader.ui.FoliumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * Pins the claim both chrome bars make by taking readings rather than the whole [ReaderUiState]:
 * a gesture that moves the viewport and nothing else re-runs the page surface, which has to redraw,
 * and neither bar, which has nothing new to say. Widening either bar back to the whole state
 * renders identically, so nothing else in this suite would notice.
 *
 * The states are produced by [HorizontalViewportReducer] from real gesture intents, which is the
 * only thing that ever produces the states [ReaderScreen] is given; they are then published through
 * the `state` parameter, exactly as [ReaderHost] publishes what its presenter reports. A two-finger
 * pinch cannot be synthesised as a gesture on this surface, so the intent is dispatched rather than
 * touched.
 *
 * Each body is counted through a string only it resolves — the zoom reading for the top bar, the
 * spoken position for the bottom bar, the page's own placeholder for the surface. See
 * [RecompositionProbe].
 */
@RunWith(AndroidJUnit4::class)
class ReaderChromeRecompositionTest {

    @get:Rule val compose = createComposeRule()

    private val probe = RecompositionProbe(InstrumentationRegistry.getInstrumentation().targetContext)

    private val pageAspect: (Int) -> Float = { 1f }
    private val onIntent: (GestureIntent) -> Unit = {}
    private val onViewportChanged: (ReaderViewport?) -> Unit = {}
    private val onBack: () -> Unit = {}

    private var published by mutableStateOf(ReaderUiState<BorrowedPage>(HorizontalViewportState.initial(PAGE_COUNT)))

    @Test fun a_pan_re_executes_the_page_surface_and_neither_chrome_bar() {
        val zoomed = reduce(HorizontalViewportState.initial(PAGE_COUNT), GestureIntent.ZoomBy(2.5f, CENTRE))
        val panned = reduce(zoomed, GestureIntent.PanBy(0.2f, 0f))

        assertNotEquals("the pan moved nothing, so it proves nothing", zoomed.zoom.center, panned.zoom.center)
        assertEquals("the pan changed the page", zoomed.currentPage, panned.currentPage)
        assertEquals("the pan changed the zoom scale", zoomed.zoom.scale, panned.zoom.scale, 0f)
        assertEquals("the pan changed the fit mode", zoomed.fitMode, panned.fitMode)

        render(ReaderUiState(zoomed))

        val top = topChromeExecutions(zoomed)
        val bottom = bottomChromeExecutions(zoomed)
        val surface = pageSurfaceExecutions(zoomed)
        assertTrue("the probe never saw the reader compose", top > 0 && bottom > 0 && surface > 0)

        publish(ReaderUiState(panned))

        assertEquals("the top bar re-executed for a pan", top, topChromeExecutions(panned))
        assertEquals("the bottom bar re-executed for a pan", bottom, bottomChromeExecutions(panned))
        assertTrue("the page surface did not re-execute for a pan", pageSurfaceExecutions(panned) > surface)
    }

    private fun topChromeExecutions(state: HorizontalViewportState): Int =
        probe.lookups(R.string.reader_zoom_level, (state.zoom.scale * 100).roundToInt())

    private fun bottomChromeExecutions(state: HorizontalViewportState): Int =
        probe.lookups(R.string.reader_page_position, state.currentPage + 1, state.pageCount)

    private fun pageSurfaceExecutions(state: HorizontalViewportState): Int =
        probe.lookups(R.string.reader_page_loading, state.currentPage + 1)

    private fun reduce(state: HorizontalViewportState, intent: GestureIntent): HorizontalViewportState =
        HorizontalViewportReducer.reduce(state, intent)

    private fun publish(state: ReaderUiState<BorrowedPage>) {
        compose.runOnIdle { published = state }
        compose.waitForIdle()
    }

    private fun render(state: ReaderUiState<BorrowedPage>) {
        published = state

        compose.setContent {
            FoliumTheme {
                ProbedComposition(probe) {
                    ReaderScreen(
                        title = TITLE,
                        state = published,
                        pageAspect = pageAspect,
                        onIntent = onIntent,
                        onViewportChanged = onViewportChanged,
                        onBack = onBack
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        const val PAGE_COUNT = 4
        const val TITLE = "Quarterly report.pdf"
        val CENTRE = PageSpacePoint(0.5f, 0.5f)
    }
}
