package com.folium.reader.core.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PagePreviewScalerTest {

    private fun solidRgba(width: Int, height: Int, r: Int, g: Int, b: Int): ByteArray {
        val pixels = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            pixels[i * 4] = r.toByte()
            pixels[i * 4 + 1] = g.toByte()
            pixels[i * 4 + 2] = b.toByte()
            pixels[i * 4 + 3] = 0xFF.toByte()
        }
        return pixels
    }

    private fun unpack565(pixels: ByteArray, index: Int): Triple<Int, Int, Int> {
        val low = pixels[index * 2].toInt() and 0xFF
        val high = pixels[index * 2 + 1].toInt() and 0xFF
        val packed = (high shl 8) or low
        val r = ((packed shr 11) and 0x1F) shl 3
        val g = ((packed shr 5) and 0x3F) shl 2
        val b = (packed and 0x1F) shl 3
        return Triple(r, g, b)
    }

    @Test fun solidColorScalesToTheSameColorEverywhere() {
        val source = solidRgba(64, 90, 200, 100, 50)
        val preview = PagePreviewScaler.scale(source, 64, 90)

        assertEquals(32, preview.width)
        assertEquals(45, preview.height)
        for (i in 0 until preview.width * preview.height) {
            val (r, g, b) = unpack565(preview.pixels, i)
            assertTrue("red close to 200, was $r", Math.abs(r - 200) <= 8)
            assertTrue("green close to 100, was $g", Math.abs(g - 100) <= 4)
            assertTrue("blue close to 50, was $b", Math.abs(b - 50) <= 8)
        }
    }

    @Test fun verticalSplitKeepsLeftAndRightHalvesDistinct() {
        val width = 64
        val height = 64
        val pixels = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = (y * width + x) * 4
                val isLeft = x < width / 2
                pixels[offset] = if (isLeft) 255.toByte() else 0
                pixels[offset + 1] = 0
                pixels[offset + 2] = if (isLeft) 0 else 255.toByte()
                pixels[offset + 3] = 0xFF.toByte()
            }
        }

        val preview = PagePreviewScaler.scale(pixels, width, height)
        val (leftR, _, leftB) = unpack565(preview.pixels, preview.height / 2 * preview.width)
        val (rightR, _, rightB) = unpack565(preview.pixels, preview.height / 2 * preview.width + preview.width - 1)

        assertTrue("left column should be reddish, was r=$leftR b=$leftB", leftR > leftB)
        assertTrue("right column should be bluish, was r=$rightR b=$rightB", rightB > rightR)
    }

    @Test fun horizontalGradientIsMonotonicAfterScaling() {
        val width = 256
        val height = 32
        val pixels = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = (y * width + x) * 4
                val value = (x * 255 / (width - 1))
                pixels[offset] = value.toByte()
                pixels[offset + 1] = value.toByte()
                pixels[offset + 2] = value.toByte()
                pixels[offset + 3] = 0xFF.toByte()
            }
        }

        val preview = PagePreviewScaler.scale(pixels, width, height)
        var previousRed = -1
        for (x in 0 until preview.width) {
            val (r, _, _) = unpack565(preview.pixels, x)
            assertTrue("gradient must be non-decreasing, was $r after $previousRed at x=$x", r >= previousRed)
            previousRed = r
        }
    }

    @Test fun aOnePixelWideVerticalLineSurvivesAsGreyRatherThanVanishing() {
        val width = 64
        val height = 64
        val pixels = solidRgba(width, height, 0, 0, 0)
        for (y in 0 until height) {
            val offset = (y * width + 32) * 4
            pixels[offset] = 255.toByte()
            pixels[offset + 1] = 255.toByte()
            pixels[offset + 2] = 255.toByte()
        }

        val preview = PagePreviewScaler.scale(pixels, width, height)
        val (r, _, _) = unpack565(preview.pixels, preview.height / 2 * preview.width + preview.width / 2)
        assertTrue("the line's box average must lighten its column above pure black, was $r", r > 0)
    }

    @Test fun oddSizesAreExactAtTheEdges() {
        val width = 67
        val height = 101
        val pixels = solidRgba(width, height, 10, 20, 30)

        val preview = PagePreviewScaler.scale(pixels, width, height)

        assertEquals(32, preview.width)
        assertTrue(preview.height in 46..49)
        val (r, g, b) = unpack565(preview.pixels, preview.width * preview.height - 1)
        assertTrue(Math.abs(r - 10) <= 8)
        assertTrue(Math.abs(g - 20) <= 4)
        assertTrue(Math.abs(b - 30) <= 8)
    }

    @Test fun pixelBytesAreLittleEndianAsAndroidRgb565BitmapsExpect() {
        // Pure red at 565 precision (R=31, G=0, B=0) packs to the 16-bit value 0xF800.
        val pixels = solidRgba(1, 1, 0xF8, 0x00, 0x00)
        val preview = PagePreviewScaler.scale(pixels, 1, 1)

        assertEquals(2, preview.pixels.size)
        assertEquals(0x00.toByte(), preview.pixels[0])
        assertEquals(0xF8.toByte(), preview.pixels[1])
    }

    @Test fun sourceNarrowerThan32IsNotUpscaled() {
        val width = 12
        val height = 20
        val pixels = solidRgba(width, height, 5, 6, 7)

        val preview = PagePreviewScaler.scale(pixels, width, height)

        assertEquals(width, preview.width)
        assertEquals(height, preview.height)
    }
}
