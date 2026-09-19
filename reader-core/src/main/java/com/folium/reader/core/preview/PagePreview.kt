package com.folium.reader.core.preview

/**
 * A downscaled, [format]-packed stand-in for a whole page's raster: small enough to keep every page
 * of a book in memory at once, and cheap enough to produce that nothing upstream needs to wait for it.
 */
data class PagePreview(
    val width: Int,
    val height: Int,
    val format: PagePreviewPixelFormat,
    val pixels: ByteArray
) {
    init {
        require(width > 0 && height > 0) { "width and height must be positive, got width=$width height=$height" }
        require(pixels.size.toLong() == width.toLong() * height.toLong() * format.bytesPerPixel) {
            "pixels size ${pixels.size} does not match ${width}x$height at ${format.bytesPerPixel} bytes per pixel"
        }
    }

    override fun equals(other: Any?): Boolean = other is PagePreview &&
        width == other.width && height == other.height && format == other.format && pixels.contentEquals(other.pixels)

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + format.hashCode()
        result = 31 * result + pixels.contentHashCode()
        return result
    }
}
