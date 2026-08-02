package com.folium.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.core.pdf.ByteBoundedPageCache
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.HorizontalViewportReducer
import com.folium.reader.core.pdf.HorizontalViewportState
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageCacheKey
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.RenderCandidate
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.reader.BorrowedPage
import com.folium.reader.reader.ReaderScreen
import com.folium.reader.reader.ReaderTestTags
import com.folium.reader.reader.ReaderUiState
import com.folium.reader.reader.RenderedPage
import com.folium.reader.ui.FoliumTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the jump dialog and the Contents sheet on a real screen, closing the gap E2's verify report
 * flagged: every decision left inside a composable (dismissal, Contents visibility, unresolvable
 * rows, indent capping) compiled but was never exercised by a host test. This is that exercise,
 * always with a non-empty outline so the "Contents is present" half of the visibility contract has
 * a witness, not only the absent half the pre-existing harness could reach.
 */
@RunWith(AndroidJUnit4::class)
class ReaderJumpAndContentsTest {

    private companion object {
        const val PAGE_COUNT = 120
        const val TARGET_PAGE_ENTRY = "45"
        const val TARGET_PAGE_INDEX = 44
    }

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cache = ByteBoundedPageCache<RenderedPage>(16L * 1024 * 1024)
    private val borrows = mutableListOf<BorrowedPage>()
    private val intents = mutableListOf<GestureIntent>()

    @After fun releaseBorrows() {
        borrows.forEach { it.release() }
        cache.clear()
    }

    private fun page(pageIndex: Int): BorrowedPage {
        val bitmap = Bitmap.createBitmap(120, 200, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.BLACK)

        val region = PageSpaceRect(0f, 0f, 1f, 1f)
        val key = PageCacheKey("fixture", pageIndex, 0L, RenderSpec(120, 200, region))
        cache.put(key, RenderCandidate(RenderedPage(bitmap, region)) {}, bitmap.allocationByteCount.toLong())

        return BorrowedPage.Cached(requireNotNull(cache.acquire(key))).also { borrows += it }
    }

    private val shown = mutableStateOf(
        ReaderUiState<BorrowedPage>(HorizontalViewportState.initial(pageCount = PAGE_COUNT))
    )

    private fun render(pages: Map<Int, BorrowedPage>, outline: List<OutlineEntry> = emptyList()) {
        shown.value = ReaderUiState(state = HorizontalViewportState.initial(PAGE_COUNT), pages = pages)
        compose.setContent {
            FoliumTheme {
                ReaderScreen(
                    title = "Field manual.pdf",
                    state = shown.value,
                    pageAspect = { 0.6f },
                    onIntent = { record(it) },
                    onViewportChanged = {},
                    onBack = {},
                    outline = outline
                )
            }
        }
    }

    private fun record(intent: GestureIntent) {
        intents += intent
        shown.value = shown.value.copy(state = HorizontalViewportReducer.reduce(shown.value.state, intent))
    }

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    /** The left edge of a Contents row's title text, which is where the indent actually lands — the row's
     *  own bounds fill the width regardless of how deep it is nested. */
    private fun titleLeft(index: Int): Float =
        compose.onNodeWithTag(ReaderTestTags.contentsRow(index), useUnmergedTree = true)
            .onChildren()
            .onFirst()
            .fetchSemanticsNode()
            .boundsInRoot.left

    /** A resolvable outline entry nested [depth] levels deep, wrapping a leaf that names [pageIndex]. */
    private fun nestedEntry(depth: Int, pageIndex: Int?): OutlineEntry {
        var node = OutlineEntry("Leaf", pageIndex)
        repeat(depth) { level -> node = OutlineEntry("Level ${depth - level - 1}", null, listOf(node)) }
        return node
    }

    @Test fun tapping_the_position_opens_the_jump_dialog_and_a_valid_entry_moves_to_that_page() {
        render(mapOf(0 to page(0), TARGET_PAGE_INDEX to page(TARGET_PAGE_INDEX)))

        compose.onNodeWithTag(ReaderTestTags.POSITION).performClick()
        compose.onNodeWithTag(ReaderTestTags.JUMP_DIALOG).assertIsDisplayed()

        compose.onNodeWithTag(ReaderTestTags.JUMP_INPUT).performTextInput(TARGET_PAGE_ENTRY)
        compose.onNodeWithTag(ReaderTestTags.JUMP_CONFIRM).performClick()

        compose.onNodeWithTag(ReaderTestTags.JUMP_DIALOG).assertDoesNotExist()
        assertTrue(GestureIntent.FlingToPage(TARGET_PAGE_INDEX) in intents)
        compose.onNodeWithTag(ReaderTestTags.pageContent(TARGET_PAGE_INDEX)).assertIsDisplayed()
        compose.onNodeWithTag(ReaderTestTags.POSITION).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.reader_page_indicator, TARGET_PAGE_INDEX + 1, PAGE_COUNT))
            .assertIsDisplayed()
    }

    @Test fun dismissing_the_jump_dialog_leaves_the_page_unchanged() {
        render(mapOf(0 to page(0)))
        compose.waitForIdle()
        val baseline = intents.size

        compose.onNodeWithTag(ReaderTestTags.POSITION).performClick()
        compose.onNodeWithTag(ReaderTestTags.JUMP_INPUT).performTextInput(TARGET_PAGE_ENTRY)
        compose.onNodeWithText(string(R.string.reader_jump_cancel)).performClick()

        compose.onNodeWithTag(ReaderTestTags.JUMP_DIALOG).assertDoesNotExist()
        assertTrue(
            "dismissal must not navigate",
            intents.drop(baseline).none { it is GestureIntent.FlingToPage }
        )
        assertEquals(0, shown.value.state.currentPage)
        compose.onNodeWithTag(ReaderTestTags.pageContent(0)).assertIsDisplayed()
    }

    @Test fun the_contents_item_is_absent_for_a_document_with_no_outline() {
        render(mapOf(0 to page(0)), outline = emptyList())

        compose.onNodeWithTag(ReaderTestTags.OVERFLOW).performClick()
        compose.onNodeWithTag(ReaderTestTags.CONTENTS).assertDoesNotExist()
    }

    @Test fun the_contents_item_opens_the_sheet_for_a_document_that_has_an_outline() {
        val outline = listOf(OutlineEntry("Chapter 1", 0), OutlineEntry("Chapter 2", 9))
        render(mapOf(0 to page(0), 9 to page(9)), outline = outline)

        compose.onNodeWithTag(ReaderTestTags.OVERFLOW).performClick()
        compose.onNodeWithTag(ReaderTestTags.CONTENTS).assertIsDisplayed().performClick()
        compose.onNodeWithTag(ReaderTestTags.CONTENTS_SHEET).assertIsDisplayed()
    }

    @Test fun selecting_a_contents_entry_jumps_to_its_page_and_closes_the_sheet() {
        val outline = listOf(OutlineEntry("Chapter 1", 0), OutlineEntry("Chapter 2", 9))
        render(mapOf(0 to page(0), 9 to page(9)), outline = outline)

        compose.onNodeWithTag(ReaderTestTags.OVERFLOW).performClick()
        compose.onNodeWithTag(ReaderTestTags.CONTENTS).performClick()
        compose.onNodeWithTag(ReaderTestTags.contentsRow(1)).performClick()

        compose.onNodeWithTag(ReaderTestTags.CONTENTS_SHEET).assertDoesNotExist()
        assertTrue(GestureIntent.FlingToPage(9) in intents)
        compose.onNodeWithTag(ReaderTestTags.pageContent(9)).assertIsDisplayed()
    }

    @Test fun an_entry_the_document_could_not_resolve_names_no_page_when_tapped() {
        val outline = listOf(OutlineEntry("Part I", null, listOf(OutlineEntry("Chapter 1", 0))))
        render(mapOf(0 to page(0)), outline = outline)

        compose.onNodeWithTag(ReaderTestTags.OVERFLOW).performClick()
        compose.onNodeWithTag(ReaderTestTags.CONTENTS).performClick()
        compose.waitForIdle()
        val baseline = intents.size

        compose.onNodeWithTag(ReaderTestTags.contentsRow(0)).assertIsDisplayed().performClick()
        compose.onNodeWithTag(ReaderTestTags.CONTENTS_SHEET).assertIsDisplayed()
        assertTrue(
            "an unresolved row must never dispatch a navigation",
            intents.drop(baseline).none { it is GestureIntent.FlingToPage }
        )

        compose.onNodeWithTag(ReaderTestTags.contentsRow(1)).assertIsDisplayed().performClick()
        assertTrue(GestureIntent.FlingToPage(0) in intents)
    }

    @Test fun a_row_nested_past_the_indent_cap_keeps_the_same_indent_as_the_row_at_the_cap() {
        val outline = listOf(nestedEntry(depth = 6, pageIndex = 0))
        render(mapOf(0 to page(0)), outline = outline)

        compose.onNodeWithTag(ReaderTestTags.OVERFLOW).performClick()
        compose.onNodeWithTag(ReaderTestTags.CONTENTS).performClick()

        val shallow = titleLeft(0)
        val atCap = titleLeft(4)
        val pastCap = titleLeft(6)

        assertTrue("depth beyond the cap must not indent further than the row at the cap", pastCap == atCap)
        assertTrue("the shallowest row must indent less than a capped one", shallow < atCap)
    }
}
