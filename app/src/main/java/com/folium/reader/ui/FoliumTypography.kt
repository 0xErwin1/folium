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
