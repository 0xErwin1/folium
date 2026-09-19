package com.folium.reader.core.preview

/**
 * Downscales a whole-page RGBA raster into a small [PagePreview], one page at a time, with no engine
 * involvement and no per-pixel allocation.
 *
 * The source is assumed to carry four bytes per pixel in row-major, top-to-bottom order with red,
 * green, blue then alpha in that order — the same layout [com.folium.reader.core.diskcache] rasters
 * and [com.folium.reader.core.pdf.Raster] already use. Alpha is not read: a rasterized page is opaque.
 */
object PagePreviewScaler {

    /** The width every preview is scaled to, unless the source itself is narrower than this. */
    const val PREVIEW_WIDTH_PX: Int = 32

    /**
     * Produces a [PagePreview] for a [width]x[height] RGBA [source], scaled down by an area (box)
     * average so a thin line or a hairline rule survives as a grey smear rather than being skipped
     * entirely by a point sample landing between its pixels.
     *
     * The output is [PREVIEW_WIDTH_PX] wide, or [width] itself when the source is already narrower,
     * since there is nothing left to average away in that case. Height follows the source's aspect
     * ratio, rounded and floored at one so a page can never produce an empty preview.
     *
     * Horizontal and vertical box boundaries are computed independently from the exact source-to-
     * output ratio in each dimension, rather than reusing one scale factor for both, so the last box
     * in every row and column always ends exactly at the source's own edge regardless of how the
     * rounded output height divides into it — this is what keeps a source size that does not divide
     * evenly by the scale factor exact at the edges instead of quietly cropping or padding.
     */
    fun scale(source: ByteArray, width: Int, height: Int): PagePreview {
        require(width > 0 && height > 0) { "width and height must be positive, got width=$width height=$height" }
        require(source.size >= width.toLong() * height.toLong() * 4) {
            "source has ${source.size} bytes, need at least ${width.toLong() * height.toLong() * 4} for ${width}x$height RGBA"
        }

        val outputWidth = minOf(PREVIEW_WIDTH_PX, width)
        val scaleX = width.toDouble() / outputWidth
        val outputHeight = maxOf(1, Math.round(height / scaleX).toInt())
        val scaleY = height.toDouble() / outputHeight

        val pixels = ByteArray(outputWidth * outputHeight * PagePreviewPixelFormat.RGB_565.bytesPerPixel)

        for (outY in 0 until outputHeight) {
            val rowStart = boxStart(outY, scaleY)
            val rowEnd = boxEnd(outY, outputHeight, scaleY, height)

            for (outX in 0 until outputWidth) {
                val colStart = boxStart(outX, scaleX)
                val colEnd = boxEnd(outX, outputWidth, scaleX, width)

                var redSum = 0L
                var greenSum = 0L
                var blueSum = 0L
                var count = 0L

                for (srcY in rowStart until rowEnd) {
                    var srcOffset = (srcY.toLong() * width + colStart) * 4
                    for (srcX in colStart until colEnd) {
                        redSum += source[srcOffset.toInt()].toInt() and 0xFF
                        greenSum += source[(srcOffset + 1).toInt()].toInt() and 0xFF
                        blueSum += source[(srcOffset + 2).toInt()].toInt() and 0xFF
                        count++
                        srcOffset += 4
                    }
                }

                val red = (redSum / count).toInt()
                val green = (greenSum / count).toInt()
                val blue = (blueSum / count).toInt()
                val packed = ((red shr 3) shl 11) or ((green shr 2) shl 5) or (blue shr 3)

                val pixelOffset = (outY * outputWidth + outX) * 2
                pixels[pixelOffset] = (packed and 0xFF).toByte()
                pixels[pixelOffset + 1] = ((packed shr 8) and 0xFF).toByte()
            }
        }

        return PagePreview(outputWidth, outputHeight, PagePreviewPixelFormat.RGB_565, pixels)
    }

    private fun boxStart(outIndex: Int, scale: Double): Int = Math.floor(outIndex * scale).toInt()

    /** The box's end is clamped to [limit], and forced to it on the last output index, so rounding in [scale] can never leave a sliver of the source unread. */
    private fun boxEnd(outIndex: Int, outputCount: Int, scale: Double, limit: Int): Int {
        if (outIndex == outputCount - 1) return limit
        val end = Math.floor((outIndex + 1) * scale).toInt()
        val start = boxStart(outIndex, scale)
        return minOf(limit, maxOf(end, start + 1))
    }
}
