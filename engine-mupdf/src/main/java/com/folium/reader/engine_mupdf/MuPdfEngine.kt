package com.folium.reader.engine_mupdf

import com.artifex.mupdf.fitz.AbortException
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.DisplayList as NativeDisplayList
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.TryLaterException
import com.folium.reader.core.pdf.CancellationSignal
import com.folium.reader.core.pdf.DisplayList
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageInfo
import com.folium.reader.core.pdf.PdfDocument
import com.folium.reader.core.pdf.PdfEngine
import com.folium.reader.core.pdf.PdfException
import com.folium.reader.core.pdf.PdfFailure
import com.folium.reader.core.pdf.PdfSource
import com.folium.reader.core.pdf.Raster
import com.folium.reader.core.pdf.RenderSpec
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MuPdfEngine : PdfEngine {
    private val engineLock = Any()
    private var sessionActive = false

    override fun open(source: PdfSource): PdfDocument {
        if (!source.path.endsWith(".pdf", ignoreCase = true)) throw PdfException(PdfFailure.Unsupported)
        synchronized(engineLock) {
            if (sessionActive) throw PdfException(PdfFailure.Resource(retryable = true))
            sessionActive = true
        }
        try {
            return initializeMuPdfSession(
                acquire = { Document.openDocument(source.path).also { MuPdfNativeOwnerTracker.documentCreated() } },
                needsPassword = { it.needsPassword() },
                createDocument = { MuPdfDocument(it, MuPdfSessionOwner { releaseSession() }) },
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
}

internal object MuPdfNativeOwnerTracker {
    data class Snapshot(val documents: Int, val pages: Int, val structuredTexts: Int, val pixmaps: Int, val displayLists: Int)

    private val lock = Any()
    private var documents = 0
    private var pages = 0
    private var structuredTexts = 0
    private var pixmaps = 0
    private var displayLists = 0
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
    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(documents, pages, structuredTexts, pixmaps, displayLists) }
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

    override fun extractText(index: Int): String = nativeCall {
        val page = document().loadPage(index)
        MuPdfNativeOwnerTracker.pageCreated()
        try {
            val text: StructuredText = page.toStructuredText()
            MuPdfNativeOwnerTracker.structuredTextCreated()
            try {
                text.asText().also { MuPdfNativeOwnerTracker.failAfterTextExtractionIfRequested() }
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

    /** Extraction lands with outline navigation; the type reports "no table of contents" until then. */
    override fun outline(): List<OutlineEntry> = emptyList()

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
                val displayList = native ?: throw PdfException(PdfFailure.Closed)
                val bounds = displayList.bounds
                val source = normalizedRegion(bounds, spec)
                val scaleX = spec.width / (source.x1 - source.x0)
                val scaleY = spec.height / (source.y1 - source.y0)
                val matrix = Matrix(scaleX, 0f, 0f, scaleY, -source.x0 * scaleX, -source.y0 * scaleY)
                val pixmap = Pixmap(ColorSpace.DeviceRGB, spec.width, spec.height, true)
                MuPdfNativeOwnerTracker.pixmapCreated()
                try {
                    pixmap.clear(0xff)
                    val device = DrawDevice(pixmap)
                    try {
                        displayList.run(device, matrix, Rect(0f, 0f, spec.width.toFloat(), spec.height.toFloat()), null)
                    } finally {
                        device.close()
                        device.destroy()
                    }
                    if (cancellationSignal.isCancelled()) throw PdfException(PdfFailure.Resource(retryable = true))
                    Raster(pixmap.width, pixmap.height, pixmap.samples)
                } finally {
                    try {
                        pixmap.destroy()
                    } finally {
                        MuPdfNativeOwnerTracker.pixmapDestroyed()
                    }
                }
            } catch (error: RuntimeException) {
                throw translateNativeFailure(error)
            }
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
