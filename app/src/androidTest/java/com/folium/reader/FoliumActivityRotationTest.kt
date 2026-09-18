package com.folium.reader

import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folium.reader.reader.ReaderHostTestTags
import com.folium.reader.reader.ReaderTestTags
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the manifest's `configChanges` keeps the reader in place across a rotation: the activity
 * is not recreated, the "Opening…" state is never shown a second time, and the page reached before
 * the rotation is still the page shown after it.
 *
 * This drives a real orientation change through [android.app.Activity.setRequestedOrientation]
 * rather than a device sensor, which is the standard, reliable way to exercise a configuration
 * change from an instrumented test without depending on the test device's rotation lock or a
 * physical orientation.
 */
@RunWith(AndroidJUnit4::class)
class FoliumActivityRotationTest {

    private val activityRule = ActivityScenarioRule<FoliumActivity>(externalViewIntent())

    @get:Rule
    val compose = AndroidComposeTestRule(activityRule) { rule ->
        var activity: FoliumActivity? = null
        rule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @After fun restoreOrientation() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    @Test fun rotating_the_open_reader_keeps_the_same_activity_and_the_same_page() {
        compose.waitUntil(RENDER_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(ReaderTestTags.POSITION).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ReaderHostTestTags.OPENING).assertDoesNotExist()
        val pageIndicator = context.getString(R.string.reader_page_indicator, 1, ExternalDocumentTestProvider.PAGE_COUNT)
        compose.onNodeWithText(pageIndicator).assertExists()
        val beforeRotation = compose.activity

        compose.runOnUiThread {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()

        assertSame("a rotation must not recreate the activity", beforeRotation, compose.activity)
        compose.onNodeWithTag(ReaderHostTestTags.OPENING).assertDoesNotExist()
        compose.onNodeWithText(pageIndicator).assertExists()
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val RENDER_TIMEOUT_MILLIS = 60_000L
    }
}

private fun externalViewIntent() = Intent(Intent.ACTION_VIEW)
    .setDataAndType(ExternalDocumentTestProvider.PDF_URI, "application/pdf")
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
