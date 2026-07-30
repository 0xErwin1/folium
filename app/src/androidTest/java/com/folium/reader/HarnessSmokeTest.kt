package com.folium.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ActivityTestRule
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HarnessSmokeTest {
    @get:Rule val activityRule = ActivityTestRule(HarnessActivity::class.java)

    @Test fun launchesHarnessActivity() = assertNotNull(activityRule.activity)
}
