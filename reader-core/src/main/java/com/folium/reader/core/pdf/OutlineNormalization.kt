package com.folium.reader.core.pdf

/**
 * Repairs a narrowly defined malformed outline shape emitted by some PDFs: root-level chapter
 * markers named `1`, `2`, and so on, followed by the entries that belong to each chapter.
 *
 * The input is returned unchanged unless there are at least two childless numeric root markers,
 * every marker forms the exact contiguous sequence starting at one, and every marker has at least
 * one following non-marker sibling. This deliberately leaves ambiguous and already-nested outlines
 * alone.
 */
fun normalizeFlatNumberedChapters(entries: List<OutlineEntry>): List<OutlineEntry> {
    val markerIndexes = mutableListOf<Int>()

    entries.forEachIndexed { index, entry ->
        val markerNumber = entry.title.numericMarkerOrNull() ?: return@forEachIndexed
        if (entry.children.isNotEmpty()) return entries
        if (markerNumber != markerIndexes.size.toLong() + 1L) return entries

        markerIndexes += index
    }

    if (markerIndexes.size < 2) return entries

    markerIndexes.forEachIndexed { markerPosition, entryIndex ->
        val nextMarkerIndex = markerIndexes.getOrNull(markerPosition + 1) ?: entries.size
        if (nextMarkerIndex == entryIndex + 1) return entries
    }

    val normalized = ArrayList<OutlineEntry>(markerIndexes.first() + markerIndexes.size)
    normalized.addAll(entries.subList(0, markerIndexes.first()))

    markerIndexes.forEachIndexed { markerPosition, entryIndex ->
        val nextMarkerIndex = markerIndexes.getOrNull(markerPosition + 1) ?: entries.size
        val groupedEntries = entries.subList(entryIndex + 1, nextMarkerIndex).toList()

        normalized += entries[entryIndex].copy(children = groupedEntries)
    }

    return normalized
}

private fun String.numericMarkerOrNull(): Long? {
    val marker = trim()
    if (marker.isEmpty() || marker.first() == '0' || marker.any { it !in '0'..'9' }) return null

    return marker.toLongOrNull()
}
