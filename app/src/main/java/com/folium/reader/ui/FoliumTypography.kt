package com.folium.reader.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.folium.reader.R

/**
 * Six sizes, three weights.
 *
 * Material 3 ships fifteen text styles and, until now, Folium used every one of them at its default
 * value — which meant the app's typography was Roboto by omission rather than by choice. The scale
 * below is the one the design system defines; the fifteen slots are filled from it so nothing falls
 * back to the platform font, and slots that carry the same role share a step rather than inventing
 * a size to fill the gap.
 *
 * Tracking is expressed in em so it scales with the user's font-size preference, unlike the sp
 * values a static specimen is drawn at.
 */
private val SchibstedGrotesk = FontFamily(
    variableFont(FontWeight.Normal, 400),
    variableFont(FontWeight.Medium, 500),
    variableFont(FontWeight.Bold, 700)
)

/**
 * One variable font file serves every weight. The axis is declared explicitly because a variable
 * font does not otherwise resolve a requested weight to its named instance — it would render the
 * 400 default and let the platform fake the rest.
 */
@OptIn(ExperimentalTextApi::class)
private fun variableFont(weight: FontWeight, axis: Int) = Font(
    R.font.schibsted_grotesk,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(axis))
)

private val Display = TextStyle(
    fontFamily = SchibstedGrotesk,
    fontWeight = FontWeight.Medium,
    fontSize = 32.sp,
    lineHeight = 34.sp,
    letterSpacing = (-0.019).em
)

private val Title = TextStyle(
    fontFamily = SchibstedGrotesk,
    fontWeight = FontWeight.Medium,
    fontSize = 21.sp,
    lineHeight = 24.sp,
    letterSpacing = (-0.024).em
)

private val Body = TextStyle(
    fontFamily = SchibstedGrotesk,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 20.sp
)

private val BodySmall = TextStyle(
    fontFamily = SchibstedGrotesk,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 17.sp
)

/** Always set in upper case at the call site; Compose has no text transform of its own. */
private val Label = TextStyle(
    fontFamily = SchibstedGrotesk,
    fontWeight = FontWeight.Bold,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.109.em
)

private val Micro = TextStyle(
    fontFamily = SchibstedGrotesk,
    fontWeight = FontWeight.Medium,
    fontSize = 10.sp,
    lineHeight = 13.sp,
    letterSpacing = 0.04.em
)

/**
 * 14/19, the size between [BodySmall] and [Body] that the screen artboards use for a menu row, a
 * result snippet and a button label — never for a paragraph, which stays on the six-step ramp.
 * Weight follows the role at the call site: 400 for a snippet's running text
 * (S-BusquedaTira.dc.html, S-Search.dc.html: "font-size: 14px; line-height: 19px"), 500 for a menu
 * row or an option (S-Componentes.dc.html's menu component, S-BusquedaOpciones.dc.html's mode rows:
 * "font-size: 14px; font-weight: 500").
 */
private val BodyMidBase = TextStyle(
    fontFamily = SchibstedGrotesk,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 19.sp
)

/**
 * 12/16, the size the screen artboards use for a secondary value or a caption
 * (P-Reader.dc.html, T-Reader.dc.html: "font-size: 12px; color: {{c.muted}}") and, tracked and at
 * medium weight, for an uppercase segmented option label (S-Componentes.dc.html, S-Ajustes.dc.html:
 * "font-size: 12px; font-weight: 500; letter-spacing: 0.6px"; the 16px line-height itself comes from
 * DS-Tactil.dc.html's "font-size: 12px; line-height: 16px; font-weight: 500").
 */
private val CaptionBase = TextStyle(
    fontFamily = SchibstedGrotesk,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp
)

/**
 * The two sizes the screen artboards use that the six-step [FoliumTypography] ramp has no room for.
 * They stay out of the [Typography] slots deliberately: every M3 slot already carries one of the six
 * declared steps, and folding a seventh and eighth size into that list would blur which role each
 * slot serves. Reach for these directly, the way `MaterialTheme.typography.bodyMedium` is reached
 * for, at the handful of call sites the artboards actually draw at 14 or 12 — a menu row, a search
 * option, a result snippet, a segmented label — never as a substitute for a declared step.
 */
internal object FoliumType {
    val BodyMid: TextStyle = BodyMidBase
    val BodyMidMedium: TextStyle = BodyMidBase.copy(fontWeight = FontWeight.Medium)
    val Caption: TextStyle = CaptionBase
    val CaptionEmphasis: TextStyle = CaptionBase.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.05.em)
}

internal val FoliumTypography = Typography(
    displayLarge = Display,
    displayMedium = Display,
    displaySmall = Display,
    headlineLarge = Display,
    headlineMedium = Display,
    headlineSmall = Title,
    titleLarge = Title,
    titleMedium = Body.copy(fontWeight = FontWeight.Medium),
    titleSmall = BodySmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = Body,
    bodyMedium = BodySmall,
    bodySmall = Micro.copy(fontWeight = FontWeight.Normal, letterSpacing = 0.em),
    labelLarge = Body.copy(fontWeight = FontWeight.Medium),
    labelMedium = Label,
    labelSmall = Micro
)
