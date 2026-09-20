package com.folium.reader.library

import com.folium.reader.core.ink.OpenSheet
import com.folium.reader.core.ink.Sheet
import com.folium.reader.core.ink.SheetId

/**
 * Opens or creates one [OpenSheet] at a time for the library to hand to
 * [com.folium.reader.ink.SheetPane].
 *
 * [com.folium.reader.core.ink.SheetStore.open] and [com.folium.reader.core.ink.SheetStore.create]
 * both throw for a [SheetId] already open through their store, so a request for a sheet that is
 * already opening or open is dropped here — [openingId] is what a double tap on a shelf row or on
 * "New sheet" runs into — rather than ever reaching the store. [cancel] bumps the generation so a
 * result that lands after its request was abandoned, because the sheet screen was left before the
 * store answered, is ignored: the same guard [BookOpenRouter] keeps for a book.
 *
 * [openSheet] and [createSheet] carry out the actual store call and report back through the callback
 * they are given, on whatever thread that callback runs on; this router never touches a thread of its
 * own, mirroring [BookOpenRouter].
 */
internal class SheetOpenRouter(
    private val openSheet: (SheetId, (OpenSheet?) -> Unit) -> Unit,
    private val createSheet: (Sheet, (OpenSheet?) -> Unit) -> Unit,
    private val discard: (OpenSheet) -> Unit
) {
    private var generation = 0L

    var openingId: SheetId? = null
        private set

    private var onOpened: ((OpenSheet) -> Unit)? = null
    private var onFailed: (() -> Unit)? = null

    fun rebind(onOpened: (OpenSheet) -> Unit, onFailed: () -> Unit) {
        this.onOpened = onOpened
        this.onFailed = onFailed
    }

    fun open(id: SheetId) {
        if (openingId != null) return

        val requestGeneration = ++generation
        openingId = id
        openSheet(id) { result -> deliver(requestGeneration, result) }
    }

    fun create(sheet: Sheet) {
        if (openingId != null) return

        val requestGeneration = ++generation
        openingId = sheet.id
        createSheet(sheet) { result -> deliver(requestGeneration, result) }
    }

    fun cancel() {
        generation += 1
        openingId = null
    }

    private fun deliver(requestGeneration: Long, result: OpenSheet?) {
        if (requestGeneration != generation) {
            // A sheet nobody will show still holds its writer, and the store refuses a second open of
            // the same id for as long as it does.
            result?.let(discard)
            return
        }

        openingId = null
        if (result != null) onOpened?.invoke(result) else onFailed?.invoke()
    }
}
