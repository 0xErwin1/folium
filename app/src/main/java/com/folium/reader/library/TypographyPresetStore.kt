package com.folium.reader.library

import com.folium.reader.core.library.BookId
import com.folium.reader.core.pdf.TYPOGRAPHY_VERSION_MARKER
import com.folium.reader.core.pdf.TypographyPreset
import com.folium.reader.core.pdf.TypographyRecords

/**
 * A reader's global typography choice, and the per-book overrides layered on top of it.
 *
 * The surface is deliberately asymmetric: [readGlobal] always returns a usable
 * [TypographyPreset], falling back to [TypographyPreset.DEFAULT] when nothing was ever chosen,
 * because a global preference is never "absent" from the reader's point of view. [readOverride]
 * returns `null` in that same situation, because a book without one has to fall back to the
 * global preset rather than to [TypographyPreset.DEFAULT] — the caller needs to tell "no override"
 * from "an override that happens to equal the default" apart, and only a nullable result can carry
 * that. A malformed per-book file reads as no override for the same reason: the smaller surprise
 * for a reader who already set a global preference is the book following it, not silently
 * resetting to the shipped default.
 *
 * Called only from the library worker thread.
 */
class TypographyPresetStore(private val paths: LibraryPaths) {

    fun readGlobal(): TypographyPreset = decode(AtomicTextFile(paths.typographyFile)) ?: TypographyPreset.DEFAULT

    fun writeGlobal(preset: TypographyPreset): Boolean = write(AtomicTextFile(paths.typographyFile), preset)

    fun readOverride(id: BookId): TypographyPreset? = decode(AtomicTextFile(paths.typographyFile(id)))

    fun writeOverride(id: BookId, preset: TypographyPreset): Boolean = write(AtomicTextFile(paths.typographyFile(id)), preset)

    /**
     * Deletes the file rather than writing an empty or marker-only one, which would read back the
     * same but leave a file claiming the book was configured — every future reader of the directory
     * would have to know it means nothing.
     */
    fun clearOverride(id: BookId): Boolean = AtomicTextFile(paths.typographyFile(id)).delete()

    private fun decode(file: AtomicTextFile): TypographyPreset? {
        val lines = file.readLines()
        if (lines.firstOrNull() != TYPOGRAPHY_VERSION_MARKER) return null
        return lines.getOrNull(1)?.let(TypographyRecords::decode)
    }

    private fun write(file: AtomicTextFile, preset: TypographyPreset): Boolean =
        file.write(listOf(TYPOGRAPHY_VERSION_MARKER, TypographyRecords.encode(preset)))
}
