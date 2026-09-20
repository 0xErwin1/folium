package com.folium.reader.ink

import com.folium.reader.core.ink.SelectionCorner
import com.folium.reader.core.ink.SelectionResizeScale
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetRect
import com.folium.reader.core.ink.selectionResizeScale

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
 */
class SelectionEditSession(
    val kind: SelectionEditKind,
    private val startBoundsSheet: SheetRect,
    private val downSheetPoint: SheetPoint
) {
    private var currentSheetPoint: SheetPoint = downSheetPoint

    /** Records the pointer having moved to [point], in sheet units. */
    fun onMove(point: SheetPoint) {
        currentSheetPoint = point
    }

    /** The sheet-space translation this drag represents right now; meaningful only for [SelectionEditKind.Move]. */
    val translation: SheetPoint
        get() = SheetPoint(currentSheetPoint.x - downSheetPoint.x, currentSheetPoint.y - downSheetPoint.y)

    /**
     * The anchor and per-axis scale this drag represents right now; meaningful only for [SelectionEditKind.Resize].
     *
     * The dragged corner moves by the pointer's own displacement since it went down, not to the pointer's
     * position: a handle is grabbed anywhere inside its hit area, so following the raw position would
     * resize the selection by the grab offset before the pointer has moved at all.
     */
    fun resizeScale(): SelectionResizeScale {
        val corner = (kind as SelectionEditKind.Resize).corner
        val start = cornerPoint(startBoundsSheet, corner)
        val delta = translation

        return selectionResizeScale(startBoundsSheet, corner, SheetPoint(start.x + delta.x, start.y + delta.y))
    }

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
