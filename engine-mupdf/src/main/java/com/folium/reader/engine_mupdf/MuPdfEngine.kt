package com.folium.reader.engine_mupdf

import com.artifex.mupdf.fitz.AbortException
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.DisplayList as NativeDisplayList
import com.artifex.mupdf.fitz.DisplayListDevice
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Location
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline as NativeOutline
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.TryLaterException
import com.folium.reader.core.library.BookFormat
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.DocumentMetadata
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.PdfEngine
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.Raster
import com.folium.reader.core.pdf.ReadingPosition
import com.folium.reader.core.pdf.ReadingPositionToken
import com.folium.reader.core.pdf.ReadingPositionTokens
import com.folium.reader.core.pdf.ReflowLayoutBox
import com.folium.reader.core.pdf.ReflowSettings
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextEngineVersion
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.CancellationException

/**
 * Scopes a reading position token to this exact engine build. Hand-bumped on an engine upgrade so
 * a token minted under one build can never resolve under a different one, where a bookmark's
 * meaning is not guaranteed to be the same.
 */
private const val POSITION_SCOPE = "mupdf-1.28.0-chapter-offset-v1"

class MuPdfEngine : PdfEngine {
    override val textEngineVersion = TextEngineVersion("mupdf-1.28.0-structured-text-v1")
    private val engineLock = Any()
    private var sessionActive = false

    override fun open(source: PdfSource): PdfDocument {
        if (BookFormat.forPath(source.path) == null) throw PdfException(PdfFailure.Unsupported)
        synchronized(engineLock) {
            if (sessionActive) throw PdfException(PdfFailure.Resource(retryable = true))
            sessionActive = true
        }
        try {
            return initializeMuPdfSession(
                acquire = { Document.openDocument(source.path).also { MuPdfNativeOwnerTracker.documentCreated() } },
                needsPassword = { it.needsPassword() },
                createDocument = {
                    layOutIfReflowable(it)
                    MuPdfDocument(it, MuPdfSessionOwner { releaseSession() })
                },
                destroy = {
                    try {
                        it.destroy()
                    } finally {
                        MuPdfNativeOwnerTracker.documentDestroyed()
                    }
                },
                releaseSession = ::releaseSession
            )
        } catch (error: RuntimeException) {
            throw translateOpenFailure(error)
        }
    }

    private fun releaseSession() = synchronized(engineLock) { sessionActive = false }

    /**
     * Lays a reflowable document out against the frozen [ReflowLayoutBox.BOX_1] before it is ever
     * paginated. A fixed-layout document has no notion of layout at all, so this is a no-op for
     * every PDF; an EPUB has no pages until laid out, and every stored reading position assumes
     * this exact box.
     */
    private fun layOutIfReflowable(document: Document) {
        if (!document.isReflowable) return
        val box = ReflowLayoutBox.BOX_1
        document.layout(box.widthPoints, box.heightPoints, box.emPoints)
    }
}

internal object MuPdfNativeOwnerTracker {
    data class Snapshot(
        val documents: Int,
        val pages: Int,
        val structuredTexts: Int,
        val pixmaps: Int,
        val displayLists: Int,
        val cookies: Int,
        val fonts: Int,
        val images: Int
    )

    private val lock = Any()
    private var documents = 0
    private var pages = 0
    private var structuredTexts = 0
    private var pixmaps = 0
    private var displayLists = 0
    private var cookies = 0
    private var fonts = 0
    private var images = 0
    private var failAfterTextExtraction = false
    private var beforeRender: (() -> Unit)? = null
    private var beforeDisplayListBuild: (() -> Unit)? = null

    fun documentCreated() = synchronized(lock) { documents++ }
    fun documentDestroyed() = synchronized(lock) { documents-- }
    fun pageCreated() = synchronized(lock) { pages++ }
    fun pageDestroyed() = synchronized(lock) { pages-- }
    fun structuredTextCreated() = synchronized(lock) { structuredTexts++ }
    fun structuredTextDestroyed() = synchronized(lock) { structuredTexts-- }
    fun pixmapCreated() = synchronized(lock) { pixmaps++ }
    fun pixmapDestroyed() = synchronized(lock) { pixmaps-- }
    fun displayListCreated() = synchronized(lock) { displayLists++ }
    fun displayListDestroyed() = synchronized(lock) { displayLists-- }
    fun cookieCreated() = synchronized(lock) { cookies++ }
    fun cookieDestroyed() = synchronized(lock) { cookies-- }
    fun fontCreated() = synchronized(lock) { fonts++ }
    fun fontDestroyed() = synchronized(lock) { fonts-- }
    fun imageCreated() = synchronized(lock) { images++ }
    fun imageDestroyed() = synchronized(lock) { images-- }
    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(documents, pages, structuredTexts, pixmaps, displayLists, cookies, fonts, images)
    }
    fun setBeforeRenderProbe(probe: (() -> Unit)?) = synchronized(lock) { beforeRender = probe }
    fun beforeRender() = synchronized(lock) { beforeRender }?.invoke()
    fun setBeforeDisplayListBuildProbe(probe: (() -> Unit)?) = synchronized(lock) { beforeDisplayListBuild = probe }
    fun beforeDisplayListBuild() = synchronized(lock) { beforeDisplayListBuild }?.invoke()
    fun failAfterNextTextExtraction() = synchronized(lock) { failAfterTextExtraction = true }
    fun clearFailure() = synchronized(lock) { failAfterTextExtraction = false }
    fun failAfterTextExtractionIfRequested() = synchronized(lock) {
        if (failAfterTextExtraction) {
            failAfterTextExtraction = false
            throw IllegalStateException("forced text extraction failure")
        }
    }
}

internal class MuPdfSessionOwner(private val onClose: () -> Unit = {}) {
    private val lock = ReentrantLock()
    private var closed = false

    /**
     * [operation] names which caller is waiting for, and then holding, the document's single lock —
     * see [MuPdfDocument.nativeCall] and [MuPdfDisplayList.render] for the labels this is called
     * with. Every operation on this document serializes through the same [lock], so the wait/hold
     * pair traced here is what shows one operation delaying another rather than merely being slow
     * on its own.
     */
    fun <T> use(operation: String, block: () -> T): T {
        traced({ "folium:engine:wait:$operation" }) { lock.lock() }
        try {
            if (closed) throw PdfException(PdfFailure.Closed)
            return traced({ "folium:engine:hold:$operation" }, block)
        } finally {
            lock.unlock()
        }
    }

    fun close(operation: String, cleanup: () -> Unit) {
        traced({ "folium:engine:wait:$operation" }) { lock.lock() }
        try {
            if (!closed) {
                closed = true
                try {
                    traced({ "folium:engine:hold:$operation" }, cleanup)
                } finally {
                    onClose()
                }
            }
        } finally {
            lock.unlock()
        }
    }

    fun serialized(operation: String, block: () -> Unit) {
        traced({ "folium:engine:wait:$operation" }) { lock.lock() }
        try {
            traced({ "folium:engine:hold:$operation" }, block)
        } finally {
            lock.unlock()
        }
    }
}

internal fun <T : Any, R> initializeMuPdfSession(
    acquire: () -> T,
    needsPassword: (T) -> Boolean,
    createDocument: (T) -> R,
    destroy: (T) -> Unit,
    releaseSession: () -> Unit
): R {
    var document: T? = null
    var transferred = false
    var failure: Throwable? = null
    try {
        document = acquire()
        if (needsPassword(document)) throw PdfException(PdfFailure.PasswordRequired)
        return createDocument(document).also { transferred = true }
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        if (!transferred) {
            var cleanupFailure: Throwable? = null
            try {
                document?.let(destroy)
            } catch (error: Throwable) {
                cleanupFailure = error
            }
            try {
                releaseSession()
            } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
            }
            if (failure != null && cleanupFailure != null) failure.addSuppressed(cleanupFailure)
            if (failure == null && cleanupFailure != null) throw cleanupFailure
        }
    }
}

/**
 * How many native display lists [MuPdfDocument] keeps built across renders. The reader keeps the
 * current page and three pages to either side rendered, and it renders the neighbours after the
 * current page, so a smaller bound lets the prefetch evict the very list the next pan or zoom of
 * the page on screen needs. The fitz Java API exposes no byte size for a display list, so the bound
 * is a fixed entry count rather than a memory budget.
 */
internal const val RETAINED_DISPLAY_LIST_CAPACITY = 8

private class MuPdfDocument(
    private var native: Document?,
    private val owner: MuPdfSessionOwner
) : PdfDocument {
    private val displayLists = mutableSetOf<MuPdfDisplayList>()
    private val retainedDisplayLists = LruEvictionCache<Int, NativeDisplayList>(
        RETAINED_DISPLAY_LIST_CAPACITY,
        ::evictRetainedDisplayList
    )
    private val annotationsFiltered = HashSet<Int>()

    override val pageCount: Int get() = nativeCall("pageCount") { document().countPages() }

    override fun pageInfo(index: Int): PageInfo = nativeCall("pageInfo") {
        val document = document()
        val page = loadPage(document, index)
        MuPdfNativeOwnerTracker.pageCreated()
        try {
            val bounds = page.bounds
            PageInfo(index, bounds.x1 - bounds.x0, bounds.y1 - bounds.y0, pageRotation(document, index))
        } finally {
            try {
                page.destroy()
            } finally {
                MuPdfNativeOwnerTracker.pageDestroyed()
            }
        }
    }

    /**
     * Runs [beforeRender], a retained-display-list lookup or build, and the rasterization under
     * one acquisition of [owner]'s lock. Taken one at a time, each step would queue again behind
     * whatever else is using the document, and a page whose display list is already retained would
     * not be handed back until the raster step had won the lock once more on its own.
     *
     * Up to [RETAINED_DISPLAY_LIST_CAPACITY] display lists stay built across calls, keyed by page
     * index and evicted least-recently-used; a later render of the same page reuses the retained
     * list instead of rebuilding it, and a display list built on a miss is abortable, so a render
     * superseded before its display list finishes stops mid-build rather than completing it for
     * nobody. A build that is cancelled or fails is never retained.
     *
     * Every inner step still goes through [owner] and re-enters the same lock, so the per-step
     * `folium:engine:hold:*` sections are still emitted and their `wait` side is near zero.
     */
    override fun renderPage(
        index: Int,
        spec: RenderSpec,
        cancellationSignal: CancellationSignal,
        beforeRender: () -> Unit
    ): Raster = owner.use("render") {
        beforeRender()
        if (cancellationSignal.isCancelled()) throw PdfException(PdfFailure.Resource(retryable = true))

        val displayList = retrieveOrBuildRetainedDisplayList(index, cancellationSignal)
        traced({ "folium:render:raster:$index:${spec.width}x${spec.height}" }) {
            renderRetainedDisplayList(displayList, spec, cancellationSignal)
        }
    }

    private fun retrieveOrBuildRetainedDisplayList(index: Int, cancellationSignal: CancellationSignal): NativeDisplayList {
        retainedDisplayLists.get(index)?.let { cached ->
            return traced({ "folium:render:displaylist:hit:$index" }) { cached }
        }

        return traced({ "folium:render:displaylist:$index" }) {
            nativeCall("displaylist") { buildRetainedDisplayList(index, cancellationSignal) }
        }
    }

    private fun buildRetainedDisplayList(index: Int, cancellationSignal: CancellationSignal): NativeDisplayList {
        val page = loadPage(document(), index)
        MuPdfNativeOwnerTracker.pageCreated()
        try {
            val nativeDisplayList = buildAbortableDisplayList(page, cancellationSignal)
            retainedDisplayLists.put(index, nativeDisplayList)
            return nativeDisplayList
        } finally {
            try {
                page.destroy()
            } finally {
                MuPdfNativeOwnerTracker.pageDestroyed()
            }
        }
    }

    private fun renderRetainedDisplayList(
        nativeDisplayList: NativeDisplayList,
        spec: RenderSpec,
        cancellationSignal: CancellationSignal
    ): Raster = MuPdfDisplayList(nativeDisplayList, owner) {}.render(spec, cancellationSignal)

    private fun evictRetainedDisplayList(index: Int, native: NativeDisplayList) =
        traced({ "folium:render:displaylist:evict:$index" }) {
            native.destroy()
            MuPdfNativeOwnerTracker.displayListDestroyed()
        }

    override fun buildDisplayList(index: Int): DisplayList = nativeCall("displaylist") {
        val page = loadPage(document(), index)
        MuPdfNativeOwnerTracker.pageCreated()
        try {
            val nativeDisplayList = page.toDisplayList()
            MuPdfNativeOwnerTracker.displayListCreated()
            MuPdfDisplayList(nativeDisplayList, owner) { displayLists.remove(it) }.also { displayLists += it }
        } finally {
            try {
                page.destroy()
            } finally {
                MuPdfNativeOwnerTracker.pageDestroyed()
            }
        }
    }

    override fun extractText(index: Int): TextPage = typedTextExtraction {
        nativeCall("text") {
            val page = loadPage(document(), index)
            MuPdfNativeOwnerTracker.pageCreated()
            try {
                val text: StructuredText = page.toStructuredText()
                MuPdfNativeOwnerTracker.structuredTextCreated()
                try {
                    extractNativeText(text, page.bounds).also { MuPdfNativeOwnerTracker.failAfterTextExtractionIfRequested() }
                } finally {
                    try {
                        text.destroy()
                    } finally {
                        MuPdfNativeOwnerTracker.structuredTextDestroyed()
                    }
                }
            } finally {
                try {
                    page.destroy()
                } finally {
                    MuPdfNativeOwnerTracker.pageDestroyed()
                }
            }
        }
    }

    override fun outline(): List<OutlineEntry> = nativeCall("outline") {
        val document = document()
        document.loadOutline()?.let { toOutlineEntries(document, it) } ?: emptyList()
    }

    override val reflowable: Boolean get() = nativeCall("reflowable") { document().isReflowable }

    /**
     * Mints a token out of the chapter [pageIndex] belongs to and how far into that chapter's text
     * [pageIndex] starts, measured in extracted characters. The chapter's own start resolves exactly
     * The chapter and the offset are both facts about the file rather than about a layout, so the
     * pair names the same words however the book is later laid out.
     */
    override fun makePositionToken(pageIndex: Int): ReadingPositionToken? = nativeCall("positionToken") {
        val document = document()
        if (!document.isReflowable) return@nativeCall null

        val location = document.locationFromPageNumber(pageIndex)
        val offset = (0 until location.page).sumOf { pageInChapter ->
            val page = document.pageNumberFromLocation(Location(location.chapter, pageInChapter))
            extractedTextLength(document, page)
        }

        ReadingPositionTokens.mintPosition(ReadingPosition(location.chapter, offset), POSITION_SCOPE)
    }

    /**
     * Resolves [token] by walking its stored chapter's pages under the document's current layout,
     * accumulating extracted text until it passes the stored character offset.
     *
     * The engine's own bookmark is not consulted, and deliberately so: a device spike measured that
     * applying a stylesheet destroys every bookmark already minted in the session, while the stored
     * chapter and offset survive it. Walking from the chapter's first page rather than from a
     * remembered one keeps the answer the same however the book was last laid out.
     */
    override fun resolvePositionToken(token: ReadingPositionToken): Int? = nativeCall("resolvePositionToken") {
        val document = document()
        val position = ReadingPositionTokens.parsePosition(token, POSITION_SCOPE) ?: return@nativeCall null

        val chapterPageCount = try {
            document.countPages(position.chapterIndex)
        } catch (error: RuntimeException) {
            return@nativeCall null
        }
        if (chapterPageCount <= 0) return@nativeCall null

        var consumed = 0
        var resolvedPage = document.pageNumberFromLocation(Location(position.chapterIndex, 0))
        for (pageInChapter in 0 until chapterPageCount) {
            val page = document.pageNumberFromLocation(Location(position.chapterIndex, pageInChapter))
            resolvedPage = page
            consumed += extractedTextLength(document, page)
            if (consumed > position.characterOffset) break
        }

        resolvedPage.coerceIn(0, document.countPages() - 1)
    }

    /**
     * Re-paginates the document under [settings]. Every display list built before this call is
     * destroyed first, so a handle held across a relayout throws [PdfException] rather than
     * rendering against a document that has moved out from under it.
     *
     * [ReflowSettings.userCss] is only applied when it is non-empty. Measured on-device: calling
     */
    override fun relayout(settings: ReflowSettings): Boolean = nativeCall("relayout") {
        val document = document()
        if (!document.isReflowable) return@nativeCall false

        displayLists.toList().forEach { it.closeNative() }
        displayLists.clear()
        retainedDisplayLists.clear()

        document.style(true, settings.userCss)
        document.layout(settings.box.widthPoints, settings.box.heightPoints, settings.box.emPoints)

        true
    }

    /**
     * Producers fill the info dictionary with whatever their template held, so a value is only
     * taken when it looks like a human wrote it: blanks and the handful of placeholder titles that
     * authoring tools leave behind are dropped rather than shown as the book's name.
     */
    override fun metadata(): DocumentMetadata = nativeCall("metadata") {
        val document = document()
        DocumentMetadata(
            title = document.usableMeta(Document.META_INFO_TITLE),
            author = document.usableMeta(Document.META_INFO_AUTHOR),
            producer = document.usableMeta(Document.META_INFO_PRODUCER)
        )
    }

    override fun close() = owner.close("close") {
        displayLists.toList().forEach { it.closeNative() }
        displayLists.clear()
        retainedDisplayLists.clear()
        native?.let {
            try {
                it.destroy()
            } finally {
                native = null
                MuPdfNativeOwnerTracker.documentDestroyed()
            }
        }
    }

    private fun document(): Document = native ?: throw PdfException(PdfFailure.Closed)

    /**
     * Loads [index], first giving [document] a chance to drop annotations that provably paint
     * nothing from that page's dictionary. MuPDF synthesizes an appearance stream for every
     * annotation lacking one on a page's first [Document.loadPage], and each synthesized appearance
     * reallocates the whole xref table, so an annotation with no visible effect either way is
     * cheaper to remove beforehand than to have MuPDF render invisibly on every load.
     */
    private fun loadPage(document: Document, index: Int): Page {
        ensureInvisibleAnnotationsDropped(document, index)
        return document.loadPage(index)
    }

    /**
     * Runs [dropInvisibleAnnotations] against [index] at most once for the life of this document.
     * [annotationsFiltered] is only ever touched from inside [owner]'s lock, which every caller of
     * [loadPage] already holds, so no separate synchronization guards it here.
     *
     * A failure while inspecting or rewriting the page's annotations leaves that page's dictionary
     * exactly as authored and never fails the page load itself; the page is still marked processed
     * so a document with unreadable annotation structure is not retried on every later load of the
     * same page.
     */
    private fun ensureInvisibleAnnotationsDropped(document: Document, index: Int) {
        if (!annotationsFiltered.add(index)) return

        val pdf = document as? PDFDocument ?: return
        try {
            dropInvisibleAnnotations(pdf, index)
        } catch (error: PdfException) {
            throw error
        } catch (error: RuntimeException) {
            // Leave the page dictionary exactly as authored.
        }
    }

    /**
     * Replaces [index]'s /Annots array with a copy that leaves out every annotation
     * [synthesizesNoVisibleAppearance] identifies as provably invisible, in the array's original
     * order. Nothing is written to the page dictionary when there is no /Annots array, or when
     * every annotation survives the filter.
     */
    private fun dropInvisibleAnnotations(pdf: PDFDocument, index: Int) = traced({ "folium:engine:annots:$index" }) {
        val page = pdf.findPage(index)
        try {
            val annots = page.get("Annots")
            try {
                if (annots.isNull || !annots.isArray) return@traced

                val kept = mutableListOf<PDFObject>()
                var dropped = false
                for (i in 0 until annots.size()) {
                    val annot = annots.get(i)
                    if (annot.appearanceTraits().synthesizesNoVisibleAppearance()) {
                        dropped = true
                        annot.destroy()
                    } else {
                        kept.add(annot)
                    }
                }

                if (!dropped) {
                    kept.forEach { it.destroy() }
                    return@traced
                }

                val replacement = pdf.newArray()
                try {
                    kept.forEach { replacement.push(it) }
                    page.put("Annots", replacement)
                } finally {
                    replacement.destroy()
                    kept.forEach { it.destroy() }
                }
            } finally {
                annots.destroy()
            }
        } finally {
            page.destroy()
        }
    }

    private fun extractedTextLength(document: Document, pageIndex: Int): Int {
        val page = loadPage(document, pageIndex)
        MuPdfNativeOwnerTracker.pageCreated()
        return try {
            val text = page.toStructuredText()
            MuPdfNativeOwnerTracker.structuredTextCreated()
            try {
                text.asText().length
            } finally {
                try {
                    text.destroy()
                } finally {
                    MuPdfNativeOwnerTracker.structuredTextDestroyed()
                }
            }
        } finally {
            try {
                page.destroy()
            } finally {
                MuPdfNativeOwnerTracker.pageDestroyed()
            }
        }
    }

    private fun pageRotation(document: Document, index: Int): Int {
        val pdf = document as? PDFDocument ?: return 0
        val pageObject: PDFObject = pdf.findPage(index)
        try {
            val rotate = pageObject.getInheritable("Rotate")
            try {
                return ((rotate.asInteger() % 360) + 360) % 360
            } finally {
                rotate.destroy()
            }
        } finally {
            pageObject.destroy()
        }
    }

    private fun <T> nativeCall(operation: String, block: () -> T): T = owner.use(operation) {
        try {
            block()
        } catch (error: RuntimeException) {
            throw translateNativeFailure(error)
        }
    }

    private fun toOutlineEntries(document: Document, nodes: Array<NativeOutline>, depth: Int = 0): List<OutlineEntry> {
        if (depth >= MAX_OUTLINE_DEPTH) return emptyList()
        return nodes.map { node ->
            OutlineEntry(
                title = node.title ?: "",
                pageIndex = resolvePageIndex(document, node),
                children = node.down?.let { toOutlineEntries(document, it, depth + 1) } ?: emptyList()
            )
        }
    }

    private fun resolvePageIndex(document: Document, node: NativeOutline): Int? {
        if (node.uri == null) return null
        return try {
            document.pageNumberFromLocation(document.resolveLink(node)).takeIf { it >= 0 }
        } catch (error: RuntimeException) {
            null
        }
    }
}

internal fun <T> typedTextExtraction(block: () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: PdfException) {
    throw error
} catch (error: RuntimeException) {
    throw PdfException(PdfFailure.TextExtraction, error)
}

private const val MAX_OUTLINE_DEPTH = 32

/**
 * Sole owner of the fitz [Cookie] belonging to one in-flight render.
 *
 * A [Cookie] is a native owner, not a value type: it carries a pointer and must be destroyed
 * exactly once, like every other handle [MuPdfNativeOwnerTracker] counts. It is also the one native
 * object here that is deliberately touched from two threads — the render thread runs with it while
 * an unrelated thread aborts through it — so its lifetime cannot be left to the render thread alone.
 *
 * The discipline: the cookie is created and destroyed on the render thread, [destroy] in a `finally`
 * around the render it belongs to; every access from any thread goes through this monitor; and
 * [destroy] clears the reference before releasing the monitor. An aborter therefore either wins the
 * monitor and touches a cookie that is still alive — holding [destroy] off for the duration of that
 * native call — or arrives after and finds null, which is a no-op. There is no window in which a
 * caller can reach a destroyed pointer.
 *
 * [native] hands the raw cookie to the render thread, which then runs without holding the monitor:
 * that is safe because destruction happens later on that very thread, and holding the monitor across
 * the render would block precisely the abort this class exists to allow.
 *
 * The `synchronized(lock)` in [abort] and [destroy] is load-bearing for native memory safety and is
 * NOT guarded by any test: hoisting the cookie reference out of that block is a real use-after-free
 * (abort reads a non-null pointer, destroy zeroes and frees it, abort then writes into the freed
 * block), but the window is a handful of instructions and no timing-based test can reliably land
 * inside it. Any future change to this monitor — reordering, removing, or narrowing it — must be
 * reviewed by hand; the test suite cannot catch a regression here.
 */
private class RenderCookie {
    private val lock = Any()
    private var cookie: Cookie? = Cookie().also { MuPdfNativeOwnerTracker.cookieCreated() }

    fun native(): Cookie? = synchronized(lock) { cookie }

    fun abort() = synchronized(lock) { cookie?.abort() }

    fun destroy() = synchronized(lock) {
        cookie?.let { live ->
            try {
                live.destroy()
            } finally {
                cookie = null
                MuPdfNativeOwnerTracker.cookieDestroyed()
            }
        }
    }
}

/**
 * Turns the engine's poll-shaped cancellation contract into the push an in-flight render needs.
 *
 * [CancellationSignal] is a predicate: nothing calls into the engine when a request is superseded,
 * so the render thread can only notice between native calls — and the native call is the whole
 * cost. One daemon thread polls the signals of every render currently running and aborts through the
 * owning [RenderCookie], which is what the render thread cannot do for itself while it is inside
 * fitz. This is the same signal the render path already checks, not a second cancellation channel.
 *
 * The thread is created on the first render and then parks on this monitor whenever nothing is
 * running, so an idle process pays nothing for it. It is a daemon so it can never hold the process
 * up, and it holds no lock while asking a signal or aborting a cookie, so it cannot be part of a
 * cycle with either the caller's own lock or the render thread's.
 */
private object RenderAbortWatcher {
    private const val POLL_INTERVAL_MILLIS = 8L

    private val lock = Object()
    private val watched = mutableMapOf<RenderCookie, CancellationSignal>()
    private var watcher: Thread? = null

    fun <T> whileWatching(cookie: RenderCookie, cancellationSignal: CancellationSignal, block: () -> T): T {
        register(cookie, cancellationSignal)
        try {
            return block()
        } finally {
            synchronized(lock) { watched.remove(cookie) }
        }
    }

    private fun register(cookie: RenderCookie, cancellationSignal: CancellationSignal) = synchronized(lock) {
        watched[cookie] = cancellationSignal
        if (watcher == null) {
            watcher = Thread(::watch, "mupdf-render-abort").apply {
                isDaemon = true
                start()
            }
        }
        lock.notifyAll()
    }

    /**
     * Runs until interrupted. Every exit path — normal interruption or an unexpected throw that
     * somehow escapes [abortIfCancelled] — clears [watcher] under [lock] before returning, so
     * [register] always sees a dead watcher as `null` and starts a fresh thread rather than leaving
     * every later render silently uninterruptible.
     */
    private fun watch() {
        try {
            while (true) {
                val active = synchronized(lock) {
                    while (watched.isEmpty()) lock.wait()
                    watched.entries.map { it.key to it.value }
                }

                active.forEach { (cookie, cancellationSignal) -> abortIfCancelled(cookie, cancellationSignal) }

                Thread.sleep(POLL_INTERVAL_MILLIS)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            synchronized(lock) { watcher = null }
        }
    }

    /**
     * A signal supplied by a caller is arbitrary code and may throw anything, including an [Error]
     * (not just a [RuntimeException]). Catching [Throwable] means a failing signal only loses the
     * abort it was asked about for this one poll, instead of unwinding [watch]'s loop and reaching
     * Android's default uncaught-exception handler, which terminates the process.
     */
    private fun abortIfCancelled(cookie: RenderCookie, cancellationSignal: CancellationSignal) {
        try {
            if (cancellationSignal.isCancelled()) cookie.abort()
        } catch (error: Throwable) {
            return
        }
    }
}

/**
 * Builds a native display list out of [page] the same way [Page.toDisplayList] does — recording
 * [Page.run]'s contents, annotations and widgets into a fresh list — but through a [Cookie]
 * watched by [RenderAbortWatcher], so a superseded build stops where it is instead of running to
 * completion for a page nobody wants any more.
 *
 * A cancelled or failing build never returns a live list: [nativeDisplayList] is destroyed before
 * this function exits unless the build genuinely completed.
 */
private fun buildAbortableDisplayList(page: Page, cancellationSignal: CancellationSignal): NativeDisplayList {
    if (cancellationSignal.isCancelled()) throw PdfException(PdfFailure.Resource(retryable = true))

    val nativeDisplayList = NativeDisplayList(page.bounds)
    var completed = false
    try {
        MuPdfNativeOwnerTracker.beforeDisplayListBuild()
        if (cancellationSignal.isCancelled()) throw PdfException(PdfFailure.Resource(retryable = true))

        runPageIntoDisplayList(page, nativeDisplayList, cancellationSignal)

        if (cancellationSignal.isCancelled()) throw PdfException(PdfFailure.Resource(retryable = true))

        MuPdfNativeOwnerTracker.displayListCreated()
        completed = true
        return nativeDisplayList
    } finally {
        if (!completed) nativeDisplayList.destroy()
    }
}

private fun runPageIntoDisplayList(page: Page, nativeDisplayList: NativeDisplayList, cancellationSignal: CancellationSignal) {
    val cookie = RenderCookie()
    try {
        val device = DisplayListDevice(nativeDisplayList)
        try {
            RenderAbortWatcher.whileWatching(cookie, cancellationSignal) {
                page.run(device, Matrix.Identity(), cookie.native())
            }
        } finally {
            closeAndDestroy(device, cancellationSignal)
        }
    } finally {
        cookie.destroy()
    }
}

/**
 * A run that was aborted part-way leaves the device's clip and group stack unbalanced, and fitz
 * refuses to close such a device: `items left on stack in draw device`. That refusal says nothing
 * about a render nobody is waiting for any more, so it is dropped when [cancellationSignal] has
 * fired and the caller reports the cancellation instead. A device that fails to close after a run
 * that was not cancelled is a real failure and still surfaces. The device is destroyed either way.
 */
private fun closeAndDestroy(device: com.artifex.mupdf.fitz.Device, cancellationSignal: CancellationSignal) {
    try {
        device.close()
    } catch (error: RuntimeException) {
        if (!cancellationSignal.isCancelled()) throw error
    } finally {
        device.destroy()
    }
}

private class MuPdfDisplayList(
    private var native: NativeDisplayList?,
    private val owner: MuPdfSessionOwner,
    private val onClose: (MuPdfDisplayList) -> Unit
) : DisplayList {
    override fun render(spec: RenderSpec, cancellationSignal: CancellationSignal): Raster {
        if (cancellationSignal.isCancelled()) throw PdfException(PdfFailure.Resource(retryable = true))
        return owner.use("raster") {
            if (cancellationSignal.isCancelled()) throw PdfException(PdfFailure.Resource(retryable = true))
            MuPdfNativeOwnerTracker.beforeRender()
            if (cancellationSignal.isCancelled()) throw PdfException(PdfFailure.Resource(retryable = true))
            try {
                rasterize(native ?: throw PdfException(PdfFailure.Closed), spec, cancellationSignal)
            } catch (error: RuntimeException) {
                throw translateNativeFailure(error)
            }
        }
    }

    private fun rasterize(displayList: NativeDisplayList, spec: RenderSpec, cancellationSignal: CancellationSignal): Raster {
        val source = normalizedRegion(displayList.bounds, spec)
        val scaleX = spec.width / (source.x1 - source.x0)
        val scaleY = spec.height / (source.y1 - source.y0)
        val matrix = Matrix(scaleX, 0f, 0f, scaleY, -source.x0 * scaleX, -source.y0 * scaleY)

        val pixmap = Pixmap(ColorSpace.DeviceRGB, spec.width, spec.height, true)
        MuPdfNativeOwnerTracker.pixmapCreated()
        try {
            pixmap.clear(0xff)
            runAbortably(displayList, pixmap, matrix, spec, cancellationSignal)

            if (cancellationSignal.isCancelled()) throw PdfException(PdfFailure.Resource(retryable = true))
            return Raster(pixmap.width, pixmap.height, pixmap.samples)
        } finally {
            try {
                pixmap.destroy()
            } finally {
                MuPdfNativeOwnerTracker.pixmapDestroyed()
            }
        }
    }

    /**
     * Runs the display list under a fitz [Cookie] so a render that has been superseded stops where it
     * is instead of rasterizing to completion.
     *
     * This is the whole difference between a cancellation that is honoured and one that is merely
     * recorded: the document lock is held for the entire call below, so a full-page prefetch that
     * nobody wants any more delays the visible render of the page the reader just turned to by
     * however long it takes to finish. [RenderAbortWatcher] polls the same [cancellationSignal] this
     * function already checks around the call, and aborts through it.
     */
    private fun runAbortably(
        displayList: NativeDisplayList,
        pixmap: Pixmap,
        matrix: Matrix,
        spec: RenderSpec,
        cancellationSignal: CancellationSignal
    ) {
        val cookie = RenderCookie()
        try {
            val device = DrawDevice(pixmap)
            try {
                RenderAbortWatcher.whileWatching(cookie, cancellationSignal) {
                    displayList.run(device, matrix, Rect(0f, 0f, spec.width.toFloat(), spec.height.toFloat()), cookie.native())
                }
            } finally {
                closeAndDestroy(device, cancellationSignal)
            }
        } finally {
            cookie.destroy()
        }
    }

    override fun close() = owner.serialized("displayListClose") { closeNative() }

    internal fun closeNative() {
        if (native != null) {
            try {
                native?.destroy()
            } finally {
                native = null
                MuPdfNativeOwnerTracker.displayListDestroyed()
            }
        }
        onClose(this)
    }
}

private fun normalizedRegion(bounds: Rect, spec: RenderSpec): Rect {
    val width = bounds.x1 - bounds.x0
    val height = bounds.y1 - bounds.y0
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) {
        throw PdfException(PdfFailure.Resource(retryable = false))
    }
    return Rect(
        bounds.x0 + width * spec.pageSpace.left,
        bounds.y0 + height * spec.pageSpace.top,
        bounds.x0 + width * spec.pageSpace.right,
        bounds.y0 + height * spec.pageSpace.bottom
    )
}

internal fun translateOpenFailure(error: RuntimeException): PdfException {
    if (error is PdfException) return error
    if (error is AbortException || error is TryLaterException) return PdfException(PdfFailure.Resource(retryable = true))
    if (error.message.isRecognizedCorruptDocumentMessage()) return PdfException(PdfFailure.Corrupt)
    throw error
}

internal fun translateNativeFailure(error: RuntimeException): PdfException {
    if (error is PdfException) return error
    if (error is AbortException || error is TryLaterException) return PdfException(PdfFailure.Resource(retryable = true))
    if (error.message.isRecognizedResourceMessage()) return PdfException(PdfFailure.Resource(retryable = false))
    throw error
}

private fun String?.isRecognizedCorruptDocumentMessage(): Boolean {
    val message = this?.lowercase() ?: return false
    return listOf(
        "cannot recognize version marker",
        "cannot find startxref",
        "cannot parse xref",
        "cannot load xref",
        "no objects found",
        "broken xref",
        "invalid xref",
        "cannot find entry"
    ).any(message::contains)
}

private fun String?.isRecognizedResourceMessage(): Boolean {
    val message = this?.lowercase() ?: return false
    return message.contains("out of memory") || message.contains("cannot allocate")
}

/**
 * Metadata a producer left behind rather than a person typed.
 *
 * These are the strings authoring tools write into an untouched info dictionary. A file whose title
 * is its own filename is just as uninformative, and is rejected by the caller that knows the name.
 */
private val PLACEHOLDER_META = setOf(
    "untitled", "unnamed", "document", "documento", "sin titulo", "sin título",
    "microsoft word", "powerpoint presentation", "pdf document", "none", "n/a", "-", "--"
)

private fun Document.usableMeta(key: String): String? {
    val value = runCatching { getMetaData(key) }.getOrNull()?.trim() ?: return null
    if (value.isEmpty()) return null
    if (value.lowercase() in PLACEHOLDER_META) return null
    if (value.startsWith("/") || value.contains("\\")) return null
    return value
}

/**
 * The handful of facts about an annotation that decide whether MuPDF has anything to draw for it,
 * lifted out of [PDFObject] so [synthesizesNoVisibleAppearance] is a pure function a JVM test can
 * exercise without the native library.
 */
internal data class AnnotationAppearanceTraits(
    val subtype: String?,
    val hasAppearance: Boolean,
    val hasInteriorColor: Boolean,
    val borderWidth: Float
)

/**
 * True for a Square or Circle annotation that is provably invisible: no appearance stream for
 * MuPDF to have synthesized in the first place, no interior color to fill, and a border width of
 * zero to stroke. AutoCAD writes annotations exactly like this to keep SHX text searchable, and
 * MuPDF still pays to synthesize an appearance for one on the page's first load regardless.
 */
internal fun AnnotationAppearanceTraits.synthesizesNoVisibleAppearance(): Boolean =
    (subtype == "Square" || subtype == "Circle") && !hasAppearance && !hasInteriorColor && borderWidth == 0f

private fun PDFObject.appearanceTraits(): AnnotationAppearanceTraits {
    val subtypeObject = get("Subtype")
    val subtype = try {
        if (subtypeObject.isNull) null else subtypeObject.asName()
    } finally {
        subtypeObject.destroy()
    }

    val appearance = get("AP")
    val hasAppearance = try {
        !appearance.isNull
    } finally {
        appearance.destroy()
    }

    val interiorColor = get("IC")
    val hasInteriorColor = try {
        !interiorColor.isNull
    } finally {
        interiorColor.destroy()
    }

    return AnnotationAppearanceTraits(subtype, hasAppearance, hasInteriorColor, borderWidth())
}

/**
 * The stroke width MuPDF would render this annotation's border with: /BS /W when a border style
 * dictionary is present, else the third element of the legacy /Border array, else the PDF-spec
 * default width of 1 — which is why an annotation carrying neither is never treated as having a
 * zero-width border.
 */
private fun PDFObject.borderWidth(): Float {
    val borderStyle = get("BS")
    try {
        if (!borderStyle.isNull) {
            val width = borderStyle.get("W")
            try {
                if (!width.isNull) return width.asFloat()
            } finally {
                width.destroy()
            }
        }
    } finally {
        borderStyle.destroy()
    }

    val border = get("Border")
    try {
        if (border.isArray && border.size() >= 3) {
            val width = border.get(2)
            try {
                return width.asFloat()
            } finally {
                width.destroy()
            }
        }
    } finally {
        border.destroy()
    }

    return 1f
}
