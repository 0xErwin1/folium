package com.folium.reader.core.library

const val TWO_PAGE_SPREAD_VERSION_MARKER = "folium-two-page-spread 1"

/**
 * The stored form of whether the reader should offer a facing-page spread. This preference is
 * app-wide, not per-book: it only ever decides whether a spread is *offered* when the page area
 * qualifies for one, never whether a given book supports it.
 */
object TwoPageSpreadPreferences {
    /** A spread is offered by default whenever the page area qualifies for one. */
    const val DEFAULT = true

    fun encode(enabled: Boolean): String = if (enabled) ENABLED_VALUE else DISABLED_VALUE

    fun decode(value: String?): Boolean = when (value?.trim()) {
        ENABLED_VALUE -> true
        DISABLED_VALUE -> false
        else -> DEFAULT
    }

    private const val ENABLED_VALUE = "on"
    private const val DISABLED_VALUE = "off"
}
