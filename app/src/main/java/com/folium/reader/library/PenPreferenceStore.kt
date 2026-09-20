package com.folium.reader.library

import com.folium.reader.ink.PenSettings
import com.folium.reader.ink.PenSettingsCodec

/** Persists the pen's remembered tip, width and colour across sheets and app restarts. */
internal class PenPreferenceStore(paths: LibraryPaths) {
    private val file = AtomicTextFile(paths.penFile)

    fun read(): PenSettings = PenSettingsCodec.decode(file.readLines())

    /** Returns whether the write succeeded. */
    fun write(settings: PenSettings): Boolean = file.write(PenSettingsCodec.encode(settings))
}
