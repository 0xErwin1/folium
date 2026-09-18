package com.folium.reader.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.R
import com.folium.reader.core.ocr.OcrPageState
import com.folium.reader.core.ocr.OcrPageStatus
import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.HorizontalViewportState
import com.folium.reader.core.pdf.PageCacheKey
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.text.TextBlock
import com.folium.reader.core.text.TextLine
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSearchSpec
import com.folium.reader.core.text.TextSource
import com.folium.reader.core.text.TextWord
import com.folium.reader.ui.FoliumTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The facing-page spread [ReaderScreen] shows in a wide landscape window: two slots instead of one,
 * a per-slot page number, the position bar reading as a range, the overflow toggle that turns it on
 * and off, and per-slot text selection, search highlights and OCR status. No device is attached in
 * this environment, so these are compiled but not executed — see the worker's own report.
 */
@RunWith(AndroidJUnit4::class)
class ReaderSpreadScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cache = ByteBoundedPageCache<RenderedPage>(16L * 1024 * 1024)
    private val borrows = mutableListOf<BorrowedPage>()

    @After fun releaseBorrows() {
        borrows.forEach { it.release() }
        cache.clear()
    }

    private fun page(pageIndex: Int, colour: Int = Color.BLACK): BorrowedPage {
        val bitmap = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(colour)
        val region = PageSpaceRect(0f, 0f, 1f, 1f)
        val key = PageCacheKey("spread-fixture", pageIndex, 0L, RenderSpec(120, 200, region))
        cache.put(key, RenderCandidate(RenderedPage(bitmap, region)) {}, bitmap.allocationByteCount.toLong())
        return BorrowedPage.Cached(requireNotNull(cache.acquire(key))).also { borrows += it }
    }

    private fun selectableTextPage() = TextPage(
        listOf(TextBlock(listOf(TextLine(listOf(
            TextWord("One", PageSpaceRect(.18f, .45f, .3f, .55f), 0),
            TextWord("two", PageSpaceRect(.36f, .45f, .48f, .55f), 1)
        ), 0)), 0)),
        TextSource.NATIVE_PDF
    )

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    /** A wide, landscape page area — well past `FoliumWidthClass.EXPANDED_FROM` — qualifies for a spread. */
    private fun renderSpread(
        pageCount: Int = 10,
        pagesPerView: Int = 2,
        currentPage: Int = 0,
        pages: Map<Int, BorrowedPage> = emptyMap(),
        textPage: TextPage? = null,
        textPages: Map<Int, ReaderTextState> = emptyMap(),
        ocr: ReaderOcrState? = null,
        ocrPages: Map<Int, ReaderOcrState> = emptyMap(),
        search: ReaderSearchState? = null,
        spread: ReaderSpreadState = ReaderSpreadState(windowQualifies = true, twoPageSpreadEnabled = true, effectivePagesPerView = 2),
        onSpreadToggle: (Boolean) -> Unit = {}
    ) {
        val state = ReaderUiState(
            state = HorizontalViewportState.initial(pageCount, currentPage, pagesPerView),
            pages = pages
        )
        compose.setContent {
            FoliumTheme {
                Box(androidx.compose.ui.Modifier.requiredSize(1200.dp, 700.dp)) {
                    ReaderScreen(
                        title = "Field manual.pdf",
                        state = state,
                        pageAspect = { 0.6f },
                        onIntent = {},
                        onViewportChanged = {},
                        onBack = {},
                        textPage = textPage,
                        textPages = textPages,
                        ocr = ocr,
                        ocrPages = ocrPages,
                        search = search,
                        spread = spread,
                        onSpreadToggle = onSpreadToggle
                    )
                }
            }
        }
    }

    @Test fun a_qualifying_window_shows_two_slots_and_a_narrow_one_shows_a_single_page() {
        renderSpread(pages = mapOf(0 to page(0), 1 to page(1)))

        compose.onNodeWithTag(ReaderTestTags.page(0)).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.page(1)).assertIsDisplayed()
    }

    @Test fun each_slot_carries_its_own_one_based_page_number() {
        renderSpread(pages = mapOf(0 to page(0), 1 to page(1)))

        compose.onNodeWithTag(ReaderTestTags.pageNumberCaption(0)).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.pageNumberCaption(1)).assertIsDisplayed()
    }

    @Test fun an_odd_page_count_leaves_the_last_page_alone_with_no_right_slot() {
        renderSpread(pageCount = 9, currentPage = 8, pages = mapOf(8 to page(8)))

        compose.onNodeWithTag(ReaderTestTags.page(8)).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.page(9)).assertDoesNotExist()
    }

    @Test fun the_position_bar_reads_as_a_range_and_speaks_it_too() {
        renderSpread(pageCount = 615, currentPage = 18, pages = mapOf(18 to page(18), 19 to page(19)))

        compose.onNodeWithText(string(R.string.reader_page_indicator_spread, 19, 20, 615)).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.POSITION).assertContentDescriptionEquals(
            context.resources.getQuantityString(R.plurals.reader_page_position_spread, 2, 19, 20, 615)
        )
    }

    @Test fun the_toggle_only_appears_once_the_window_qualifies_and_carries_its_state_in_semantics() {
        renderSpread(
            pages = mapOf(0 to page(0), 1 to page(1)),
            spread = ReaderSpreadState(windowQualifies = false, twoPageSpreadEnabled = true, effectivePagesPerView = 1)
        )
        compose.onNodeWithTag(ReaderTestTags.OVERFLOW).performClick()
        compose.onNodeWithTag(ReaderTestTags.TWO_PAGES).assertDoesNotExist()
    }

    @Test fun the_toggle_appears_and_reflects_the_stored_preference_when_the_window_qualifies() {
        var toggled: Boolean? = null
        renderSpread(
            pages = mapOf(0 to page(0), 1 to page(1)),
            spread = ReaderSpreadState(windowQualifies = true, twoPageSpreadEnabled = true, effectivePagesPerView = 2),
            onSpreadToggle = { toggled = it }
        )
        compose.onNodeWithTag(ReaderTestTags.OVERFLOW).performClick()
        compose.onNodeWithTag(ReaderTestTags.TWO_PAGES).assertIsSelected().performClick()
        assert(toggled == false)
    }

    @Test fun search_highlights_and_ocr_status_reach_the_right_slot() {
        val match = ReaderSearchMatch(
            identity = ReaderSearchMatchIdentity(1, 0),
            pageIndex = 1,
            wordRange = 0..0,
            boxes = listOf(PageSpaceRect(.1f, .1f, .2f, .2f)),
            snippet = "two"
        )
        val search = ReaderSearchState(TextSearchSpec("two")).copy(matches = listOf(match), activeIdentity = match.identity)
        val rightOcr = ReaderOcrState(1, OcrPageStatus(OcrPageState.QUEUED, 1))

        renderSpread(
            pages = mapOf(0 to page(0), 1 to page(1)),
            textPage = selectableTextPage(),
            textPages = mapOf(1 to ReaderTextState.Loaded(1, selectableTextPage())),
            ocrPages = mapOf(1 to rightOcr),
            search = search
        )

        compose.onNodeWithTag(ReaderTestTags.page(1)).onChildren().assertAny(hasTestTag(ReaderTestTags.SEARCH_HIGHLIGHTS))
        compose.onNodeWithTag(ReaderTestTags.ocrStatus(1)).assertIsDisplayed()
    }

    /**
     * A single [PageTextSelection] backs both slots, so starting one on the right page's own overlay
     * is what proves it replaces whatever was selected on the left rather than the two coexisting.
     */
    @Test fun starting_a_selection_on_the_right_page_replaces_one_on_the_left() {
        renderSpread(
            pages = mapOf(0 to page(0), 1 to page(1)),
            textPage = selectableTextPage(),
            textPages = mapOf(1 to ReaderTextState.Loaded(1, selectableTextPage()))
        )

        compose.onNodeWithTag(ReaderTestTags.page(0)).onChildren()
            .assertAny(hasTestTag(ReaderTestTags.SELECTION_OVERLAY))

        compose.onNodeWithTag(ReaderTestTags.page(1)).performTouchInput { longClick(percentOffset(.24f, .5f)) }

        compose.onNodeWithTag(ReaderTestTags.page(1)).onChildren()
            .assertAny(hasTestTag(ReaderTestTags.SELECTION_HIGHLIGHT))
        compose.onNodeWithTag(ReaderTestTags.page(0)).onChildren()
            .assertAll(!hasTestTag(ReaderTestTags.SELECTION_HIGHLIGHT))
    }
}
