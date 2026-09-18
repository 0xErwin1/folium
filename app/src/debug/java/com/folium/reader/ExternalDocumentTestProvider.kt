package com.folium.reader

import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.content.ContentProvider
import android.content.ContentValues
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32

class ExternalDocumentTestProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri): String = if (uri.lastPathSegment == "epub") "application/epub+zip" else "application/pdf"

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> if (uri.lastPathSegment == "epub") "external.epub" else "external.pdf"
                    OpenableColumns.SIZE -> file(uri).length()
                    else -> null
                }
            })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    private fun file(uri: Uri): File = if (uri.lastPathSegment == "epub") epub() else pdf()

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    private fun pdf(): File {
        val file = requireNotNull(context).cacheDir.resolve("external-document.pdf")
        if (file.exists()) return file
        val document = PdfDocument()
        try {
            repeat(PAGE_COUNT) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("External ${index + 1}", 80f, 300f, Paint().apply { textSize = 42f; color = Color.BLACK })
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }

    private fun epub(): File {
        val file = requireNotNull(context).cacheDir.resolve("external-document.epub")
        if (file.exists()) return file
        ZipOutputStream(file.outputStream()).use { zip ->
            storedEntry(zip, "mimetype", "application/epub+zip")
            entry(zip, "META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry(zip, "OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?><package version="3.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="book"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book">external</dc:identifier><dc:title>External EPUB</dc:title></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>""")
            entry(zip, "OEBPS/chapter.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>External EPUB page.</p></body></html>""")
        }
        return file
    }

    private fun storedEntry(zip: ZipOutputStream, name: String, contents: String) {
        val bytes = contents.toByteArray()
        zip.putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun entry(zip: ZipOutputStream, name: String, contents: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(contents.toByteArray())
        zip.closeEntry()
    }

    companion object {
        const val AUTHORITY = "com.folium.reader.test.external"
        val PDF_URI: Uri = Uri.parse("content://$AUTHORITY/pdf")
        val EPUB_URI: Uri = Uri.parse("content://$AUTHORITY/epub")
        const val PAGE_COUNT = 3
    }
}
