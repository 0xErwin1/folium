package com.folium.reader.core.pdf

import java.io.Closeable

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
    data class Resource(val retryable: Boolean) : PdfFailure()
}

class PdfException(val failure: PdfFailure) : RuntimeException(failure.javaClass.simpleName)

interface PdfEngine { fun open(source: PdfSource): PdfDocument }
interface PdfDocument : Closeable {
    val pageCount: Int
    fun pageInfo(index: Int): PageInfo
    fun buildDisplayList(index: Int): DisplayList
    fun extractText(index: Int): String
    override fun close()
}
interface DisplayList : Closeable {
    fun render(spec: RenderSpec, cancellationSignal: CancellationSignal = CancellationSignal { false }): Raster
    override fun close()
}
