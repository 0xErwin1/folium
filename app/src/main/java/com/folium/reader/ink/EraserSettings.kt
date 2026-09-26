package com.folium.reader.ink

import java.text.NumberFormat
import java.util.Locale

/** The eraser stepper's own range and step, in whole millimetres (`rail-spec.md` 2.2: "TAMAÑO ... 4 mm"). */
internal const val ERASER_SIZE_MIN_MM: Int = 1
internal const val ERASER_SIZE_MAX_MM: Int = 20
internal const val ERASER_SIZE_STEP_MM: Int = 1
internal const val ERASER_SIZE_DEFAULT_MM: Int = 4

/** Clamps a stepper step to the eraser size's own range, so a bound is never overshot regardless of the direction stepped from. */
internal fun clampEraserSizeMm(sizeMm: Int): Int = sizeMm.coerceIn(ERASER_SIZE_MIN_MM, ERASER_SIZE_MAX_MM)

/** The stepper's own value text: a whole number of millimetres in the locale's own digits, with a trailing " mm" unit. */
internal fun formatEraserSizeMm(sizeMm: Int, locale: Locale = Locale.getDefault()): String =
    "${NumberFormat.getIntegerInstance(locale).format(sizeMm)} mm"

/**
 * How far, in view pixels, a tap has to land from a stroke's centreline before the eraser removes it,
 * once [ERASER_HIT_MIN_RADIUS_VIEW_PX] is reached. A sheet-unit radius is zoom-invariant by design —
 * the whole point of erasing by width relative to the writing rather than to the screen — but that
 * same invariance means it keeps shrinking in view pixels as the sheet is zoomed out, until a tap can
 * no longer reliably land on it; this floor is applied in view pixels precisely because it exists to
 * counteract that shrinkage.
 */
internal const val ERASER_HIT_MIN_RADIUS_VIEW_PX: Float = 4f

/**
 * The eraser's own hit-test radius in sheet units: half of [sizeMm], converted through [mmToUnits]
 * ([mmToSheetUnits] on a sheet), floored so it never reads as fewer than
 * [ERASER_HIT_MIN_RADIUS_VIEW_PX] view pixels at the current [viewPxPerSheetUnit] scale
 * (`rail-spec.md` task instructions).
 */
internal fun eraserHitRadiusSheetUnits(
    sizeMm: Float,
    viewPxPerSheetUnit: Float,
    mmToUnits: (Float) -> Float = ::mmToSheetUnits
): Float {
    require(sizeMm > 0f) { "sizeMm must be positive, was $sizeMm" }
    require(viewPxPerSheetUnit > 0f) { "viewPxPerSheetUnit must be positive, was $viewPxPerSheetUnit" }

    val nominalRadiusSheetUnits = mmToUnits(sizeMm / 2f)
    val minRadiusSheetUnits = ERASER_HIT_MIN_RADIUS_VIEW_PX / viewPxPerSheetUnit
    return maxOf(nominalRadiusSheetUnits, minRadiusSheetUnits)
}
