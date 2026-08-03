package com.folium.reader.core.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryViewModeTest {

    @Test fun every_mode_round_trips_through_its_stored_form() {
        LibraryViewMode.values().forEach { mode ->
            assertEquals(mode, LibraryViewModes.decode(LibraryViewModes.encode(mode)))
        }
    }

    @Test fun the_stored_form_is_stable_rather_than_derived_from_the_enum() {
        assertEquals("list", LibraryViewModes.encode(LibraryViewMode.LIST))
        assertEquals("grid", LibraryViewModes.encode(LibraryViewMode.GRID))
    }

    @Test fun a_value_this_version_does_not_know_reads_back_as_the_default() {
        listOf(null, "", "   ", "GRID", "tiles", "grid extra", "0").forEach { value ->
            assertEquals(
                "decoded $value as something other than the default",
                LibraryViewModes.DEFAULT,
                LibraryViewModes.decode(value)
            )
        }
    }

    @Test fun the_default_is_the_list() {
        assertEquals(LibraryViewMode.LIST, LibraryViewModes.DEFAULT)
    }

    @Test fun surrounding_whitespace_does_not_lose_the_preference() {
        assertEquals(LibraryViewMode.GRID, LibraryViewModes.decode(" grid\t"))
    }
}
