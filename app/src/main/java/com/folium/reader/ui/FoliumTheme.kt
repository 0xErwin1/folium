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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import com.folium.reader.core.library.AppearanceMode
import com.folium.reader.core.library.AppearanceModes
import com.folium.reader.core.library.isEInk
import com.folium.reader.core.pdf.ReflowPageBackground
import com.folium.reader.core.pdf.ReflowPageColors
import java.util.Locale

/**
 * Whether the active appearance mode is one of the e-ink families, readable anywhere under
 * [FoliumTheme] the same way [MaterialTheme]'s own color scheme is — without threading a parameter
 * by hand through every composable between the screen that knows the mode and the one that needs it.
 *
 * Defaults to `false` outside of [FoliumTheme] (previews, unit tests composing a bare composable)
 * rather than failing, since a backlit-style default is the safer one to fall back to.
 */
val LocalFoliumEInk = staticCompositionLocalOf { false }

/**
 * The design's "line" colour: the hairline a tool rail draws around itself and between its groups,
 * lighter than [androidx.compose.material3.ColorScheme.outlineVariant] on e-ink, where that role is
 * held to a 3:1 floor because it is the only mark separating a card or a divider. A line is drawn
 * beside ink it frames, never alone, so it keeps the design's own softer grey. Read through
 * [FoliumColors.line]; outside of [FoliumTheme] it falls back to the backlit light palette's.
 */
val LocalFoliumLine = staticCompositionLocalOf { BacklitLightLine }

/** Colour roles the design system names that [MaterialTheme]'s own scheme has no slot for. */
object FoliumColors {
    val line: Color
        @Composable get() = LocalFoliumLine.current
}

/**
 * A deliberately neutral, high-contrast palette.
 *
 * Folium targets e-ink as well as backlit screens, so the scheme avoids tinted surfaces and
 * relies on outlines rather than elevation to separate content.
 *
 * The tertiary role carries the design system's signal: the one colour with work to do — progress,
 * a book already under way, a search hit. Each palette states its own value rather than sharing a
 * hex, because one red cannot sit on paper, on charcoal and on two grades of electronic paper and
 * stay legible on all four. Kept out of every structural role so a greyscale panel loses an accent
 * rather than a distinction, and kept apart from the error role, because a control that deletes and
 * a bar that fills must not ask for the same reading.
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

private val BacklitLightLine = Color(0xFFBFBFBF)
private val BacklitDarkLine = Color(0xFF3A3A3A)
private val EInkLightLine = Color(0xFFB5B8AF)
private val EInkDarkLine = Color(0xFF4A4C46)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1A1A1A),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF3D3D3D),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFD54329),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5DFDB),
    onTertiaryContainer = Color(0xFF4A160D),
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
    tertiary = Color(0xFFD9543C),
    onTertiary = Color(0xFF2B0F06),
    tertiaryContainer = Color(0xFF301712),
    onTertiaryContainer = Color(0xFFEBD9D6),
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

/**
 * Neutral e-paper chrome with restrained color accents; document pixels remain unchanged.
 *
 * [outlineVariant] is drawn well below Material's usual "subtle divider" tone: with no shadow or
 * tonal surface to lean on, it is the only line marking a card edge, a divider or a progress track,
 * so it has to clear the same 3:1 contrast floor as any other essential mark rather than sit in the
 * ambiguous mid-gray band a backlit screen can get away with.
 */
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
    tertiary = Color(0xFFC83F27),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDDCD9),
    onTertiaryContainer = Color(0xFF3D160F),
    background = Color(0xFFF4F4EF),
    onBackground = Color(0xFF171816),
    surface = Color(0xFFF4F4EF),
    onSurface = Color(0xFF171816),
    surfaceVariant = Color(0xFFE8E8E1),
    onSurfaceVariant = Color(0xFF444640),
    surfaceTint = Color(0xFFC83F27),
    inverseSurface = Color(0xFF2B2D29),
    inverseOnSurface = Color(0xFFF4F4EF),
    outline = Color(0xFF61645D),
    outlineVariant = Color(0xFF7B7D76),
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

/**
 * Charcoal e-paper chrome with differentiated neutral surfaces and warm high-contrast text.
 *
 * [outlineVariant] carries the same flat-panel constraint as its light companion above: it is the
 * only line the app draws for a divider, a card edge or a progress track, so it has to stay lighter
 * than a decorative Material divider would need to be.
 *
 * [error] is kept above the 60% relative-luminance mark rather than the mid pink Material would
 * pick for a dark scheme: a mid-gray red reads no clearer than a mid-gray neutral on e-ink, so the
 * one color the panel uses to say "this failed" has to be as far from that band as ink or paper are.
 */
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
    tertiary = Color(0xFFDA563E),
    onTertiary = Color(0xFF2B0F06),
    tertiaryContainer = Color(0xFF3C201B),
    onTertiaryContainer = Color(0xFFE6D4D1),
    background = Color(0xFF171816),
    onBackground = Color(0xFFF3F2E8),
    surface = Color(0xFF171816),
    onSurface = Color(0xFFF3F2E8),
    surfaceVariant = Color(0xFF30322E),
    onSurfaceVariant = Color(0xFFD2D3CA),
    surfaceTint = Color(0xFFDA563E),
    inverseSurface = Color(0xFFECECE4),
    inverseOnSurface = Color(0xFF2B2D29),
    outline = Color(0xFFA2A59B),
    outlineVariant = Color(0xFF80837A),
    scrim = Color(0xFF0B0C0A),
    error = Color(0xFFF5C4C4),
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

    CompositionLocalProvider(
        LocalFoliumEInk provides appearanceMode.isEInk(),
        LocalFoliumLine provides lineColorFor(appearanceMode, systemDark)
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FoliumTypography,
            shapes = FoliumShapes,
            content = content
        )
    }
}

/** Resolves the chosen appearance without consulting platform state or dynamic color APIs. */
internal fun resolveColorScheme(mode: AppearanceMode, systemDark: Boolean) = when (mode) {
    AppearanceMode.SYSTEM -> if (systemDark) DarkScheme else LightScheme
    AppearanceMode.LIGHT -> LightScheme
    AppearanceMode.DARK -> DarkScheme
    AppearanceMode.E_INK_LIGHT -> EInkLightScheme
    AppearanceMode.E_INK_DARK -> EInkDarkScheme
}

/**
 * [LocalFoliumLine]'s value for [mode]: the design's own `#B5B8AF` on e-ink light, its counterpart
 * between surface and outline on e-ink dark, and each backlit palette's own outline variant, which
 * already sits at that softer tone there.
 */
internal fun lineColorFor(mode: AppearanceMode, systemDark: Boolean): Color = when (mode) {
    AppearanceMode.SYSTEM -> if (systemDark) BacklitDarkLine else BacklitLightLine
    AppearanceMode.LIGHT -> BacklitLightLine
    AppearanceMode.DARK -> BacklitDarkLine
    AppearanceMode.E_INK_LIGHT -> EInkLightLine
    AppearanceMode.E_INK_DARK -> EInkDarkLine
}

internal fun usesDarkSystemBarIcons(mode: AppearanceMode, systemDark: Boolean): Boolean = when (mode) {
    AppearanceMode.SYSTEM -> !systemDark
    AppearanceMode.LIGHT, AppearanceMode.E_INK_LIGHT -> true
    AppearanceMode.DARK, AppearanceMode.E_INK_DARK -> false
}

/**
 * The colours an engine relayout carries for a reflowable document's own page, drawn from the same
 * roles the chrome already reads for [mode] rather than a second palette someone has to keep in
 * step: [androidx.compose.material3.ColorScheme.surface] for the page background — the surface a
 * reader reads on, which is not always the app's chrome background — [androidx.compose.material3.ColorScheme.onSurface]
 * for the reading text, and [androidx.compose.material3.ColorScheme.tertiary], the same accent role
 * the chrome already uses for its signal, for links.
 */
internal fun pageColorsFor(mode: AppearanceMode, systemDark: Boolean): ReflowPageColors {
    val scheme = resolveColorScheme(mode, systemDark)
    return ReflowPageColors(
        foregroundHex = scheme.onSurface.toPageColorHex(),
        backgroundHex = scheme.surface.toPageColorHex(),
        accentHex = scheme.tertiary.toPageColorHex()
    )
}

private fun Color.toPageColorHex(): String = String.format(Locale.ROOT, "%06X", toArgb() and 0xFFFFFF)

/** [ReflowPageColors.backgroundHex] as an opaque [Color], the inverse of [Color.toPageColorHex]. */
internal fun ReflowPageColors.toBackgroundColor(): Color =
    Color((0xFF000000L or backgroundHex.toLong(16)).toInt())

/**
 * The page colours [ReflowPageBackground.LIGHT] and [ReflowPageBackground.DARK] resolve to for one
 * appearance mode: this mode's own match-app-theme colours, plus the light and dark variant of
 * whichever appearance family it belongs to — the e-ink family for [AppearanceMode.E_INK_LIGHT] and
 * [AppearanceMode.E_INK_DARK], the backlit family for every other mode — so a reader who pins a page
 * to "Light" or "Dark" still reads it in the same palette family the rest of the app is in, e-ink
 * accents and all, rather than a hardcoded pair that only matches the backlit palette.
 */
data class AppearancePageColors(
    val matchingAppTheme: ReflowPageColors,
    val light: ReflowPageColors,
    val dark: ReflowPageColors
)

internal fun appearancePageColorsFor(mode: AppearanceMode, systemDark: Boolean): AppearancePageColors {
    val isEInk = mode == AppearanceMode.E_INK_LIGHT || mode == AppearanceMode.E_INK_DARK
    val lightMode = if (isEInk) AppearanceMode.E_INK_LIGHT else AppearanceMode.LIGHT
    val darkMode = if (isEInk) AppearanceMode.E_INK_DARK else AppearanceMode.DARK

    return AppearancePageColors(
        matchingAppTheme = pageColorsFor(mode, systemDark),
        light = pageColorsFor(lightMode, systemDark),
        dark = pageColorsFor(darkMode, systemDark)
    )
}

/** Which of [appearance]'s colours a reader's [background] choice actually resolves to. */
internal fun resolveEffectivePageColors(
    background: ReflowPageBackground,
    appearance: AppearancePageColors
): ReflowPageColors = when (background) {
    ReflowPageBackground.MATCH_APP_THEME -> appearance.matchingAppTheme
    ReflowPageBackground.LIGHT -> appearance.light
    ReflowPageBackground.DARK -> appearance.dark
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
