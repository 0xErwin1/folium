package com.folium.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessUnitSmokeTest {
    @Test fun applicationIdIsStable() = assertEquals("com.folium.reader", BuildConfig.APPLICATION_ID)
}
