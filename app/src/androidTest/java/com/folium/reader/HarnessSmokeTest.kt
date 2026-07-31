package com.folium.reader

import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HarnessSmokeTest {
    @get:Rule val scenarioRule = ActivityScenarioRule(HarnessActivity::class.java)

    @Test fun launchesHarnessActivity() {
        assertEquals(Lifecycle.State.RESUMED, scenarioRule.scenario.state)
        scenarioRule.scenario.onActivity { activity ->
            assertFalse(activity.isFinishing)
        }
    }
}
