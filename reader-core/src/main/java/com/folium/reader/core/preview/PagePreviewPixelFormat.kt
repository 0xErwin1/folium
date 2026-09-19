package com.folium.reader.core.preview

/**
 * How a [PagePreview]'s pixels are packed on disk and in memory.
 *
 * A preview is small enough, and shown blurred and stretched, that RGB565's banding never surfaces:
 * at 32 pixels wide the whole point is a soft field of color, not legible detail. Measured against
 * RGBA8888 at [com.folium.reader.core.preview.PagePreviewScaler.PREVIEW_WIDTH_PX] wide:
 *
 * - 32x45 (a typical portrait page): 2,880 bytes at RGB565 versus 5,760 at RGBA8888.
 * - A 390-page book: roughly 1.07 MiB versus 2.14 MiB for every preview combined.
 * - A 1,563-page book: roughly 4.29 MiB versus 8.58 MiB.
 *
 * Halving that footprint, for a preview that is discarded the instant its real raster is ready, is
 * worth the alpha channel this format drops — a rasterized page is already opaque, so there is
 * nothing in that channel to keep.
 */
enum class PagePreviewPixelFormat(val id: Byte, val bytesPerPixel: Int) {
    RGB_565(0, 2);

    companion object {
        fun fromId(id: Byte): PagePreviewPixelFormat? = entries.firstOrNull { it.id == id }
    }
}
