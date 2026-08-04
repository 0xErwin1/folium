package com.folium.reader.core.library

/** How Folium chooses between its existing light and dark neutral palettes. */
enum class AppearanceMode { SYSTEM, LIGHT, DARK }

const val APPEARANCE_MODE_VERSION_MARKER = "folium-appearance 1"

/** The stable, versioned stored form of [AppearanceMode]. */
object AppearanceModes {
    val DEFAULT = AppearanceMode.SYSTEM

    fun encode(mode: AppearanceMode): String = when (mode) {
        AppearanceMode.SYSTEM -> SYSTEM_VALUE
        AppearanceMode.LIGHT -> LIGHT_VALUE
        AppearanceMode.DARK -> DARK_VALUE
    }

    fun decode(value: String?): AppearanceMode = when (value?.trim()) {
        SYSTEM_VALUE -> AppearanceMode.SYSTEM
        LIGHT_VALUE -> AppearanceMode.LIGHT
        DARK_VALUE -> AppearanceMode.DARK
        else -> DEFAULT
    }

    private const val SYSTEM_VALUE = "system"
    private const val LIGHT_VALUE = "light"
    private const val DARK_VALUE = "dark"
}
