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
     * [strokeIds] is empty. [hasTextBoxes] is whether the selection holds at least one
     * [com.folium.reader.core.ink.SheetTextBox], the one thing a host's selection menu needs to know
     * beyond [strokeIds] itself to decide whether to offer its own TEXT item.
     */
    fun onSelectionChanged(strokeIds: Set<StrokeId>, boundsViewPx: ViewRect?, hasTextBoxes: Boolean) {}

    /**
     * A move or resize drag against the current selection just started or just ended, committed or
     * not. A host hides the selection menu for exactly as long as [editing] stays `true`, since the
     * design never shows the menu while a drag is live.
     */
    fun onSelectionEditingChanged(editing: Boolean) {}

    /**
     * A [InkSurfaceTool.TEXT] session just opened or just closed (committed, whether or not it
     * actually changed anything). A host closes the sheet screen and the selector panel only for as
     * long as [editing] stays `false`, since neither should compete with the keyboard for the same
     * space.
     */
    fun onTextEditingChanged(editing: Boolean) {}

    /**
     * A write to the underlying sheet failed. The strokes behind the failed edit remain visible and
     * the surface stops accepting new strokes until the host acts: see [InkPersistenceQueue].
     */
    fun onPersistenceFailure(error: Throwable) {}
}
