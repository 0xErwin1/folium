package com.folium.reader.core.library

/** How Folium chooses among its neutral screen palettes. */
enum class AppearanceMode { SYSTEM, LIGHT, DARK, E_INK_LIGHT, E_INK_DARK }

const val APPEARANCE_MODE_VERSION_MARKER = "folium-appearance 1"

/** The stable, versioned stored form of [AppearanceMode]. */
object AppearanceModes {
    val DEFAULT = AppearanceMode.SYSTEM

    fun encode(mode: AppearanceMode): String = when (mode) {
        AppearanceMode.SYSTEM -> SYSTEM_VALUE
        AppearanceMode.LIGHT -> LIGHT_VALUE
        AppearanceMode.DARK -> DARK_VALUE
        AppearanceMode.E_INK_LIGHT -> E_INK_LIGHT_VALUE
        AppearanceMode.E_INK_DARK -> E_INK_DARK_VALUE
    }

    fun decode(value: String?): AppearanceMode = when (value?.trim()) {
        SYSTEM_VALUE -> AppearanceMode.SYSTEM
        LIGHT_VALUE -> AppearanceMode.LIGHT
        DARK_VALUE -> AppearanceMode.DARK
        E_INK_LIGHT_VALUE -> AppearanceMode.E_INK_LIGHT
        E_INK_DARK_VALUE -> AppearanceMode.E_INK_DARK
        else -> DEFAULT
    }

    private const val SYSTEM_VALUE = "system"
    private const val LIGHT_VALUE = "light"
    private const val DARK_VALUE = "dark"
    private const val E_INK_LIGHT_VALUE = "e-ink"
    private const val E_INK_DARK_VALUE = "e-ink-dark"
}
