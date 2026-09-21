package com.folium.reader.ink

import com.folium.reader.core.ink.InkInputKind

/** What a touch sequence on the sheet surface is currently doing. */
enum class InkGesture { DRAW, ERASE, SHAPE, SELECT, TEXT, PAN_ZOOM, IGNORE }

/**
 * Decides [InkGesture] from pointer count, the tool type of each pointer as it goes down, and the
 * currently selected [InkSurfaceTool], following the surface's input rules:
 *
 * - A single pointer draws, erases, drags out a shape, or selects, according to the selected tool.
 * - A second pointer going down cancels an in-progress draw, erase, shape drag or select gesture and
 *   starts [InkGesture.PAN_ZOOM], which lasts until every pointer has lifted, even once the pointer
 *   count drops back to one.
 * - Once a [InkInputKind.STYLUS] pointer has gone down anywhere in this arbiter's lifetime, a lone
 *   [InkInputKind.FINGER] pointer only pans rather than drawing, erasing, dragging out a shape or
 *   selecting, so a resting palm cannot leave a mark while a stylus is in use.
 * - With [InkSurfaceTool.VIEW] selected, a lone pointer of any kind — finger, stylus or mouse — pans
 *   rather than drawing or erasing, so this tool never marks the sheet.
 *
 * Not thread-safe: driven from the single thread touch events already arrive on.
 */
class InkGestureArbiter {
    private var stylusEverSeen = false
    private var activePointerCount = 0

    var gesture: InkGesture = InkGesture.IGNORE
        private set

    /**
     * Records a new pointer going down with [toolType], while [tool] is the surface's currently
     * selected tool. Returns whether this call just canceled an in-progress draw or erase, which
     * happens exactly when the second concurrent pointer arrives.
     */
    fun onPointerDown(toolType: InkInputKind, tool: InkSurfaceTool): Boolean {
        if (toolType == InkInputKind.STYLUS) stylusEverSeen = true
        activePointerCount += 1

        return when (activePointerCount) {
            1 -> {
                gesture = firstPointerGesture(toolType, tool)
                false
            }
            2 -> {
                val canceledActiveGesture = gesture == InkGesture.DRAW || gesture == InkGesture.ERASE ||
                    gesture == InkGesture.SHAPE || gesture == InkGesture.SELECT || gesture == InkGesture.TEXT
                gesture = InkGesture.PAN_ZOOM
                canceledActiveGesture
            }
            else -> false
        }
    }

    private fun firstPointerGesture(toolType: InkInputKind, tool: InkSurfaceTool): InkGesture = when {
        tool == InkSurfaceTool.VIEW -> InkGesture.PAN_ZOOM
        toolType == InkInputKind.FINGER && stylusEverSeen -> InkGesture.PAN_ZOOM
        tool == InkSurfaceTool.ERASER -> InkGesture.ERASE
        tool == InkSurfaceTool.SHAPE -> InkGesture.SHAPE
        tool == InkSurfaceTool.SELECT -> InkGesture.SELECT
        tool == InkSurfaceTool.TEXT -> InkGesture.TEXT
        else -> InkGesture.DRAW
    }

    /** Records a pointer lifting, leaving [remainingPointerCount] still down. [InkGesture.PAN_ZOOM] persists until this reaches zero. */
    fun onPointerUp(remainingPointerCount: Int) {
        require(remainingPointerCount >= 0) { "remainingPointerCount must be non-negative, was $remainingPointerCount" }
        activePointerCount = remainingPointerCount
        if (activePointerCount == 0) gesture = InkGesture.IGNORE
    }

    /** Records the whole touch sequence being canceled by the platform, ending the gesture immediately. */
    fun onCancel() {
        activePointerCount = 0
        gesture = InkGesture.IGNORE
    }
}
