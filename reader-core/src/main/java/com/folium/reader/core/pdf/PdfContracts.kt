package com.folium.reader.core.pdf

import java.io.Closeable
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextEngineVersion

fun interface CancellationSignal { fun isCancelled(): Boolean }

data class PageSpacePoint(val x: Float, val y: Float) {
    init { require(x in 0f..1f && y in 0f..1f) }
}

data class PageSpaceRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init { require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f && right > left && bottom > top) }
}

data class PdfSource(val path: String) { init { require(path.isNotBlank()) } }
data class PageInfo(val index: Int, val width: Float, val height: Float, val rotationDegrees: Int) {
    init { require(index >= 0 && width > 0 && height > 0 && rotationDegrees in setOf(0, 90, 180, 270)) }
}
data class RenderSpec(val width: Int, val height: Int, val pageSpace: PageSpaceRect = PageSpaceRect(0f, 0f, 1f, 1f)) {
    init {
        val byteCount = rgbaByteCountOrNull(width, height)
        require(width > 0 && height > 0 && byteCount != null && byteCount <= MAX_RASTER_BYTES)
    }
}
data class Raster(val width: Int, val height: Int, val rgba: ByteArray) {
    init { require(rgbaByteCountOrNull(width, height) == rgba.size.toLong()) }
}

private const val RGBA_BYTES_PER_PIXEL = 4L
private const val MAX_RASTER_BYTES = 64L * 1024 * 1024

private fun rgbaByteCountOrNull(width: Int, height: Int): Long? = try {
    Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), RGBA_BYTES_PER_PIXEL)
} catch (_: ArithmeticException) {
    null
}

sealed class PdfFailure {
    data object Corrupt : PdfFailure()
    data object Unsupported : PdfFailure()
    data object PasswordRequired : PdfFailure()
    data object WrongPassword : PdfFailure()
    data object Closed : PdfFailure()
    data object TextExtraction : PdfFailure()
    data class Resource(val retryable: Boolean) : PdfFailure()
}

class PdfException(val failure: PdfFailure, cause: Throwable? = null) : RuntimeException(failure.javaClass.simpleName, cause)

/**
 * One node of a document's table of contents. [pageIndex] is `null` when the entry's destination
 * could not be resolved to a page; [children] preserves nesting.
 */
data class OutlineEntry(
    val title: String,
    val pageIndex: Int?,
    val children: List<OutlineEntry> = emptyList()
) {
    init { require(pageIndex == null || pageIndex >= 0) { "pageIndex must be non-negative or null, was $pageIndex" } }
}

/** One row of a depth-first flattening of an outline tree, ready for a flat list presentation. */
data class OutlineRow(val title: String, val pageIndex: Int?, val depth: Int)

/**
 * Flattens [entries] depth-first, assigning each row the depth of its ancestry. Stops descending
 * past [maxDepth] as a stack-safety bound against a malformed or cyclic nesting chain, not as a
 * performance cap.
 */
fun flattenOutline(entries: List<OutlineEntry>, maxDepth: Int = 32): List<OutlineRow> {
    val rows = mutableListOf<OutlineRow>()

    fun visit(nodes: List<OutlineEntry>, depth: Int) {
        if (depth > maxDepth) return
        for (node in nodes) {
            rows += OutlineRow(node.title, node.pageIndex, depth)
            visit(node.children, depth + 1)
        }
    }

    visit(entries, 0)
    return rows
}

interface PdfEngine {
    val textEngineVersion: TextEngineVersion
    fun open(source: PdfSource): PdfDocument
}
interface PdfDocument : Closeable {
    val pageCount: Int
    fun pageInfo(index: Int): PageInfo
    fun buildDisplayList(index: Int): DisplayList
    fun extractText(index: Int): TextPage

    /** The document's table of contents. An empty list means the document has none. */
    fun outline(): List<OutlineEntry>
    override fun close()
}
interface DisplayList : Closeable {
    fun render(spec: RenderSpec, cancellationSignal: CancellationSignal = CancellationSignal { false }): Raster
    override fun close()
}
