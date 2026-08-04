package com.folium.reader.core.library

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceModeTest {

    @Test fun every_mode_round_trips_through_its_stored_form() {
        AppearanceMode.values().forEach { mode ->
            assertEquals(mode, AppearanceModes.decode(AppearanceModes.encode(mode)))
        }
    }

    @Test fun the_stored_form_is_stable_rather_than_derived_from_the_enum() {
        assertEquals("system", AppearanceModes.encode(AppearanceMode.SYSTEM))
        assertEquals("light", AppearanceModes.encode(AppearanceMode.LIGHT))
        assertEquals("dark", AppearanceModes.encode(AppearanceMode.DARK))
        assertEquals("e-ink", AppearanceModes.encode(AppearanceMode.E_INK_LIGHT))
        assertEquals("e-ink-dark", AppearanceModes.encode(AppearanceMode.E_INK_DARK))
    }

    @Test fun the_build_74_e_ink_value_reads_as_e_ink_light() {
        assertEquals(AppearanceMode.E_INK_LIGHT, AppearanceModes.decode("e-ink"))
    }

    @Test fun a_value_this_version_does_not_know_reads_back_as_the_default() {
        listOf(null, "", "   ", "DARK", "auto", "dark extra", "0").forEach { value ->
            assertEquals(AppearanceModes.DEFAULT, AppearanceModes.decode(value))
        }
    }

    @Test fun the_default_follows_the_system() {
        assertEquals(AppearanceMode.SYSTEM, AppearanceModes.DEFAULT)
    }
}
