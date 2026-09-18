package com.folium.reader.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoPageSpreadPreferenceTest {

    @Test fun every_value_round_trips_through_its_stored_form() {
        listOf(true, false).forEach { enabled ->
            assertEquals(enabled, TwoPageSpreadPreferences.decode(TwoPageSpreadPreferences.encode(enabled)))
        }
    }

    @Test fun the_stored_form_is_stable_rather_than_derived_from_the_boolean() {
        assertEquals("on", TwoPageSpreadPreferences.encode(true))
        assertEquals("off", TwoPageSpreadPreferences.encode(false))
    }

    @Test fun a_value_this_version_does_not_know_reads_back_as_the_default() {
        listOf(null, "", "   ", "ON", "true", "1", "on extra").forEach { value ->
            assertEquals(
                "decoded $value as something other than the default",
                TwoPageSpreadPreferences.DEFAULT,
                TwoPageSpreadPreferences.decode(value)
            )
        }
    }

    /** The spread is offered out of the box wherever the page area qualifies for one. */
    @Test fun the_default_is_enabled() {
        assertTrue(TwoPageSpreadPreferences.DEFAULT)
    }

    @Test fun surrounding_whitespace_does_not_lose_the_preference() {
        assertEquals(false, TwoPageSpreadPreferences.decode(" off\t"))
    }
}
