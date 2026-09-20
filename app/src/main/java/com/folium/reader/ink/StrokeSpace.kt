package com.folium.reader.ink

import com.folium.reader.core.ink.InkInputKind
import com.folium.reader.core.ink.SheetPoint

/** A point in the fixed coordinate space `androidx.ink` `Stroke`s are authored in; see [StrokeSpace]. */
data class StrokeSpacePoint(val x: Float, val y: Float)

/**
 * The fixed coordinate space every `androidx.ink` `Stroke` this app produces is built in:
 * [UNITS_PER_SHEET_UNIT] stroke-space units per sheet unit, independent of the current zoom or pan.
 * A finished stroke is authored with a `motionEventToWorldTransform`/`strokeToWorldTransform` pair
 * that maps straight into this space, so its geometry never has to be rebuilt when the viewport
 * changes, and its brush sizes stay sane round numbers instead of tiny fractions of a sheet unit.
 */
object StrokeSpace {
    const val UNITS_PER_SHEET_UNIT: Float = 1000f

    fun sheetToStrokeSpace(sheetUnits: Float): Float = sheetUnits * UNITS_PER_SHEET_UNIT
    fun strokeSpaceToSheet(strokeSpaceUnits: Float): Float = strokeSpaceUnits / UNITS_PER_SHEET_UNIT

    fun sheetToStrokeSpace(point: SheetPoint): StrokeSpacePoint =
        StrokeSpacePoint(sheetToStrokeSpace(point.x), sheetToStrokeSpace(point.y))

    fun strokeSpaceToSheet(point: StrokeSpacePoint): SheetPoint =
        SheetPoint(strokeSpaceToSheet(point.x), strokeSpaceToSheet(point.y))
}

/**
 * The subset of `android.view.MotionEvent.TOOL_TYPE_*` values this app distinguishes, expressed as
 * the raw `Int` constants Android defines rather than the `MotionEvent` type itself, so the mapping
 * is a plain JVM unit rather than an Android one. `TOOL_TYPE_ERASER` is treated as [InkInputKind.UNKNOWN]:
 * this app never sees a hardware eraser tip, only its own software eraser tool.
 */
fun inkInputKindOfMotionEventToolType(toolType: Int): InkInputKind = when (toolType) {
    1 -> InkInputKind.FINGER
    2 -> InkInputKind.STYLUS
    3 -> InkInputKind.MOUSE
    else -> InkInputKind.UNKNOWN
}
