package com.folium.reader.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.R
import com.folium.reader.core.library.DocumentProbeFailure
import com.folium.reader.core.library.DocumentVersion
import com.folium.reader.core.library.LibraryDocumentCandidate
import com.folium.reader.core.library.LibraryState
import com.folium.reader.core.library.ProviderDocumentIdentity
import com.folium.reader.core.library.RecoveryReason
import com.folium.reader.core.library.RecoveryState
import com.folium.reader.ui.FoliumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val report = candidate("root:books/8fa1", "Quarterly report.pdf")
    private val manual = candidate("root:books/2c07", "Field manual.pdf")

    private val opened = mutableListOf<ProviderDocumentIdentity>()
    private var selectRootCalls = 0
    private var retryCalls = 0

    /** State 1 of 7 — no root has ever been chosen. */
    @Test fun first_selection_invites_the_reader_instead_of_reporting_an_error() {
        render(LibraryState.Error(RecoveryState(RecoveryReason.RootNotSelected)))

        compose.onNodeWithTag(LibraryTestTags.FIRST_SELECTION).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_root_title_not_selected)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_root_body_not_selected)).assertIsDisplayed()

        compose.onNodeWithTag(LibraryTestTags.PRIMARY_ACTION).assertIsDisplayed().performClick()
        assertEquals(1, selectRootCalls)
        assertEquals(0, retryCalls)
    }

    /** State 2 of 7 — the root is being enumerated. */
    @Test fun loading_announces_progress_and_shows_no_stale_documents() {
        render(LibraryState.Loading)

        compose.onNodeWithTag(LibraryTestTags.LOADING).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_loading)).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.DOCUMENTS).assertDoesNotExist()
        compose.onNodeWithTag(LibraryTestTags.SKIPPED).assertDoesNotExist()
    }

    /** State 3 of 7 — a readable root that holds no PDFs. */
    @Test fun an_empty_root_explains_itself_and_offers_another_folder() {
        render(LibraryState.Empty(emptyList()))

        compose.onNodeWithTag(LibraryTestTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_empty_body)).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.SKIPPED).assertDoesNotExist()

        compose.onNodeWithTag(LibraryTestTags.CHANGE_ROOT).assertIsDisplayed().performClick()
        assertEquals(1, selectRootCalls)
    }

    /** State 4 of 7 — real documents, dispatched by stable provider identity. */
    @Test fun documents_are_listed_and_open_by_stable_provider_identity() {
        render(LibraryState.Content(listOf(report, manual), emptyList()))

        compose.onNodeWithTag(LibraryTestTags.DOCUMENTS).assertIsDisplayed()
        compose.onNodeWithText(report.displayName).assertIsDisplayed()
        compose.onNodeWithText(manual.displayName).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.SKIPPED).assertDoesNotExist()

        compose.onNodeWithTag(LibraryTestTags.document(manual.identity)).performClick()
        compose.onNodeWithTag(LibraryTestTags.document(report.identity)).performClick()

        assertEquals(listOf(manual.identity, report.identity), opened)
    }

    /** State 5 of 7 — the root itself is inaccessible. */
    @Test fun an_inaccessible_root_names_the_failure_and_offers_the_typed_recovery() {
        render(LibraryState.PermissionLost(RecoveryState(RecoveryReason.PermissionRevoked)))

        compose.onNodeWithTag(LibraryTestTags.RECOVERY).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_root_title_permission_revoked)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_root_body_permission_revoked)).assertIsDisplayed()

        compose.onNodeWithTag(LibraryTestTags.PRIMARY_ACTION).assertIsDisplayed().performClick()
        assertEquals(1, selectRootCalls)
        assertEquals(0, opened.size)
    }

    @Test fun a_transient_root_failure_offers_a_retry_rather_than_a_reselection() {
        render(LibraryState.Error(RecoveryState(RecoveryReason.TransientQueryFailure)))

        compose.onNodeWithTag(LibraryTestTags.RECOVERY).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_root_title_transient)).assertIsDisplayed()

        compose.onNodeWithTag(LibraryTestTags.PRIMARY_ACTION).performClick()
        assertEquals(1, retryCalls)
        assertEquals(0, selectRootCalls)

        compose.onNodeWithTag(LibraryTestTags.CHANGE_ROOT).performClick()
        assertEquals(1, selectRootCalls)
    }

    /** State 6 of 7 — individual documents failed while the root stayed readable. */
    @Test fun per_document_failures_are_visible_alongside_the_documents_that_loaded() {
        val unreadable = DocumentProbeFailure(RecoveryState(RecoveryReason.DocumentUnreadable), manual.identity)
        val malformed = DocumentProbeFailure(RecoveryState(RecoveryReason.MalformedMetadata), null)
        render(LibraryState.Content(listOf(report), listOf(unreadable, malformed)))

        compose.onNodeWithText(report.displayName).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.SKIPPED).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_skipped_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_skipped_count, 2)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_skip_unreadable)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_skip_malformed)).assertIsDisplayed()
        compose.onNodeWithText(manual.identity.documentId).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_skipped_unnamed)).assertIsDisplayed()
    }

    @Test fun a_root_whose_documents_all_failed_reports_them_instead_of_looking_merely_empty() {
        val unreadable = DocumentProbeFailure(RecoveryState(RecoveryReason.DocumentUnreadable), manual.identity)
        render(LibraryState.Empty(listOf(unreadable)))

        compose.onNodeWithTag(LibraryTestTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(LibraryTestTags.SKIPPED).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_skipped_count, 1)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.library_skip_unreadable)).assertIsDisplayed()
    }

    /** State 7 of 7 — reselecting a different root from a populated library. */
    @Test fun reselection_is_reachable_while_documents_are_on_screen() {
        render(LibraryState.Content(listOf(report), emptyList()))

        compose.onNodeWithTag(LibraryTestTags.CHANGE_ROOT).assertIsDisplayed().performClick()

        assertEquals(1, selectRootCalls)
        assertEquals(0, opened.size)
    }

    /**
     * Narrow width: `libraryColumns` reports 1 column, so the two documents must stack into
     * distinct rows sharing the same left edge. `requiredWidth` is used (not `width`) so the
     * declared width is not silently coerced down to the incoming constraints.
     */
    @Test fun documents_render_at_the_narrow_width() {
        assertEquals(1, libraryColumns(360.dp))
        assertEquals(1, libraryColumns(599.dp))
        assertEquals(2, libraryColumns(600.dp))
        assertEquals(3, libraryColumns(1000.dp))

        render(LibraryState.Content(listOf(report, manual), emptyList()), width = 360.dp)
        compose.onNodeWithText(report.displayName).assertIsDisplayed()
        compose.onNodeWithText(manual.displayName).assertIsDisplayed()

        val reportNode = compose.onNodeWithTag(LibraryTestTags.document(report.identity))
        val manualNode = compose.onNodeWithTag(LibraryTestTags.document(manual.identity))
        val reportBounds = reportNode.getUnclippedBoundsInRoot()

        manualNode.assertLeftPositionInRootIsEqualTo(reportBounds.left)
        assertNotSameRow(reportBounds.top, manualNode.getUnclippedBoundsInRoot().top)
    }

    /**
     * Expanded width: `libraryColumns` reports 2 columns, so the two documents must sit side by
     * side in the same row. The test runs at a genuine 840.dp on every target, including phones
     * narrower than that, via `requiredWidth`; the second column may fall outside the physical
     * screen there, so its visibility is not asserted, only its layout position.
     */
    @Test fun documents_render_at_the_expanded_width() {
        render(LibraryState.Content(listOf(report, manual), emptyList()), width = 840.dp)

        compose.onNodeWithTag(LibraryTestTags.DOCUMENTS).assertIsDisplayed()
        compose.onNodeWithText(report.displayName).assertIsDisplayed()

        val reportNode = compose.onNodeWithTag(LibraryTestTags.document(report.identity))
        val manualNode = compose.onNodeWithTag(LibraryTestTags.document(manual.identity))
        val reportBounds = reportNode.getUnclippedBoundsInRoot()

        manualNode.assertTopPositionInRootIsEqualTo(reportBounds.top)
        assertNotSameColumn(reportBounds.left, manualNode.getUnclippedBoundsInRoot().left)

        reportNode.performClick()
        assertEquals(listOf(report.identity), opened)
    }

    private fun assertNotSameRow(a: androidx.compose.ui.unit.Dp, b: androidx.compose.ui.unit.Dp) {
        assertTrue("expected rows at $a and $b to differ", kotlin.math.abs((a - b).value) > 1f)
    }

    private fun assertNotSameColumn(a: androidx.compose.ui.unit.Dp, b: androidx.compose.ui.unit.Dp) {
        assertTrue("expected columns at $a and $b to differ", kotlin.math.abs((a - b).value) > 1f)
    }

    private fun render(state: LibraryState, width: androidx.compose.ui.unit.Dp? = null) {
        compose.setContent {
            FoliumTheme {
                val content: @androidx.compose.runtime.Composable () -> Unit = {
                    LibraryScreen(
                        state = state,
                        onSelectRoot = { selectRootCalls++ },
                        onRetry = { retryCalls++ },
                        onOpenDocument = { opened += it.identity }
                    )
                }
                if (width == null) content() else Box(Modifier.requiredWidth(width)) { content() }
            }
        }
    }

    private fun string(id: Int): String = context.getString(id)

    private fun candidate(documentId: String, name: String) = LibraryDocumentCandidate(
        ProviderDocumentIdentity("com.folium.reader.debug.documents", documentId),
        DocumentVersion("1"),
        name,
        "application/pdf",
        true
    )
}
