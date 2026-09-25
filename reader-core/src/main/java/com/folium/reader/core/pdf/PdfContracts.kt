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

/** The page geometry and stylesheet the engine lays a reflowable document out against. */
data class ReflowSettings(val box: ReflowLayoutBox, val userCss: String)

interface PdfDocument : Closeable {
    val pageCount: Int
    fun pageInfo(index: Int): PageInfo
    fun buildDisplayList(index: Int): DisplayList
    fun extractText(index: Int): TextPage

    /**
     * Rasterizes [index] under [spec] in one step: builds a display list, renders it, and closes
     * it again, so a caller that only ever wants the raster never has to hold the display list
     * itself. [beforeRender] runs first and is meant for work that belongs in the same critical
     * section as the render that follows it, such as reporting a page's measured shape.
     *
     * The default implementation composes [buildDisplayList] and [DisplayList.render] as three
     * separate calls; an engine that can rasterize under a single lock acquisition overrides this
     * to do so.
     */
    fun renderPage(
        index: Int,
        spec: RenderSpec,
        cancellationSignal: CancellationSignal = CancellationSignal { false },
        beforeRender: () -> Unit = {}
    ): Raster {
        beforeRender()
        val displayList = buildDisplayList(index)
        return try {
            displayList.render(spec, cancellationSignal)
        } finally {
            displayList.close()
        }
    }

    /** The document's table of contents. An empty list means the document has none. */
    fun outline(): List<OutlineEntry>

    /**
     * What the file says it is, as opposed to what it is called.
     *
     * Producers fill this in inconsistently and sometimes fill it with rubbish — a template name, a
     * path, the string "untitled" — so every field is optional and a caller has to be prepared for
     * all of them to be absent.
     */
    fun metadata(): DocumentMetadata

    /** Whether the engine can re-paginate this document under a different [ReflowSettings]. */
    val reflowable: Boolean get() = false

    /**
     * Mints a token that identifies [pageIndex]'s place in the document well enough to be resolved
     * again after the document has been laid out differently. Null for a fixed-layout document,
     * because a fixed page index is already stable and a token would be a second, weaker way of
     * saying the same thing.
     */
    fun makePositionToken(pageIndex: Int): ReadingPositionToken? = null

    /** Resolves [token] to a page index under the document's current layout; null when it cannot be. */
    fun resolvePositionToken(token: ReadingPositionToken): Int? = null

    /**
     * The chapter [pageIndex] belongs to and the character offset it starts at under the current
     * layout, measured the same way [makePositionToken] measures it. Null for a fixed-layout
     * document, where the page index itself is the stable anchor.
     *
     * An engine may extract the text of every page in the chapter to answer, and serializes that
     * work with every other call on this document, so call it from a background worker rather
     * than the main thread.
     */
    fun positionOf(pageIndex: Int): ReadingPosition? = null

    /**
     * Resolves each of [positions] to a page index under the current layout, in the same order,
     * with the semantics of [resolvePositionToken]: an offset exactly at a page boundary lands on
     * the page starting there, and one at or past its chapter's end lands on the chapter's last
     * page. An entry is null when its chapter does not exist or has no pages, and every entry is
     * null for a fixed-layout document.
     *
     * The answer is only valid until the next [relayout], so a caller re-resolves after every
     * re-pagination. Threading is as for [positionOf].
     */
    fun resolvePositions(positions: List<ReadingPosition>): List<Int?> = positions.map { null }

    /**
     * Re-paginates the document under [settings]. Returns false and does nothing for a fixed-layout
     * document.
     */
    fun relayout(settings: ReflowSettings): Boolean = false

    override fun close()
}

/** Bibliographic fields a document declares about itself. Blank values are normalized to null. */
data class DocumentMetadata(
    val title: String? = null,
    val author: String? = null,
    val producer: String? = null
) {
    init {
        require(title == null || title.isNotBlank())
        require(author == null || author.isNotBlank())
        require(producer == null || producer.isNotBlank())
    }

    companion object {
        val NONE = DocumentMetadata()
    }
}
interface DisplayList : Closeable {
    fun render(spec: RenderSpec, cancellationSignal: CancellationSignal = CancellationSignal { false }): Raster
    override fun close()
}
