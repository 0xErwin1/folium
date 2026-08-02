package com.folium.reader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * A deliberately neutral, high-contrast palette.
 *
 * Folium targets e-ink as well as backlit screens, so the scheme avoids tinted surfaces and
 * relies on outlines rather than elevation to separate content.
 *
 * The tertiary role is the one exception: a muted gold echoing the app's own mark, reserved for
 * the few places worth accenting — the wordmark, and a book already under way. Kept out of every
 * structural role so a greyscale panel loses an accent rather than a distinction.
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF1A1A1A),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF3D3D3D),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF8A6A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF7EDD2),
    onTertiaryContainer = Color(0xFF3B2D00),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF101010),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101010),
    surfaceVariant = Color(0xFFF1F1F1),
    onSurfaceVariant = Color(0xFF383838),
    outline = Color(0xFF5E5E5E),
    outlineVariant = Color(0xFFBFBFBF),
    error = Color(0xFF7A1414),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF3E9E9),
    onErrorContainer = Color(0xFF4A0D0D)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFEDEDED),
    onPrimary = Color(0xFF101010),
    secondary = Color(0xFFC8C8C8),
    onSecondary = Color(0xFF101010),
    tertiary = Color(0xFFE3C063),
    onTertiary = Color(0xFF241A00),
    tertiaryContainer = Color(0xFF2A2415),
    onTertiaryContainer = Color(0xFFF4E3B6),
    background = Color(0xFF0B0B0B),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF0B0B0B),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFD0D0D0),
    outline = Color(0xFF9A9A9A),
    outlineVariant = Color(0xFF3A3A3A),
    error = Color(0xFFF0B4B4),
    onError = Color(0xFF3A0909),
    errorContainer = Color(0xFF241414),
    onErrorContainer = Color(0xFFF4DADA)
)

@Composable
fun FoliumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content
    )
}
