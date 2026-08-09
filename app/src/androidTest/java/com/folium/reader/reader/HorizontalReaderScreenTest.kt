package com.folium.reader.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.R
import com.folium.reader.core.library.AppearanceMode
import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.HorizontalViewportReducer
import com.folium.reader.core.pdf.HorizontalViewportState
import com.folium.reader.core.pdf.PageCacheKey
import com.folium.reader.core.pdf.PageFitMode
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.text.TextBlock
import com.folium.reader.core.text.TextLine
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSelection
import com.folium.reader.core.text.SelectionEndpoint
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextWord
import com.folium.reader.ui.FoliumTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicReference

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
        const val FIRST_FIXTURE_WORD_CENTER_X = .24f
        const val LONG_PRESS_MILLIS = 600L

        /** A colour no other part of the reader draws, so any pixel of it can only have come from one page. */
        const val NEIGHBOUR = 0xFFFF00FFL.toInt()
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

        return BorrowedPage.Cached(requireNotNull(cache.acquire(key))).also { borrows += it }
    }

    private val shown = mutableStateOf(ReaderUiState<BorrowedPage>(HorizontalViewportState.initial(pageCount = 5)))
    private val appearanceMode = mutableStateOf(AppearanceMode.SYSTEM)

    /**
     * Whether an intent is fed back into the state the screen is rendering. Most assertions are
     * about which intent a gesture produces and are clearer against a state that does not move
     * under them; a gesture whose own behaviour depends on what it has already done to the state
     * needs the loop closed instead.
     */
    private var live = false

    private fun render(
        state: ReaderUiState<BorrowedPage>,
        width: androidx.compose.ui.unit.Dp? = null,
        height: androidx.compose.ui.unit.Dp = 640.dp,
        textPage: TextPage? = null,
        search: ReaderSearchState? = null,
        searchState: State<ReaderSearchState?>? = null,
        onSearch: (String) -> Unit = {},
        onSearchNext: () -> Unit = {}
    ) {
        shown.value = state
        compose.setContent {
            FoliumTheme(appearanceMode.value) {
                val screen: @androidx.compose.runtime.Composable () -> Unit = {
                    ReaderScreen(
                        title = "Field manual.pdf",
                        state = shown.value,
                        pageAspect = { 0.6f },
                        onIntent = { record(it) },
                        onViewportChanged = {},
                        onBack = { backPresses++ },
                        textPage = textPage,
                        search = searchState?.value ?: search,
                        onSearch = onSearch,
                        onSearchNext = onSearchNext
                    )
                }
                if (width == null) screen() else Box(Modifier.requiredSize(width, height)) { screen() }
            }
        }
    }

    private fun record(intent: GestureIntent) {
        intents += intent
        if (live) shown.value = shown.value.copy(state = HorizontalViewportReducer.reduce(shown.value.state, intent))
    }

    private fun update(state: ReaderUiState<BorrowedPage>) = compose.runOnIdle { shown.value = state }

    private fun renderSelectionHarness(
        textPage: TextPage,
        initialSelection: TextSelection,
        topOcclusionPx: State<Float?>,
        observedSelection: AtomicReference<TextSelection>
    ) {
        compose.setContent {
            FoliumTheme(appearanceMode.value) {
                val selection = androidx.compose.runtime.remember { mutableStateOf<TextSelection?>(initialSelection) }

                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val viewport = ReaderViewport.of(constraints.maxWidth, constraints.maxHeight)
                    if (viewport != null) {
                        val readerState = HorizontalViewportState.initial(1)
                        ReaderSelectionOverlay(
                            textPage = textPage,
                            layout = ReaderGeometry.layout(
                                viewport,
                                pageAspect = .6f,
                                zoom = readerState.zoom,
                                fitMode = readerState.fitMode
                            ),
                            selection = selection.value,
                            topOcclusionPx = topOcclusionPx.value,
                            onSelectionChanged = { updated ->
                                selection.value = updated
                                if (updated != null) observedSelection.set(updated)
                            }
                        )
                    }
                }
            }
        }
    }

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
    ) = ReaderUiState(state = state, pages = pages, failedPages = failedPages)

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun selectableTextPage() = TextPage(
        listOf(TextBlock(listOf(TextLine(listOf(
            TextWord("One", PageSpaceRect(.18f, .45f, .3f, .55f), 0),
            TextWord("two", PageSpaceRect(.36f, .45f, .48f, .55f), 1),
            TextWord("three", PageSpaceRect(.54f, .45f, .68f, .55f), 2),
            TextWord("four", PageSpaceRect(.75f, .45f, .88f, .55f), 3)
        ), 0)), 0)),
        TextSource.NATIVE_PDF
    )

    private fun nearTopSelectableTextPage() = TextPage(
        listOf(TextBlock(listOf(TextLine(listOf(
            TextWord("One", PageSpaceRect(.18f, .03f, .3f, .08f), 0)
        ), 0)), 0)),
        TextSource.NATIVE_PDF
    )

    private fun wideSelectableTextPage() = TextPage(
        listOf(TextBlock(listOf(TextLine(listOf(
            TextWord("One", PageSpaceRect(.18f, .45f, .82f, .55f), 0)
        ), 0)), 0)),
        TextSource.NATIVE_PDF
    )

    private fun multiLineSelectableTextPage() = TextPage(
        listOf(TextBlock(listOf(
            TextLine(listOf(
                TextWord("One", PageSpaceRect(.18f, .36f, .30f, .44f), 0),
                TextWord("two", PageSpaceRect(.36f, .36f, .48f, .44f), 1),
                TextWord("three", PageSpaceRect(.54f, .36f, .68f, .44f), 2)
            ), 0),
            TextLine(listOf(
                TextWord("four", PageSpaceRect(.18f, .54f, .30f, .62f), 0),
                TextWord("five", PageSpaceRect(.36f, .54f, .48f, .62f), 1)
            ), 1)
        ), 0)),
        TextSource.NATIVE_PDF
    )

    private fun splitPage(pageIndex: Int): BorrowedPage {
        val bitmap = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.BLACK)
            clipRect(bitmap.width / 2, 0, bitmap.width, bitmap.height)
            drawColor(Color.WHITE)
        }

        val region = PageSpaceRect(0f, 0f, 1f, 1f)
        val key = PageCacheKey("split-fixture", pageIndex, 0L, RenderSpec(120, 200, region))
        cache.put(key, RenderCandidate(RenderedPage(bitmap, region)) {}, bitmap.allocationByteCount.toLong())

        return BorrowedPage.Cached(requireNotNull(cache.acquire(key))).also { borrows += it }
    }

    private fun TouchInjectionScope.longClickFirstFixtureWord() {
        longClick(percentOffset(FIRST_FIXTURE_WORD_CENTER_X, .5f))
    }

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
        compose.onNodeWithTag(ReaderTestTags.POSITION)
            .assertIsDisplayed()
            .assertContentDescriptionEquals(string(R.string.reader_page_position, 1, 5))
        compose.onNodeWithText(string(R.string.reader_page_indicator, 1, 5)).assertIsDisplayed()
        compose.onNodeWithText("Field manual.pdf").assertIsDisplayed()
    }

    /** Paging, and the way out of the document, are all the bars themselves are allowed to carry. */
    @Test fun the_bars_carry_nothing_but_paging_the_title_and_the_way_into_the_menu() {
        render(readingState(mapOf(0 to page(0))))

        listOf(ReaderTestTags.BACK, ReaderTestTags.OVERFLOW).forEach {
            compose.onNodeWithTag(ReaderTestTags.CHROME_TOP).onChildren().assertAny(hasTestTag(it))
        }
        compose.onNodeWithTag(ReaderTestTags.CHROME_TOP).onChildren().assertAll(!hasTestTag(ReaderTestTags.FIT_PAGE))

        compose.onNodeWithTag(ReaderTestTags.PREVIOUS).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.NEXT).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.POSITION).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.ZOOM).assertDoesNotExist()
        compose.onNodeWithTag(ReaderTestTags.FIT_PAGE).assertDoesNotExist()
        compose.onNodeWithTag(ReaderTestTags.FIT_WIDTH).assertDoesNotExist()
    }

    @Test fun the_overflow_menu_is_where_both_ways_of_fitting_a_page_live() {
        render(readingState(mapOf(0 to page(0))))

        compose.onNodeWithTag(ReaderTestTags.OVERFLOW).assertIsDisplayed().performClick()
        compose.onNodeWithTag(ReaderTestTags.FIT_WIDTH).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.FIT_PAGE).assertIsDisplayed().performClick()

        assertTrue(GestureIntent.SetFitMode(PageFitMode.PAGE) in intents)
        assertTrue(GestureIntent.ResetZoom in intents)
    }

    /**
     * Choosing the fit a page is already at is how a reader who has zoomed in gets back to it, so
     * it has to give up the zoom rather than being read as a no-op.
     */
    @Test fun choosing_the_fit_a_page_already_has_still_gives_up_the_zoom() {
        val zoomed = HorizontalViewportReducer.reduce(
            HorizontalViewportState.initial(5),
            GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f))
        )
        render(readingState(mapOf(0 to page(0)), state = zoomed))

        compose.onNodeWithTag(ReaderTestTags.OVERFLOW).performClick()
        compose.onNodeWithTag(ReaderTestTags.FIT_WIDTH).performClick()

        assertTrue(GestureIntent.ResetZoom in intents)
    }

    @Test fun a_zoom_is_reported_in_the_bar_only_while_there_is_one_and_undoes_itself_when_tapped() {
        render(readingState(mapOf(0 to page(0))))
        compose.onNodeWithTag(ReaderTestTags.ZOOM).assertDoesNotExist()

        val zoomed = HorizontalViewportReducer.reduce(
            HorizontalViewportState.initial(5),
            GestureIntent.ZoomBy(2f, PageSpacePoint(0.5f, 0.5f))
        )
        update(readingState(mapOf(0 to page(0)), state = zoomed))

        compose.onNodeWithTag(ReaderTestTags.ZOOM)
            .assertIsDisplayed()
            .assertContentDescriptionEquals(string(R.string.reader_zoom_level, 200))
            .performClick()
        assertTrue(GestureIntent.ResetZoom in intents)
    }

    /**
     * The chrome is hidden because the reader asked for it to be, so nothing else may bring it
     * back — least of all turning a page, which is what a reader does most while reading.
     */
    @Test fun chrome_hidden_for_reading_survives_turning_pages_and_only_a_tap_restores_it() {
        live = true
        val hidden = HorizontalViewportReducer.reduce(HorizontalViewportState.initial(5), GestureIntent.HideChrome)
        render(readingState(mapOf(0 to page(0)), state = hidden))

        repeat(3) { tapAndSettle { centerRight } }

        assertEquals(3, intents.count { it is GestureIntent.PageForward })
        assertTrue("nothing may re-show the chrome on its own", shown.value.state.chromeVisible.not())
        compose.onNodeWithTag(ReaderTestTags.CHROME_TOP).assertDoesNotExist()
        compose.onNodeWithTag(ReaderTestTags.CHROME_BOTTOM).assertDoesNotExist()

        tapAndSettle { center }
        compose.onNodeWithTag(ReaderTestTags.CHROME_TOP).assertIsDisplayed()
    }

    /**
     * A pinch crosses from a fitted page to a zoomed one part-way through, and the reader's fingers
     * do not come off the glass to mark the crossing. Every event after it must therefore still
     * reach the same detector — the defect this pins is a detector rebuilt at exactly that moment,
     * which leaves the rest of the pinch going nowhere until the gesture is started over.
     */
    @Test fun one_continuous_pinch_keeps_zooming_after_the_page_has_become_zoomed() {
        live = true
        render(readingState(mapOf(0 to page(0))))

        val pager = compose.onNodeWithTag(ReaderTestTags.PAGER)
        pager.performTouchInput {
            down(0, center + Offset(-40f, 0f))
            down(1, center + Offset(40f, 0f))
        }

        val spread = mutableListOf<Int>()
        repeat(8) { step ->
            val reach = 60f + step * 60f
            pager.performTouchInput {
                moveTo(0, center + Offset(-reach, 0f))
                moveTo(1, center + Offset(reach, 0f))
            }
            compose.waitForIdle()
            spread += intents.count { it is GestureIntent.ZoomBy }
        }
        pager.performTouchInput { up(0); up(1) }

        val zoomedAfter = spread.indexOfFirst { it > 0 }
        assertTrue("the pinch must zoom at all", zoomedAfter >= 0)
        assertTrue("the page must actually leave the fitted scale", shown.value.state.zoom.scale > 1f)
        assertTrue(
            "the pinch must keep zooming once the page is zoomed, saw $spread",
            spread.last() > spread[zoomedAfter]
        )
    }

    /**
     * The pager keeps the next page laid out immediately beside this one, and a page drawn under a
     * zoom is far wider than the slot it owns. Nothing may reach out of its own slot, or a reader
     * zooming in sees the next page laid over the one they are reading until the UI settles.
     */
    @Test fun a_zoomed_page_never_draws_over_the_page_beside_it() {
        val zoomed = HorizontalViewportReducer.reduce(
            HorizontalViewportState.initial(5),
            GestureIntent.ZoomBy(3f, PageSpacePoint(0.5f, 0.5f))
        )
        render(
            readingState(mapOf(0 to page(0, colour = Color.BLACK), 1 to page(1, colour = NEIGHBOUR)), state = zoomed)
        )

        val pixels = capturePageRetryingTheCopy(0)
        val intruding = (0 until pixels.width step 4).sumOf { x ->
            (0 until pixels.height step 4).count { y -> pixels[x, y] == ComposeColor(NEIGHBOUR) }
        }

        assertEquals("the neighbouring page bled into this one", 0, intruding)
    }

    /**
     * `captureToImage` reads the window back through `PixelCopy`, which times out intermittently on
     * a software-rendered emulator. Only the read-back is retried — whatever pixels it returns are
     * asserted unchanged — so a genuine bleed still fails on the first successful capture.
     */
    private fun capturePageRetryingTheCopy(page: Int, attempts: Int = 4): PixelMap {
        var lastFailure: AssertionError? = null

        repeat(attempts) {
            compose.waitForIdle()

            try {
                return compose.onNodeWithTag(ReaderTestTags.page(page)).captureToImage().toPixelMap()
            } catch (failure: AssertionError) {
                if (failure.message?.contains("PixelCopy") != true) throw failure
                lastFailure = failure
            }
        }

        throw AssertionError("PixelCopy never returned the page after $attempts attempts", lastFailure)
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

    @Test fun leaving_the_reader_is_reachable_from_the_chrome() {
        render(readingState(mapOf(0 to page(0))))

        compose.onNodeWithTag(ReaderTestTags.BACK).assertIsDisplayed().performClick()
        assertEquals(1, backPresses)
    }

    @Test fun every_control_meets_the_minimum_touch_target_at_the_narrow_width() {
        render(readingState(mapOf(0 to page(0))), width = 360.dp)

        listOf(
            ReaderTestTags.BACK,
            ReaderTestTags.OVERFLOW,
            ReaderTestTags.PREVIOUS,
            ReaderTestTags.NEXT
        ).forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
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

    @Test fun long_press_selects_a_word_draws_accessible_handles_and_copies_through_click_and_semantics() {
        render(readingState(mapOf(0 to page(0))), textPage = nearTopSelectableTextPage())
        val overlay = compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY)
        val initialOverlayBounds = overlay.fetchSemanticsNode().boundsInRoot
        val selectedPageRect = PageSpaceRect(.18f, .03f, .3f, .08f)
        val selectionCenter = fixturePageRect(initialOverlayBounds, selectedPageRect).center -
            initialOverlayBounds.topLeft
        overlay.performTouchInput { longClick(selectionCenter) }

        compose.onNodeWithTag(ReaderTestTags.SELECTION_HIGHLIGHT).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.SELECTION_ANCHOR)
            .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(ReaderTestTags.SELECTION_FOCUS)
            .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

        val copyLabel = string(R.string.reader_selection_copy)
        compose.onNodeWithTag(ReaderTestTags.OVERFLOW).assertIsDisplayed()
        assertEquals(
            null,
            overlay.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)
        )
        val copy = compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals(copyLabel)
        val copyBounds = copy.fetchSemanticsNode().boundsInRoot
        val chromeBounds = compose.onNodeWithTag(ReaderTestTags.CHROME_TOP).fetchSemanticsNode().boundsInRoot
        val overlayBounds = overlay.fetchSemanticsNode().boundsInRoot
        val selectedBand = fixturePageRect(overlayBounds, selectedPageRect)
        val chromeGap = with(compose.density) { 8.dp.toPx() }
        assertTrue("copy must clear visible top chrome", copyBounds.top >= chromeBounds.bottom + chromeGap)
        assertTrue("copy must not remain fixed in top chrome", !copyBounds.overlaps(chromeBounds))
        assertTrue("copy must not cover selected text", !copyBounds.overlaps(selectedBand))
        val nearEndpointTolerance = with(compose.density) { 64.dp.toPx() }
        assertTrue(
            "copy must stay near the selected endpoint",
            kotlin.math.abs(copyBounds.center.x - selectedBand.right) < nearEndpointTolerance
        )

        copy.performClick()
        compose.onNodeWithText(copyLabel).assertDoesNotExist()
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("One", clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
        compose.onNodeWithTag(ReaderTestTags.SELECTION_HIGHLIGHT).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.SELECTION_ANCHOR).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.SELECTION_FOCUS).assertIsDisplayed()

        val customCopy = compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY)
            .fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)?.single()
        assertTrue(requireNotNull(customCopy?.action).invoke())
        assertEquals("One", clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
        compose.onNodeWithTag(ReaderTestTags.SELECTION_HIGHLIGHT).assertIsDisplayed()
    }

    @Test fun long_press_exposes_contextual_copy_without_forcing_hidden_chrome_visible() {
        val hidden = HorizontalViewportReducer.reduce(HorizontalViewportState.initial(5), GestureIntent.HideChrome)
        render(readingState(mapOf(0 to page(0)), state = hidden), textPage = selectableTextPage())
        compose.onNodeWithTag(ReaderTestTags.CHROME_TOP).assertDoesNotExist()
        compose.onNodeWithTag(ReaderTestTags.CHROME_BOTTOM).assertDoesNotExist()

        compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY)
            .performTouchInput { longClickFirstFixtureWord() }

        compose.onNodeWithTag(ReaderTestTags.CHROME_TOP).assertDoesNotExist()
        compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertContentDescriptionEquals(string(R.string.reader_selection_copy))
        compose.onNodeWithTag(ReaderTestTags.CHROME_BOTTOM).assertDoesNotExist()
    }

    @Test fun hidden_to_visible_chrome_suppresses_copy_until_occlusion_is_measured() {
        val topOcclusionPx = mutableStateOf<Float?>(0f)
        val observedSelection = AtomicReference(TextSelection(0, 0))
        renderSelectionHarness(
            textPage = nearTopSelectableTextPage(),
            initialSelection = observedSelection.get(),
            topOcclusionPx = topOcclusionPx,
            observedSelection = observedSelection
        )
        compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY).assertIsDisplayed()

        compose.runOnIdle { topOcclusionPx.value = null }
        compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY).assertDoesNotExist()

        val measuredChromeBottom = with(compose.density) { 88.dp.toPx() }
        compose.runOnIdle { topOcclusionPx.value = measuredChromeBottom }
        val copyBounds = compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val gap = with(compose.density) { 8.dp.toPx() }
        assertTrue(copyBounds.top >= measuredChromeBottom + gap)
    }

    @Test fun contextual_copy_uses_high_contrast_container_and_icon_in_every_appearance() {
        appearanceMode.value = AppearanceMode.LIGHT
        render(readingState(mapOf(0 to page(0))), textPage = selectableTextPage())
        compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY).performTouchInput { longClickFirstFixtureWord() }

        listOf(
            Triple(AppearanceMode.LIGHT, ComposeColor(0xFF1A1A1A), ComposeColor.White),
            Triple(AppearanceMode.DARK, ComposeColor(0xFFEDEDED), ComposeColor(0xFF101010)),
            Triple(AppearanceMode.E_INK_LIGHT, ComposeColor(0xFF171816), ComposeColor(0xFFFAFAF6)),
            Triple(AppearanceMode.E_INK_DARK, ComposeColor(0xFFF3F2E8), ComposeColor(0xFF171816))
        ).forEach { (mode, container, icon) ->
            compose.runOnIdle { appearanceMode.value = mode }

            val copy = compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY)
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            val pixels = copy.captureToImage().toPixelMap()

            assertTrue("$mode copy container did not render with primary", pixels.containsColour(container))
            assertTrue("$mode copy icon did not render with onPrimary", pixels.containsColour(icon))
            assertTrue("$mode copy contrast was below 7:1", contrastRatio(container, icon) >= 7f)
        }
    }

    @Test fun dragging_a_handle_resizes_the_range_and_tapping_outside_clears_without_toggling_chrome() {
        render(readingState(mapOf(0 to page(0))), textPage = selectableTextPage())
        compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY)
            .performTouchInput { longClickFirstFixtureWord() }
        val copyBeforeDrag = compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY)
            .fetchSemanticsNode().boundsInRoot

        val handleCenter = compose.onNodeWithTag(ReaderTestTags.SELECTION_FOCUS)
            .fetchSemanticsNode().boundsInRoot.center
        val downTime = SystemClock.uptimeMillis()
        injectTouch(MotionEvent.ACTION_DOWN, downTime, handleCenter)
        SystemClock.sleep(50)
        val finalPoint = handleCenter + Offset(800f, 0f)
        injectTouch(MotionEvent.ACTION_MOVE, downTime, finalPoint)
        compose.waitForIdle()
        compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY).assertDoesNotExist()

        injectTouch(MotionEvent.ACTION_UP, downTime, finalPoint)
        compose.waitForIdle()
        compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY).assertIsDisplayed()
        val copyAfterDrag = compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("copy must follow the final handle endpoint", copyAfterDrag.center.x > copyBeforeDrag.center.x)
        compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY).performClick()
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("One two three four", clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())

        val baseline = intents.size
        compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY).performTouchInput {
            click(center + percentOffset(0f, .4f))
        }
        compose.mainClock.advanceTimeBy(DOUBLE_TAP_SETTLE_MILLIS)
        compose.waitForIdle()

        compose.onNodeWithTag(ReaderTestTags.SELECTION_HIGHLIGHT).assertDoesNotExist()
        assertEquals(baseline, intents.size)
    }

    @Test fun selection_survives_zoom_but_stays_cleared_after_returning_to_the_same_cached_page() {
        val text = selectableTextPage()
        render(readingState(mapOf(0 to page(0), 1 to page(1))), textPage = text)
        compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY)
            .performTouchInput { longClickFirstFixtureWord() }
        val copyBeforeZoom = compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY)
            .fetchSemanticsNode().boundsInRoot

        val zoomed = HorizontalViewportReducer.reduce(
            shown.value.state,
            GestureIntent.ZoomBy(2f, PageSpacePoint(.325f, .5f))
        )
        update(readingState(mapOf(0 to page(0), 1 to page(1)), state = zoomed))
        compose.onNodeWithTag(ReaderTestTags.SELECTION_HIGHLIGHT).assertIsDisplayed()
        val copyAfterZoom = compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY)
            .fetchSemanticsNode().boundsInRoot
        val handleAfterZoom = compose.onNodeWithTag(ReaderTestTags.SELECTION_FOCUS)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("copy must be recomputed after zoom", copyAfterZoom != copyBeforeZoom)

        val panned = HorizontalViewportReducer.reduce(zoomed, GestureIntent.PanBy(.1f, .08f))
        update(readingState(mapOf(0 to page(0), 1 to page(1)), state = panned))
        val copyAfterPan = compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY)
            .fetchSemanticsNode().boundsInRoot
        val handleAfterPan = compose.onNodeWithTag(ReaderTestTags.SELECTION_FOCUS)
            .fetchSemanticsNode().boundsInRoot
        val overlayBounds = compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY)
            .fetchSemanticsNode().boundsInRoot
        val viewport = requireNotNull(ReaderViewport.of(
            overlayBounds.width.roundToInt(),
            overlayBounds.height.roundToInt()
        ))
        val endpoint = PageSpacePoint(.3f, .55f)
        val beforePanPoint = ReaderGeometry.pageToViewport(
            ReaderGeometry.layout(viewport, .6f, zoomed.zoom, zoomed.fitMode),
            endpoint
        )
        val afterPanPoint = ReaderGeometry.pageToViewport(
            ReaderGeometry.layout(viewport, .6f, panned.zoom, panned.fitMode),
            endpoint
        )
        val expectedDelta = Offset(
            afterPanPoint.x - beforePanPoint.x,
            afterPanPoint.y - beforePanPoint.y
        )
        val handleDelta = handleAfterPan.center - handleAfterZoom.center
        val copyDelta = copyAfterPan.center - copyAfterZoom.center

        assertEquals(expectedDelta.x, handleDelta.x, 2f)
        assertEquals(expectedDelta.y, handleDelta.y, 2f)
        assertEquals(handleDelta.x, copyDelta.x, 2f)
        assertEquals(handleDelta.y, copyDelta.y, 2f)
        val relativeBefore = copyAfterZoom.center - handleAfterZoom.center
        val relativeAfter = copyAfterPan.center - handleAfterPan.center
        assertEquals(relativeBefore.x, relativeAfter.x, 2f)
        assertEquals(relativeBefore.y, relativeAfter.y, 2f)

        update(readingState(
            mapOf(0 to page(0), 1 to page(1)),
            state = panned.copy(currentPage = 1, generation = panned.generation + 1)
        ))
        compose.waitForIdle()
        compose.onNodeWithTag(ReaderTestTags.SELECTION_HIGHLIGHT).assertDoesNotExist()
        compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY).assertDoesNotExist()

        update(readingState(
            mapOf(0 to page(0), 1 to page(1)),
            state = panned.copy(currentPage = 0, generation = panned.generation + 2)
        ))
        compose.waitForIdle()
        compose.onNodeWithTag(ReaderTestTags.SELECTION_HIGHLIGHT).assertDoesNotExist()
    }

    @Test fun a_swipe_started_before_long_press_still_belongs_to_the_pager() {
        render(readingState(mapOf(0 to page(0), 1 to page(1))), textPage = selectableTextPage())
        compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY).performTouchInput {
            swipe(centerRight, centerLeft, durationMillis = 150)
        }
        compose.waitForIdle()

        assertTrue(intents.any { it is GestureIntent.FlingToPage && it.targetPage == 1 })
        compose.onNodeWithTag(ReaderTestTags.SELECTION_HIGHLIGHT).assertDoesNotExist()
    }

    @Test fun long_press_drag_selects_a_continuous_range_across_multiple_lines_and_keeps_its_handles() {
        render(readingState(mapOf(0 to page(0))), textPage = multiLineSelectableTextPage())
        val overlay = compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY)
        val baseline = intents.size

        overlay.performTouchInput {
            down(percentOffset(.24f, .40f))
            advanceEventTime(LONG_PRESS_MILLIS)
            moveTo(percentOffset(.42f, .40f))
            advanceEventTime(50)
            moveTo(percentOffset(.61f, .40f))
            advanceEventTime(50)
            moveTo(percentOffset(.24f, .58f))
            advanceEventTime(50)
            moveTo(percentOffset(.42f, .58f))
            up()
        }
        compose.waitForIdle()

        assertTrue(intents.drop(baseline).none { it is GestureIntent.FlingToPage })
        assertTrue(intents.drop(baseline).none { it is GestureIntent.PanBy })

        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY).performClick()
        assertEquals("One two three\nfour five", clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())

        assertHandleNear(ReaderTestTags.SELECTION_ANCHOR, .18f, .44f)
        assertHandleNear(ReaderTestTags.SELECTION_FOCUS, .48f, .62f)
    }

    @Test fun reverse_long_press_drag_follows_only_the_original_pointer() {
        render(readingState(mapOf(0 to page(0))), textPage = multiLineSelectableTextPage())
        val overlay = compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY)
        val baseline = intents.size

        overlay.performTouchInput {
            down(0, percentOffset(.42f, .58f))
            advanceEventTime(LONG_PRESS_MILLIS)
            down(1, percentOffset(.90f, .90f))
            moveTo(0, percentOffset(.61f, .40f))
            advanceEventTime(50)
            moveTo(0, percentOffset(.24f, .40f))
            advanceEventTime(50)
            moveTo(1, percentOffset(.42f, .58f))
            up(0)
            up(1)
        }
        compose.waitForIdle()

        assertTrue(intents.drop(baseline).none { it is GestureIntent.FlingToPage })
        assertTrue(intents.drop(baseline).none { it is GestureIntent.PanBy })
        assertHandleNear(ReaderTestTags.SELECTION_ANCHOR, .48f, .62f)
        assertHandleNear(ReaderTestTags.SELECTION_FOCUS, .18f, .44f)

        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY).performClick()
        assertEquals("One two three\nfour five", clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
    }

    private fun assertHandleNear(tag: String, expectedX: Float, expectedY: Float) {
        val overlayBounds = compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY).fetchSemanticsNode().boundsInRoot
        val handleCenter = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
        val pageWidth = minOf(overlayBounds.width, overlayBounds.height * .6f)
        val pageHeight = pageWidth / .6f
        val expectedViewportX = overlayBounds.left + (overlayBounds.width - pageWidth) / 2f + expectedX * pageWidth
        val expectedViewportY = overlayBounds.top + (overlayBounds.height - pageHeight) / 2f + expectedY * pageHeight

        assertEquals(expectedViewportX, handleCenter.x, 2f)
        assertEquals(expectedViewportY, handleCenter.y, 2f)
    }

    private fun fixturePageRect(
        overlayBounds: androidx.compose.ui.geometry.Rect,
        pageRect: PageSpaceRect
    ): androidx.compose.ui.geometry.Rect {
        val pageWidth = minOf(overlayBounds.width, overlayBounds.height * .6f)
        val pageHeight = pageWidth / .6f
        val originX = overlayBounds.left + (overlayBounds.width - pageWidth) / 2f
        val originY = overlayBounds.top + (overlayBounds.height - pageHeight) / 2f

        return androidx.compose.ui.geometry.Rect(
            left = originX + pageRect.left * pageWidth,
            top = originY + pageRect.top * pageHeight,
            right = originX + pageRect.right * pageWidth,
            bottom = originY + pageRect.bottom * pageHeight
        )
    }

    @Test fun dragging_a_handle_on_a_zoomed_page_never_pans_the_page() {
        render(readingState(mapOf(0 to page(0))), textPage = selectableTextPage())
        compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY).performTouchInput { longClickFirstFixtureWord() }

        val zoomed = HorizontalViewportReducer.reduce(
            shown.value.state,
            GestureIntent.ZoomBy(2f, PageSpacePoint(.25f, .5f))
        )
        update(readingState(mapOf(0 to page(0)), state = zoomed))
        val baseline = intents.size

        compose.onNodeWithTag(ReaderTestTags.SELECTION_FOCUS).performTouchInput {
            swipe(center, center + Offset(300f, 0f), durationMillis = 500)
        }
        compose.waitForIdle()

        assertTrue(intents.drop(baseline).none { it is GestureIntent.PanBy })
        compose.onNodeWithTag(ReaderTestTags.SELECTION_HIGHLIGHT).assertIsDisplayed()
    }

    @Test fun crossing_reanchors_copy_to_the_active_anchor_beyond_the_interior_focus() {
        val observedSelection = AtomicReference(TextSelection(0, 0))
        renderSelectionHarness(
            textPage = selectableTextPage(),
            initialSelection = observedSelection.get(),
            topOcclusionPx = mutableStateOf<Float?>(0f),
            observedSelection = observedSelection
        )
        dragFocusHandleToPageFraction(.42f)
        assertEquals(1, observedSelection.get().focusWord)

        dragAnchorHandleToPageFraction(.815f)
        val crossed = observedSelection.get()
        assertTrue("anchor must cross beyond focus", crossed.anchorWord > crossed.focusWord)
        assertEquals(3, crossed.anchorWord)
        assertEquals(SelectionEndpoint.ANCHOR, crossed.activeEndpoint)

        val anchorCenter = compose.onNodeWithTag(ReaderTestTags.SELECTION_ANCHOR)
            .fetchSemanticsNode().boundsInRoot.center
        val focusCenter = compose.onNodeWithTag(ReaderTestTags.SELECTION_FOCUS)
            .fetchSemanticsNode().boundsInRoot.center
        val copyCenter = compose.onNodeWithTag(ReaderTestTags.SELECTION_COPY)
            .fetchSemanticsNode().boundsInRoot.center
        assertTrue("crossed anchor handle must finish right of focus", anchorCenter.x > focusCenter.x)
        assertTrue(
            "crossing must re-anchor copy to the active anchor endpoint",
            (copyCenter - anchorCenter).getDistance() < (copyCenter - focusCenter).getDistance()
        )
    }

    private fun dragFocusHandleToPageFraction(targetFraction: Float) {
        val overlayBounds = compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY).fetchSemanticsNode().boundsInRoot
        val handleBounds = compose.onNodeWithTag(ReaderTestTags.SELECTION_FOCUS).fetchSemanticsNode().boundsInRoot
        val targetX = overlayBounds.left + overlayBounds.width * targetFraction
        val deltaX = targetX - handleBounds.center.x

        compose.onNodeWithTag(ReaderTestTags.SELECTION_FOCUS).performTouchInput {
            swipe(center, center + Offset(deltaX, 0f), durationMillis = 500)
        }
        compose.waitForIdle()
    }

    private fun dragAnchorHandleToPageFraction(targetFraction: Float) {
        val overlayBounds = compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY).fetchSemanticsNode().boundsInRoot
        val handleBounds = compose.onNodeWithTag(ReaderTestTags.SELECTION_ANCHOR).fetchSemanticsNode().boundsInRoot
        val targetX = overlayBounds.left + overlayBounds.width * targetFraction
        val deltaX = targetX - handleBounds.center.x

        compose.onNodeWithTag(ReaderTestTags.SELECTION_ANCHOR).performTouchInput {
            swipe(center, center + Offset(deltaX, 0f), durationMillis = 500)
        }
        compose.waitForIdle()
    }

    private fun injectTouch(action: Int, downTime: Long, point: Offset) {
        val event = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            point.x,
            point.y,
            0
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }

        try {
            InstrumentationRegistry.getInstrumentation().sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    @Test fun selection_accent_stays_visible_on_black_and_white_pdf_pixels_in_every_appearance() {
        appearanceMode.value = AppearanceMode.LIGHT
        render(readingState(mapOf(0 to splitPage(0))), textPage = wideSelectableTextPage())
        compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY).performTouchInput { longClickFirstFixtureWord() }

        val selectedColours = mutableSetOf<Pair<ComposeColor, ComposeColor>>()
        listOf(
            AppearanceMode.LIGHT,
            AppearanceMode.DARK,
            AppearanceMode.E_INK_LIGHT,
            AppearanceMode.E_INK_DARK
        ).forEach { mode ->
            compose.runOnIdle { appearanceMode.value = mode }
            val pixels = capturePageRetryingTheCopy(0)
            val selectedY = (pixels.height * .5f).roundToInt()
            val outsideY = (pixels.height * .3f).roundToInt()
            val darkX = (pixels.width * .25f).roundToInt()
            val lightX = (pixels.width * .75f).roundToInt()
            val selectedDark = pixels[darkX, selectedY]
            val selectedLight = pixels[lightX, selectedY]

            assertTrue("$mode selection disappeared on black", selectedDark != ComposeColor.Black)
            assertTrue("$mode selection disappeared on white", selectedLight != ComposeColor.White)
            assertTrue("$mode selection on black is not blue", selectedDark.blue > selectedDark.red * 2f)
            assertTrue("$mode selection on white is not blue", selectedLight.blue > selectedLight.red)
            assertEquals("$mode changed black pixels outside selection", ComposeColor.Black, pixels[darkX, outsideY])
            assertEquals("$mode changed white pixels outside selection", ComposeColor.White, pixels[lightX, outsideY])
            selectedColours += selectedDark to selectedLight
        }

        assertEquals("PDF selection colour must not depend on chrome appearance", 1, selectedColours.size)
    }

    @Test fun single_word_selection_stays_compact_and_does_not_cover_nearby_lines() {
        render(readingState(mapOf(0 to page(0))), textPage = selectableTextPage())
        compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY).performTouchInput { longClickFirstFixtureWord() }

        val pixels = capturePageRetryingTheCopy(0)
        val wordCenterY = (pixels.height * .5f).roundToInt()
        val priorLineY = (pixels.height * .35f).roundToInt()

        assertTrue(pixels[(pixels.width * .24f).roundToInt(), wordCenterY] != ComposeColor.Black)
        assertEquals(ComposeColor.Black, pixels[(pixels.width * .15f).roundToInt(), wordCenterY])
        assertEquals(ComposeColor.Black, pixels[(pixels.width * .35f).roundToInt(), wordCenterY])
        assertEquals(ComposeColor.Black, pixels[(pixels.width * .24f).roundToInt(), priorLineY])
    }

    @Test fun selection_draws_one_continuous_band_across_the_gap_between_words() {
        render(readingState(mapOf(0 to page(0))), textPage = selectableTextPage())
        compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY).performTouchInput { longClickFirstFixtureWord() }
        dragFocusHandleToPageFraction(.42f)

        val pixels = capturePageRetryingTheCopy(0)
        val selectedY = (pixels.height * .5f).roundToInt()
        val gapBetweenWordsX = (pixels.width * .33f).roundToInt()

        assertTrue(
            "the word gap remained an unselected seam inside one line",
            pixels[gapBetweenWordsX, selectedY] != ComposeColor.Black
        )
    }

    @Test fun search_menu_opens_field_and_forwards_literal_query() {
        val queries = mutableListOf<String>()
        render(readingState(mapOf(0 to page(0))), textPage = selectableTextPage(), onSearch = { queries += it })
        compose.onNodeWithTag(ReaderTestTags.OVERFLOW).performClick()
        compose.onNodeWithTag(ReaderTestTags.SEARCH).performClick()
        compose.onNodeWithTag(ReaderTestTags.SEARCH_FIELD).performTextInput("word")
        compose.runOnIdle { assertEquals(listOf("word"), queries) }
    }

    @Test fun search_navigation_and_highlights_coexist_with_selection() {
        val first = ReaderSearchMatch(
            ReaderSearchMatchIdentity(0, 0), 0, 0..0,
            listOf(PageSpaceRect(.16f, .45f, .31f, .55f)), "first word"
        )
        val second = ReaderSearchMatch(
            ReaderSearchMatchIdentity(0, 1), 0, 1..1,
            listOf(PageSpaceRect(.36f, .45f, .52f, .55f)), "second word"
        )
        val dynamicSearch = mutableStateOf<ReaderSearchState?>(
            ReaderSearchState(
                "word", listOf(first, second), first.identity,
                ReaderSearchCoverage(1, 0, 5, running = true)
            )
        )
        render(
            readingState(mapOf(0 to page(0))),
            textPage = selectableTextPage(),
            searchState = dynamicSearch,
            onSearchNext = {
                dynamicSearch.value = dynamicSearch.value?.copy(activeIdentity = second.identity)
            }
        )
        compose.onNodeWithText("1 of 2 results").assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.SEARCH_POSITION).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.SEARCH_PREVIOUS).assertIsNotEnabled()
        compose.onNodeWithTag(ReaderTestTags.SEARCH_NEXT).assertIsEnabled()
        compose.onNodeWithTag(ReaderTestTags.SEARCH_COVERAGE).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.SEARCH_HIGHLIGHTS).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.SEARCH_ACTIVE_HIGHLIGHT).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.SEARCH_NEXT).performClick()
        compose.onNodeWithText("2 of 2 results").assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.SEARCH_NEXT).assertIsNotEnabled()
        compose.onNodeWithTag(ReaderTestTags.SEARCH_ACTIVE_HIGHLIGHT).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.SELECTION_OVERLAY).performTouchInput { longClickFirstFixtureWord() }
        compose.onNodeWithTag(ReaderTestTags.SELECTION_HIGHLIGHT).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.SEARCH_HIGHLIGHTS).assertIsDisplayed()
    }

    private fun PixelMap.containsColour(colour: ComposeColor): Boolean {
        for (x in 0 until width) {
            for (y in 0 until height) {
                if (this[x, y] == colour) return true
            }
        }

        return false
    }

    private fun contrastRatio(first: ComposeColor, second: ComposeColor): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())

        return (lighter + .05f) / (darker + .05f)
    }
}
