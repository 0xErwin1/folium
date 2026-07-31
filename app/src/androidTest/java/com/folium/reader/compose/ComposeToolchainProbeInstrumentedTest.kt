package com.folium.reader.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeToolchainProbeInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComposeToolchainProbeActivity>()

    @Test
    fun rendersTheToolchainProbeText() {
        composeRule.onNodeWithContentDescription(COMPOSE_TOOLCHAIN_PROBE_TAG).assertIsDisplayed()
    }
}
