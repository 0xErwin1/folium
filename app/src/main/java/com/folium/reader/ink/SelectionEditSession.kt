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

    /** The anchor and per-axis scale this drag represents right now; meaningful only for [SelectionEditKind.Resize]. */
    fun resizeScale(): SelectionResizeScale {
        val corner = (kind as SelectionEditKind.Resize).corner
        return selectionResizeScale(startBoundsSheet, corner, currentSheetPoint)
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
    fun hasChanged(): Boolean = when (kind) {
        SelectionEditKind.Move -> translation.x != 0f || translation.y != 0f
        is SelectionEditKind.Resize -> resizeScale().let { it.scaleX != 1f || it.scaleY != 1f }
    }
}

/** [SheetRect]'s own corners, reordered so [SheetRect.left] <= [SheetRect.right] and [SheetRect.top] <= [SheetRect.bottom]: a negative resize scale flips which raw corner ends up where. */
private fun normalized(left: Float, top: Float, right: Float, bottom: Float): SheetRect = SheetRect(
    left = minOf(left, right),
    top = minOf(top, bottom),
    right = maxOf(left, right),
    bottom = maxOf(top, bottom)
)
