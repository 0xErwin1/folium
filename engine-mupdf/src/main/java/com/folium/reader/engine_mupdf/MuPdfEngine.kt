package com.folium.reader.engine_mupdf

import com.artifex.mupdf.fitz.AbortException
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.DisplayList as NativeDisplayList
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DrawDevice
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
import com.folium.reader.core.pdf.ReflowLayoutBox
import com.folium.reader.core.pdf.RenderSpec
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextEngineVersion
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.CancellationException
import kotlin.concurrent.withLock

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

    fun <T> use(block: () -> T): T = lock.withLock {
        if (closed) throw PdfException(PdfFailure.Closed)
        block()
    }

    fun close(cleanup: () -> Unit) = lock.withLock {
        if (!closed) {
            closed = true
            try {
                cleanup()
            } finally {
                onClose()
            }
        }
    }

    fun serialized(block: () -> Unit) = lock.withLock(block)
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

private class MuPdfDocument(
    private var native: Document?,
    private val owner: MuPdfSessionOwner
) : PdfDocument {
    private val displayLists = mutableSetOf<MuPdfDisplayList>()

    override val pageCount: Int get() = nativeCall { document().countPages() }

    override fun pageInfo(index: Int): PageInfo = nativeCall {
        val document = document()
        val page = document.loadPage(index)
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

    override fun buildDisplayList(index: Int): DisplayList = nativeCall {
        val page = document().loadPage(index)
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
        nativeCall {
            val page = document().loadPage(index)
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

    override fun outline(): List<OutlineEntry> = nativeCall {
        val document = document()
        document.loadOutline()?.let { toOutlineEntries(document, it) } ?: emptyList()
    }

    /**
     * Producers fill the info dictionary with whatever their template held, so a value is only
     * taken when it looks like a human wrote it: blanks and the handful of placeholder titles that
     * authoring tools leave behind are dropped rather than shown as the book's name.
     */
    override fun metadata(): DocumentMetadata = nativeCall {
        val document = document()
        DocumentMetadata(
            title = document.usableMeta(Document.META_INFO_TITLE),
            author = document.usableMeta(Document.META_INFO_AUTHOR),
            producer = document.usableMeta(Document.META_INFO_PRODUCER)
        )
    }

    override fun close() = owner.close {
        displayLists.toList().forEach { it.closeNative() }
        displayLists.clear()
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

    private fun <T> nativeCall(block: () -> T): T = owner.use {
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

private class MuPdfDisplayList(
    private var native: NativeDisplayList?,
    private val owner: MuPdfSessionOwner,
    private val onClose: (MuPdfDisplayList) -> Unit
) : DisplayList {
    override fun render(spec: RenderSpec, cancellationSignal: CancellationSignal): Raster {
        if (cancellationSignal.isCancelled()) throw PdfException(PdfFailure.Resource(retryable = true))
        return owner.use {
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
                device.close()
                device.destroy()
            }
        } finally {
            cookie.destroy()
        }
    }

    override fun close() = owner.serialized { closeNative() }

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
        "invalid xref"
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
