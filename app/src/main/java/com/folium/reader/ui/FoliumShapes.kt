package com.folium.reader.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Every corner is square.
 *
 * This is the one place the design system is absolute rather than proportional, and it is what
 * makes the rest of it hold together: with no radius, the only thing separating one surface from
 * another is a rule, which is exactly the property that lets the same layout render on a backlit
 * screen and on electronic paper without a second set of decisions.
 *
 * Material 3 defaults every shape slot to a rounded corner, so leaving these unset is a choice too
 * — just not one anybody made.
 */
private val Square = RoundedCornerShape(0.dp)

internal val FoliumShapes = Shapes(
    extraSmall = Square,
    small = Square,
    medium = Square,
    large = Square,
    extraLarge = Square
)
