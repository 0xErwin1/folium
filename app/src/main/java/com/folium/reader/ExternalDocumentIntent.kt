package com.folium.reader

import android.content.Intent
import android.net.Uri
import com.folium.reader.core.library.BookFormat
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.ImportOutcome
import com.folium.reader.core.library.ImportReport
import com.folium.reader.library.PickedSource
import java.io.InputStream

internal fun isSupportedExternalDocument(action: String?, scheme: String?, mimeType: String?): Boolean =
    action == Intent.ACTION_VIEW && scheme == "content" && mimeType in BookFormat.entries.map { it.mimeType }

internal fun Intent.externalDocumentUri(): Uri? =
    data?.takeIf { isSupportedExternalDocument(action, it.scheme, type) }

/**
 * Keeps an external import's completion delivery with the retained activity state. The import itself
 * is private-storage based, so only the short-lived content URI grant is used while copying.
 */
internal class BookOpenRouter(
    private val open: (BookId, (com.folium.reader.library.OpenBookRequest?) -> Unit) -> Unit
) {
    private var generation = 0L
    var pendingBookId: BookId? = null
        private set
    private var onOpened: ((com.folium.reader.library.OpenBookRequest) -> Unit)? = null

    fun rebind(onOpened: (com.folium.reader.library.OpenBookRequest) -> Unit) {
        this.onOpened = onOpened
    }

    fun request(id: BookId) {
        val requestGeneration = ++generation
        pendingBookId = id
        open(id) { request ->
            if (requestGeneration != generation || request == null) return@open
            pendingBookId = null
            onOpened?.invoke(request)
        }
    }

    fun cancel() {
        generation += 1
        pendingBookId = null
    }
}

internal class ExternalDocumentIntake(
    private val import: (List<PickedSource>, (ImportReport) -> Unit) -> Unit
) {
    private var generation = 0L
    private var onImported: ((BookId) -> Unit)? = null

    fun rebind(onImported: (BookId) -> Unit) {
        this.onImported = onImported
    }

    fun cancel() {
        generation += 1
    }

    fun import(uri: Uri, displayName: String, open: () -> InputStream) {
        val requestGeneration = ++generation
        import(listOf(PickedSource(displayName, open))) { report ->
            if (requestGeneration != generation) return@import
            val imported = report.outcomes.filterIsInstance<ImportOutcome.Imported>().lastOrNull() ?: return@import
            onImported?.invoke(imported.book.id)
        }
    }
}
