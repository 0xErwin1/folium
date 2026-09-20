package com.folium.reader.ink

/** Notifies a host of state changes on an ink drawing surface, all delivered on the UI thread. */
interface InkSurfaceListener {
    /** [canUndo]/[canRedo] changed, typically after a new edit, an undo, or a redo. */
    fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {}

    /** The visible [SheetViewport] changed, from a pan, a zoom, or a size change. */
    fun onViewportChanged(viewport: SheetViewport) {}

    /** The number of live strokes on the sheet changed. */
    fun onStrokeCountChanged(count: Int) {}

    /**
     * A write to the underlying sheet failed. The strokes behind the failed edit remain visible and
     * the surface stops accepting new strokes until the host acts: see [InkPersistenceQueue].
     */
    fun onPersistenceFailure(error: Throwable) {}
}
