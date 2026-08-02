package com.folium.reader

import android.database.Cursor
import android.database.MatrixCursor
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.FileNotFoundException

class FixtureDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor {
        val columns = projection ?: ROOT_COLUMNS
        return MatrixCursor(columns).apply { addRow(rootRow(columns)) }
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        mode().throwForQuery()
        if (documentId != ROOT || mode() == Mode.Missing) throw FileNotFoundException()
        val columns = projection ?: DOCUMENT_COLUMNS
        return MatrixCursor(columns).apply { addRow(documentRow(documentId, columns)) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        mode().throwForQuery()
        if (parentDocumentId != ROOT || mode() == Mode.Missing) throw FileNotFoundException()
        val requested = projection ?: DOCUMENT_COLUMNS
        val columns = if (mode() == Mode.NoDisplayNameColumn) {
            requested.filterNot { it == DocumentsContract.Document.COLUMN_DISPLAY_NAME }.toTypedArray()
        } else {
            requested
        }
        return MatrixCursor(columns).apply {
            CHILDREN.forEach { addRow(documentRow(it, columns)) }
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == ROOT && documentId in CHILDREN

    override fun openDocument(documentId: String, mode: String, signal: android.os.CancellationSignal?): ParcelFileDescriptor {
        if (mode() == Mode.UnstablePdf && documentId == PDF) throw IllegalStateException()
        if (documentId !in setOf(PDF, ODD_NAME_PDF) || mode() == Mode.Unreadable && documentId == PDF) throw FileNotFoundException()
        return ParcelFileDescriptor.open(pdfFile(documentId), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * A genuinely renderable PDF, not a stub: the reader is exercised end to end against this
     * provider, so a placeholder here would only prove the plumbing up to the point where a real
     * document would have been parsed.
     */
    private fun pdfFile(documentId: String): java.io.File {
        val pages = pageCount(documentId)
        val file = context!!.cacheDir.resolve("fixture-$documentId-$pages.pdf")
        if (file.length() == 0L) writePdf(file, pages)
        return file
    }

    private fun writePdf(file: java.io.File, pages: Int) {
        val document = android.graphics.pdf.PdfDocument()
        try {
            repeat(pages) { index -> document.finishPage(drawPage(document, index)) }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun drawPage(document: android.graphics.pdf.PdfDocument, index: Int): android.graphics.pdf.PdfDocument.Page {
        val page = document.startPage(
            android.graphics.pdf.PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
        )
        val canvas = page.canvas
        canvas.drawColor(android.graphics.Color.WHITE)

        val border = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 8f
            color = android.graphics.Color.BLACK
        }
        canvas.drawRect(40f, 40f, PAGE_WIDTH - 40f, PAGE_HEIGHT - 40f, border)

        val label = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 120f
            isAntiAlias = true
        }
        canvas.drawText("Page ${index + 1}", 90f, 300f, label)

        val marker = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
        canvas.drawRect(90f, 400f + index * 120f, 90f + (index + 1) * 100f, 480f + index * 120f, marker)

        return page
    }

    private fun pageCount(documentId: String): Int = if (documentId == PDF) FIXTURE_PAGE_COUNT else 1

    private fun rootRow(columns: Array<String>): Array<Any?> = columns.map { column ->
        when (column) {
            DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT
            DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT
            DocumentsContract.Root.COLUMN_TITLE -> "Folium SAF Fixture"
            DocumentsContract.Root.COLUMN_FLAGS -> DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES -> 1L
            DocumentsContract.Root.COLUMN_MIME_TYPES -> "application/pdf"
            else -> null
        }
    }.toTypedArray()

    private fun documentRow(id: String, columns: Array<String>): Array<Any?> = columns.map { column ->
        when (column) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> if (mode() == Mode.MalformedChild && id == MALFORMED) null else id
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> displayName(id)
            DocumentsContract.Document.COLUMN_MIME_TYPE -> mimeType(id)
            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> version(id)
            DocumentsContract.Document.COLUMN_FLAGS -> if (id == ROOT) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else 0
            DocumentsContract.Document.COLUMN_SIZE -> 1L
            else -> null
        }
    }.toTypedArray()

    private fun displayName(id: String): String = when (id) {
        ROOT -> "library"
        PDF -> if (mode() == Mode.Renamed) "renamed.pdf" else "original.pdf"
        ODD_NAME_PDF -> "opaque-resource"
        PDF_NAMED_TEXT -> "misleading.pdf"
        TEXT -> "note.txt"
        DIRECTORY -> "folder"
        else -> "broken"
    }

    private fun mimeType(id: String): String = when (id) {
        PDF, ODD_NAME_PDF -> "application/pdf"
        DIRECTORY, ROOT -> DocumentsContract.Document.MIME_TYPE_DIR
        else -> "text/plain"
    }

    private fun version(id: String): Any? = when {
        mode() == Mode.RootMalformed && id == ROOT -> null
        mode() == Mode.MalformedChild && id == MALFORMED -> null
        mode() == Mode.Renamed && id in setOf(ROOT, PDF) -> 2L
        else -> 1L
    }

    private fun mode(): Mode = Mode.valueOf(context!!.getSharedPreferences(PREFERENCES, 0).getString(MODE, Mode.Normal.name)!!)
    private fun Mode.throwForQuery() { if (this == Mode.Unavailable) throw IllegalStateException() }

    enum class Mode { Normal, Renamed, Missing, Unavailable, RootMalformed, MalformedChild, Unreadable, UnstablePdf, NoDisplayNameColumn }

    companion object {
        const val AUTHORITY = "com.folium.reader.debug.documents"
        const val FIXTURE_PAGE_COUNT = 3
        private const val PAGE_WIDTH = 595
        private const val PAGE_HEIGHT = 842
        const val ROOT = "root-token"
        const val PDF = "pdf-token"
        const val ODD_NAME_PDF = "odd-name-pdf-token"
        const val PDF_NAMED_TEXT = "pdf-named-text-token"
        const val TEXT = "text-token"
        const val DIRECTORY = "directory-token"
        const val MALFORMED = "malformed-token"
        private val CHILDREN = setOf(PDF, ODD_NAME_PDF, PDF_NAMED_TEXT, TEXT, DIRECTORY, MALFORMED)
        private const val PREFERENCES = "fixture-documents"
        private const val MODE = "mode"
        private val ROOT_COLUMNS = arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
        private val DOCUMENT_COLUMNS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE)
        fun setMode(context: android.content.Context, mode: Mode) { context.getSharedPreferences(PREFERENCES, 0).edit().putString(MODE, mode.name).commit() }
        fun assertAncestryContract() {
            check(CHILDREN.all { it != ROOT })
            check(ROOT !in CHILDREN)
            check("unknown" !in CHILDREN)
        }
        fun assertRootProjectionContract() {
            val row = ROOT_COLUMNS.associateWith { column -> when (column) {
                DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT
                DocumentsContract.Root.COLUMN_TITLE -> "Folium SAF Fixture"
                DocumentsContract.Root.COLUMN_FLAGS -> DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES -> 1L
                else -> null
            } }
            check(row[DocumentsContract.Root.COLUMN_ROOT_ID] == ROOT)
            check(row[DocumentsContract.Root.COLUMN_DOCUMENT_ID] == ROOT)
            check(row[DocumentsContract.Root.COLUMN_AVAILABLE_BYTES] is Long)
        }
    }
}
