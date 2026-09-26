package com.folium.reader.ink

import com.folium.reader.core.ink.SelectionCorner
import com.folium.reader.core.ink.SelectionResizeScale
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetRect
import com.folium.reader.core.ink.selectionResizeScale
import kotlin.math.roundToInt

/** What one [SelectionEditSession] is doing to the SELECT tool's own current selection. */
sealed class SelectionEditKind {
    object Move : SelectionEditKind()
    data class Resize(val corner: SelectionCorner) : SelectionEditKind()
}

/**
 * One drag that moves or resizes the SELECT tool's own current selection, from the pointer's own down
 * point in sheet units to wherever it is now. Pure and Android-free, the same contract
 * [SelectionGestureSession] and [PartialEraseSession] already follow, so [InkDrawingSurface] can drive
 * it on the UI thread without any of it touching a view.
 *
 * [startBoundsSheet] is the selection's own bounding box exactly as it stood the moment the drag
 * began: every [resizeScale] and [previewBounds] call recomputes from it directly, the same way
 * [InkDrawingSurface]'s own straighten-resize preview never resizes from its own last resized value,
 * so the anchor corner a [SelectionEditKind.Resize] drag is pulling against never drifts across moves.
 *
 * [verticalSnapUnits] snaps [translation]'s own vertical component to the nearest whole multiple of
 * itself for a [SelectionEditKind.Move] drag, so a text box carried along keeps its baseline on
 * [SheetRuleGrid]; `null` moves freely on both axes, [InkDrawingSurface]'s own contract for a
 * strokes-only selection. Never applied to a [SelectionEditKind.Resize] drag, whose own translation
 * feeds [resizeScale] instead and must track the pointer exactly.
 */
class SelectionEditSession(
    val kind: SelectionEditKind,
    private val startBoundsSheet: SheetRect,
    private val downSheetPoint: SheetPoint,
    private val verticalSnapUnits: Float? = null
) {
    /** Where the pointer was last recorded, in sheet units; see [onMove]. */
    var currentSheetPoint: SheetPoint = downSheetPoint
        private set

    /** Records the pointer having moved to [point], in sheet units. */
    fun onMove(point: SheetPoint) {
        currentSheetPoint = point
    }

    /** The sheet-space translation this drag represents right now; meaningful only for [SelectionEditKind.Move]. See [verticalSnapUnits] for its own vertical snapping. */
    val translation: SheetPoint
        get() {
            val rawDy = currentSheetPoint.y - downSheetPoint.y
            val snapUnits = verticalSnapUnits
            val dy = if (kind == SelectionEditKind.Move && snapUnits != null) {
                (rawDy / snapUnits).roundToInt() * snapUnits
            } else {
                rawDy
            }
            return SheetPoint(currentSheetPoint.x - downSheetPoint.x, dy)
        }

    /**
     * The anchor and per-axis scale this drag represents right now; meaningful only for [SelectionEditKind.Resize].
     *
     * The dragged corner moves by the pointer's own displacement since it went down, not to the pointer's
     * position: a handle is grabbed anywhere inside its hit area, so following the raw position would
     * resize the selection by the grab offset before the pointer has moved at all.
     */
    fun resizeScale(): SelectionResizeScale = selectionResizeScale(startBoundsSheet, resizeCorner(), draggedCornerPointSheet())

    /** The dragged corner's own sheet-space point right now: its own start position plus this drag's own [translation]; meaningful only for [SelectionEditKind.Resize]. */
    fun draggedCornerPointSheet(): SheetPoint {
        val start = cornerPoint(startBoundsSheet, resizeCorner())
        val delta = translation
        return SheetPoint(start.x + delta.x, start.y + delta.y)
    }

    private fun resizeCorner(): SelectionCorner = (kind as SelectionEditKind.Resize).corner

    /** The selection's own bounding box as this drag would leave it right now, for the live outline and handles. */
    fun previewBounds(): SheetRect = when (kind) {
        SelectionEditKind.Move -> {
            val delta = translation
            SheetRect(
                left = startBoundsSheet.left + delta.x,
                top = startBoundsSheet.top + delta.y,
                right = startBoundsSheet.right + delta.x,
                bottom = startBoundsSheet.bottom + delta.y
            )
        }
        is SelectionEditKind.Resize -> {
            val scale = resizeScale()
            normalized(
                left = scale.anchor.x + (startBoundsSheet.left - scale.anchor.x) * scale.scaleX,
                top = scale.anchor.y + (startBoundsSheet.top - scale.anchor.y) * scale.scaleY,
                right = scale.anchor.x + (startBoundsSheet.right - scale.anchor.x) * scale.scaleX,
                bottom = scale.anchor.y + (startBoundsSheet.bottom - scale.anchor.y) * scale.scaleY
            )
        }
    }

    /** Whether this drag has moved or resized the selection at all: a drag that ends exactly where it started commits nothing. */
    fun hasChanged(): Boolean = translation.x != 0f || translation.y != 0f
}

private fun cornerPoint(bounds: SheetRect, corner: SelectionCorner): SheetPoint = when (corner) {
    SelectionCorner.TOP_LEFT -> SheetPoint(bounds.left, bounds.top)
    SelectionCorner.TOP_RIGHT -> SheetPoint(bounds.right, bounds.top)
    SelectionCorner.BOTTOM_LEFT -> SheetPoint(bounds.left, bounds.bottom)
    SelectionCorner.BOTTOM_RIGHT -> SheetPoint(bounds.right, bounds.bottom)
}

/** [SheetRect]'s own corners, reordered so [SheetRect.left] <= [SheetRect.right] and [SheetRect.top] <= [SheetRect.bottom]: a negative resize scale flips which raw corner ends up where. */
private fun normalized(left: Float, top: Float, right: Float, bottom: Float): SheetRect = SheetRect(
    left = minOf(left, right),
    top = minOf(top, bottom),
    right = maxOf(left, right),
    bottom = maxOf(top, bottom)
)
