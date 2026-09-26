package com.folium.reader.ink

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.folium.reader.core.ink.StrokeId

/**
 * What one drawing surface last reported about its selection, its text editor and its writer, held as
 * Compose state so the overlays a host draws over that surface follow it: the selection menu
 * ([InkSelectionMenu]), the save-failure banner ([InkPersistenceBanner]) and back closing the text
 * editor. A host forwards the matching [InkSurfaceListener] callbacks to it.
 */
@Stable
internal class InkSurfaceUiState {
    var selectedStrokeIds by mutableStateOf<Set<StrokeId>>(emptySet())
        private set
    var selectionBoundsViewPx by mutableStateOf<ViewRect?>(null)
        private set
    var selectionHasTextBoxes by mutableStateOf(false)
        private set
    var selectionEditing by mutableStateOf(false)
        private set
    var textEditing by mutableStateOf(false)
        private set
    var persistenceFailed by mutableStateOf(false)
        private set

    /** See [InkSurfaceListener.onSelectionChanged]. */
    fun onSelectionChanged(strokeIds: Set<StrokeId>, boundsViewPx: ViewRect?, hasTextBoxes: Boolean) {
        selectedStrokeIds = strokeIds
        selectionBoundsViewPx = boundsViewPx
        selectionHasTextBoxes = hasTextBoxes
    }

    /** See [InkSurfaceListener.onSelectionEditingChanged]. */
    fun onSelectionEditingChanged(editing: Boolean) {
        selectionEditing = editing
    }

    /** See [InkSurfaceListener.onTextEditingChanged]. */
    fun onTextEditingChanged(editing: Boolean) {
        textEditing = editing
    }

    /** See [InkSurfaceListener.onPersistenceFailure]; a writer that refused an edit never recovers. */
    fun onPersistenceFailure() {
        persistenceFailed = true
    }
}
