package com.folium.reader.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.R
import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.HorizontalViewportReducer
import com.folium.reader.core.pdf.HorizontalViewportState
import com.folium.reader.core.pdf.PageCacheKey
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.ui.FoliumTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the reading surface itself: what is drawn for each state a page can be in, which gestures
 * and controls produce which intent, and that both a phone width and a large-screen width stay
 * usable. Rasters are real bitmaps borrowed from a real cache, so the borrow the screen draws
 * through is the same one the reader uses.
 */
@RunWith(AndroidJUnit4::class)
class HorizontalReaderScreenTest {

    private companion object {
        const val DOUBLE_TAP_SETTLE_MILLIS = 1_000L
    }

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cache = ByteBoundedPageCache<RenderedPage>(16L * 1024 * 1024)
    private val borrows = mutableListOf<BorrowedPage>()
    private val intents = mutableListOf<GestureIntent>()
    private var backPresses = 0

    @After fun releaseBorrows() {
        borrows.forEach { it.release() }
        cache.clear()
    }

    private fun page(
        pageIndex: Int,
        colour: Int = Color.BLACK,
        region: PageSpaceRect = PageSpaceRect(0f, 0f, 1f, 1f)
    ): BorrowedPage {
        val bitmap = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(colour)

        val key = PageCacheKey("fixture", pageIndex, 0L, RenderSpec(120, 200, region))
        cache.put(key, RenderCandidate(RenderedPage(bitmap, region)) {}, bitmap.allocationByteCount.toLong())

        return BorrowedPage(requireNotNull(cache.acquire(key))).also { borrows += it }
    }

    private val shown = mutableStateOf(ReaderUiState<BorrowedPage>(HorizontalViewportState.initial(pageCount = 5)))

    private fun render(
        state: ReaderUiState<BorrowedPage>,
        width: androidx.compose.ui.unit.Dp? = null,
        height: androidx.compose.ui.unit.Dp = 640.dp
    ) {
        shown.value = state
        compose.setContent {
            FoliumTheme {
                val screen: @androidx.compose.runtime.Composable () -> Unit = {
                    ReaderScreen(
                        title = "Field manual.pdf",
                        state = shown.value,
                        pageAspect = { 0.6f },
                        onIntent = { intents += it },
                        onViewportChanged = {},
                        onBack = { backPresses++ }
                    )
                }
                if (width == null) screen() else Box(Modifier.requiredSize(width, height)) { screen() }
            }
        }
    }

    private fun update(state: ReaderUiState<BorrowedPage>) = compose.runOnIdle { shown.value = state }

    /**
     * A single tap only resolves once it is clear no second one is coming, and taps issued back to
     * back would otherwise be read as the double tap that zooms. Letting each one settle is what
     * makes this a test of three separate taps rather than of a pinch by accident.
     */
    private fun tapAndSettle(position: androidx.compose.ui.test.TouchInjectionScope.() -> Offset) {
        compose.onNodeWithTag(ReaderTestTags.PAGER).performTouchInput { click(position()) }
        compose.mainClock.advanceTimeBy(DOUBLE_TAP_SETTLE_MILLIS)
        compose.waitForIdle()
    }

    private fun readingState(
        pages: Map<Int, BorrowedPage>,
        failedPages: Set<Int> = emptySet(),
        state: HorizontalViewportState = HorizontalViewportState.initial(pageCount = 5)
    ) = ReaderUiState(state, pages, failedPages)

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    @Test fun a_rendered_page_is_drawn_and_a_page_still_rendering_says_so_instead_of_showing_another() {
        render(readingState(mapOf(0 to page(0))))

        compose.onNodeWithTag(ReaderTestTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.pageContent(0)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.reader_page_loading, 1)).assertDoesNotExist()
    }

    @Test fun a_page_with_no_raster_yet_shows_its_own_placeholder_and_never_a_neighbours_raster() {
        render(readingState(mapOf(1 to page(1))))

        compose.onNodeWithTag(ReaderTestTags.pageContent(0)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.reader_page_loading, 1)).assertIsDisplayed()
    }

    @Test fun a_page_that_failed_terminally_is_named_rather_than_left_blank() {
        render(readingState(pages = emptyMap(), failedPages = setOf(0)))

        compose.onNodeWithTag(ReaderTestTags.pageFailure(0)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.reader_page_failed, 1)).assertIsDisplayed()
    }

    @Test fun the_chrome_reports_the_position_and_both_bars_are_on_screen_together() {
        render(readingState(mapOf(0 to page(0))))

        compose.onNodeWithTag(ReaderTestTags.CHROME_TOP).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.CHROME_BOTTOM).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.POSITION).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.reader_page_position, 1, 5)).assertIsDisplayed()
        compose.onNodeWithText("Field manual.pdf").assertIsDisplayed()
    }

    @Test fun hidden_chrome_leaves_the_page_alone_on_screen() {
        val hidden = HorizontalViewportReducer.reduce(HorizontalViewportState.initial(5), GestureIntent.HideChrome)
        render(readingState(mapOf(0 to page(0)), state = hidden))

        compose.onNodeWithTag(ReaderTestTags.CHROME_TOP).assertDoesNotExist()
        compose.onNodeWithTag(ReaderTestTags.CHROME_BOTTOM).assertDoesNotExist()
        compose.onNodeWithTag(ReaderTestTags.pageContent(0)).assertIsDisplayed()
    }

    @Test fun the_navigation_controls_turn_pages_and_stop_at_both_ends() {
        render(readingState(mapOf(0 to page(0))))

        compose.onNodeWithTag(ReaderTestTags.PREVIOUS).assertIsNotEnabled()
        compose.onNodeWithTag(ReaderTestTags.NEXT).assertIsEnabled().performClick()
        assertTrue(GestureIntent.PageForward in intents)

        val last = HorizontalViewportState.initial(5).copy(currentPage = 4)
        update(readingState(mapOf(4 to page(4)), state = last))
        compose.onNodeWithTag(ReaderTestTags.NEXT).assertIsNotEnabled()
        compose.onNodeWithTag(ReaderTestTags.PREVIOUS).assertIsEnabled().performClick()
        assertTrue(GestureIntent.PageBack in intents)
    }

    @Test fun tapping_the_outer_edges_turns_the_page_and_the_middle_toggles_the_chrome() {
        render(readingState(mapOf(0 to page(0))))

        tapAndSettle { centerLeft }
        tapAndSettle { centerRight }
        tapAndSettle { center }

        assertEquals(
            listOf(GestureIntent.PageBack, GestureIntent.PageForward, GestureIntent.ToggleChrome),
            intents.filter { it is GestureIntent.PageBack || it is GestureIntent.PageForward || it is GestureIntent.ToggleChrome }
        )
    }

    @Test fun double_tapping_zooms_about_the_point_that_was_tapped() {
        render(readingState(mapOf(0 to page(0))))

        compose.onNodeWithTag(ReaderTestTags.PAGER).performTouchInput { doubleClick(centerLeft) }
        compose.waitForIdle()

        val zoom = intents.filterIsInstance<GestureIntent.ZoomBy>().single()
        assertTrue("a double tap must zoom in", zoom.factor > 1f)
        assertTrue("it must anchor left of centre", zoom.focal.x < 0.5f)
    }

    @Test fun the_fit_page_control_is_offered_only_while_the_page_is_actually_zoomed() {
        render(readingState(mapOf(0 to page(0))))
        compose.onNodeWithTag(ReaderTestTags.RESET_ZOOM).assertIsNotEnabled()

        val zoomed = HorizontalViewportReducer.reduce(
            HorizontalViewportState.initial(5),
            GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f))
        )
        update(readingState(mapOf(0 to page(0)), state = zoomed))

        compose.onNodeWithTag(ReaderTestTags.RESET_ZOOM).assertIsEnabled().performClick()
        assertTrue(GestureIntent.ResetZoom in intents)
    }

    @Test fun leaving_the_reader_is_reachable_from_the_chrome() {
        render(readingState(mapOf(0 to page(0))))

        compose.onNodeWithTag(ReaderTestTags.BACK).assertIsDisplayed().performClick()
        assertEquals(1, backPresses)
    }

    @Test fun every_control_meets_the_minimum_touch_target_at_the_narrow_width() {
        render(readingState(mapOf(0 to page(0))), width = 360.dp)

        listOf(
            ReaderTestTags.BACK,
            ReaderTestTags.PREVIOUS,
            ReaderTestTags.NEXT,
            ReaderTestTags.RESET_ZOOM
        ).forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun the_reader_stays_usable_at_a_large_screen_width() {
        render(readingState(mapOf(0 to page(0))), width = 1280.dp, height = 800.dp)

        compose.onNodeWithTag(ReaderTestTags.pageContent(0)).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.CHROME_TOP).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.CHROME_BOTTOM).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.POSITION).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.NEXT).assertIsDisplayed().assertIsEnabled()
    }

    /**
     * A raster rendered before a zoom keeps being drawn afterwards, scaled into place rather than
     * discarded, which is what stops a zoom from blanking the page it is zooming into.
     */
    @Test fun a_raster_from_before_a_zoom_is_still_drawn_while_the_sharper_one_is_pending() {
        val zoomed = HorizontalViewportReducer.reduce(
            HorizontalViewportState.initial(5),
            GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f))
        )
        render(readingState(mapOf(0 to page(0, region = PageSpaceRect(0f, 0f, 1f, 1f))), state = zoomed))

        compose.onNodeWithTag(ReaderTestTags.pageContent(0)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.reader_page_loading, 1)).assertDoesNotExist()
    }
}
