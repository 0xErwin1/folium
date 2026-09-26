package com.folium.reader.ink

/** What a [SheetPaneHistory] drives: the undo and redo of one live drawing surface. */
interface InkHistoryTarget {
    fun undo()

    fun redo()
}
