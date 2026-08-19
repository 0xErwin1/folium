package com.folium.reader.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import com.folium.reader.core.library.AppearanceMode
import com.folium.reader.core.library.AppearanceModes

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
/**
 * What a page that has not arrived is drawn as.
 *
 * Deliberately outside every palette. The reader leaves document pixels alone, so the sheet standing
 * in for a document is not chrome and must not follow the theme: drawn in a dark palette's own
 * surface it landed a shade away from the page area behind it, and scrubbing through a book showed
 * an empty rectangle where it should have shown pages going by. Paper is paper on either side of a
 * light switch, and the raster that replaces this a moment later is paper too.
 */
val FoliumPaper = Color(0xFFFAFAF6)

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

/** Neutral e-paper chrome with restrained color accents; document pixels remain unchanged. */
private val EInkLightScheme = lightColorScheme(
    primary = Color(0xFF171816),
    onPrimary = Color(0xFFFAFAF6),
    primaryContainer = Color(0xFFD6D8D0),
    onPrimaryContainer = Color(0xFF171816),
    inversePrimary = Color(0xFFE5E6DF),
    secondary = Color(0xFF484A45),
    onSecondary = Color(0xFFFAFAF6),
    secondaryContainer = Color(0xFFD8DAD2),
    onSecondaryContainer = Color(0xFF171816),
    tertiary = Color(0xFF806200),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEFE5C5),
    onTertiaryContainer = Color(0xFF302400),
    background = Color(0xFFF4F4EF),
    onBackground = Color(0xFF171816),
    surface = Color(0xFFF4F4EF),
    onSurface = Color(0xFF171816),
    surfaceVariant = Color(0xFFE8E8E1),
    onSurfaceVariant = Color(0xFF444640),
    surfaceTint = Color(0xFF806200),
    inverseSurface = Color(0xFF2B2D29),
    inverseOnSurface = Color(0xFFF4F4EF),
    outline = Color(0xFF61645D),
    outlineVariant = Color(0xFFB5B8AF),
    scrim = Color(0xFF000000),
    error = Color(0xFF702020),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF0DEDC),
    onErrorContainer = Color(0xFF461010),
    surfaceDim = Color(0xFFDDDED7),
    surfaceBright = Color(0xFFFAFAF6),
    surfaceContainerLowest = Color(0xFFFAFAF6),
    surfaceContainerLow = Color(0xFFF0F0EA),
    surfaceContainer = Color(0xFFEBEBE4),
    surfaceContainerHigh = Color(0xFFE5E6DF),
    surfaceContainerHighest = Color(0xFFDEE0D8)
)

/** Charcoal e-paper chrome with differentiated neutral surfaces and warm high-contrast text. */
private val EInkDarkScheme = darkColorScheme(
    primary = Color(0xFFF3F2E8),
    onPrimary = Color(0xFF171816),
    primaryContainer = Color(0xFF444740),
    onPrimaryContainer = Color(0xFFFAFAF2),
    inversePrimary = Color(0xFF4F514A),
    secondary = Color(0xFFC9CBC2),
    onSecondary = Color(0xFF23241F),
    secondaryContainer = Color(0xFF373934),
    onSecondaryContainer = Color(0xFFF2F1E8),
    tertiary = Color(0xFFE0BC52),
    onTertiary = Color(0xFF292000),
    tertiaryContainer = Color(0xFF443A1C),
    onTertiaryContainer = Color(0xFFF6E6B3),
    background = Color(0xFF171816),
    onBackground = Color(0xFFF3F2E8),
    surface = Color(0xFF171816),
    onSurface = Color(0xFFF3F2E8),
    surfaceVariant = Color(0xFF30322E),
    onSurfaceVariant = Color(0xFFD2D3CA),
    surfaceTint = Color(0xFFE0BC52),
    inverseSurface = Color(0xFFECECE4),
    inverseOnSurface = Color(0xFF2B2D29),
    outline = Color(0xFFA2A59B),
    outlineVariant = Color(0xFF4A4C46),
    scrim = Color(0xFF0B0C0A),
    error = Color(0xFFF0A8A8),
    onError = Color(0xFF3B090B),
    errorContainer = Color(0xFF4A1D20),
    onErrorContainer = Color(0xFFFFDAD9),
    surfaceDim = Color(0xFF11120F),
    surfaceBright = Color(0xFF3B3D38),
    surfaceContainerLowest = Color(0xFF0E0F0D),
    surfaceContainerLow = Color(0xFF1B1C19),
    surfaceContainer = Color(0xFF20211E),
    surfaceContainerHigh = Color(0xFF282A26),
    surfaceContainerHighest = Color(0xFF32342F)
)

@Composable
fun FoliumTheme(
    appearanceMode: AppearanceMode = AppearanceModes.DEFAULT,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val colorScheme = resolveColorScheme(appearanceMode, systemDark)
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = colorScheme.background.toArgb()
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = colorScheme.background.toArgb()
                }

                WindowInsetsControllerCompat(window, view).apply {
                    val useDarkIcons = usesDarkSystemBarIcons(appearanceMode, systemDark)
                    isAppearanceLightStatusBars = useDarkIcons
                    isAppearanceLightNavigationBars = useDarkIcons
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FoliumTypography,
        shapes = FoliumShapes,
        content = content
    )
}

/** Resolves the chosen appearance without consulting platform state or dynamic color APIs. */
internal fun resolveColorScheme(mode: AppearanceMode, systemDark: Boolean) = when (mode) {
    AppearanceMode.SYSTEM -> if (systemDark) DarkScheme else LightScheme
    AppearanceMode.LIGHT -> LightScheme
    AppearanceMode.DARK -> DarkScheme
    AppearanceMode.E_INK_LIGHT -> EInkLightScheme
    AppearanceMode.E_INK_DARK -> EInkDarkScheme
}

internal fun usesDarkSystemBarIcons(mode: AppearanceMode, systemDark: Boolean): Boolean = when (mode) {
    AppearanceMode.SYSTEM -> !systemDark
    AppearanceMode.LIGHT, AppearanceMode.E_INK_LIGHT -> true
    AppearanceMode.DARK, AppearanceMode.E_INK_DARK -> false
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
