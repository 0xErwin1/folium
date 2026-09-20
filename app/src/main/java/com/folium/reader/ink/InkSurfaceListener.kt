package com.folium.reader.ink

import com.folium.reader.core.ink.StrokeId

/** Notifies a host of state changes on an ink drawing surface, all delivered on the UI thread. */
interface InkSurfaceListener {
    /** [canUndo]/[canRedo] changed, typically after a new edit, an undo, or a redo. */
    fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {}

    /** The visible [SheetViewport] changed, from a pan, a zoom, or a size change. */
    fun onViewportChanged(viewport: SheetViewport) {}

    /** The number of live strokes on the sheet changed. */
    fun onStrokeCountChanged(count: Int) {}

    /** A stroke or erase gesture just started on the surface, panning and zooming excluded. */
    fun onStrokeStarted() {}

    /**
     * The [InkSurfaceTool.SELECT] tool's own current selection changed: a fresh selecting gesture
     * replaced it, it was cleared, or the viewport moved while [strokeIds] was non-empty.
     * [boundsViewPx] is the selection's own bounding box in view pixels, `null` exactly when
     * [strokeIds] is empty.
     */
    fun onSelectionChanged(strokeIds: Set<StrokeId>, boundsViewPx: ViewRect?) {}

    /**
     * A write to the underlying sheet failed. The strokes behind the failed edit remain visible and
     * the surface stops accepting new strokes until the host acts: see [InkPersistenceQueue].
     */
    fun onPersistenceFailure(error: Throwable) {}
}
