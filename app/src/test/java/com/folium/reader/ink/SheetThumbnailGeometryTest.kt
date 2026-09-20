package com.folium.reader.ink

import com.folium.reader.core.ink.InkInputKind
import com.folium.reader.core.ink.InkSample
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.InkTip
import com.folium.reader.core.ink.InkTool
import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.StrokeId
import org.junit.Assert.assertEquals
import org.junit.Test

private const val EPSILON = 1e-4f

class SheetThumbnailGeometryTest {

    @Test
    fun heightKeepsTheBitmapAtTheCoverAspect() {
        val height = SheetThumbnailGeometry.heightPx(360)
        assertEquals(506, height)
    }

    @Test
    fun regionSpansTheFullWidthAndOneCoverHeight() {
        val region = SheetThumbnailGeometry.region()
        assertEquals(0f, region.left, EPSILON)
        assertEquals(0f, region.top, EPSILON)
        assertEquals(1f, region.right, EPSILON)
        assertEquals(1.4045f, region.bottom, EPSILON)
    }

    @Test
    fun toPixelScalesBothAxesByTheBitmapWidth() {
        val pixel = SheetThumbnailGeometry.toPixel(SheetPoint(0.5f, 0.25f), widthPx = 360)
        assertEquals(180f, pixel.x, EPSILON)
        assertEquals(90f, pixel.y, EPSILON)
    }

    @Test
    fun strokeWidthScalesWithTheBitmapWidth() {
        assertEquals(36f, SheetThumbnailGeometry.strokeWidthPx(0.1f, widthPx = 360), EPSILON)
    }

    @Test
    fun strokeWidthNeverGoesBelowOnePixel() {
        assertEquals(1f, SheetThumbnailGeometry.strokeWidthPx(0.0001f, widthPx = 360), EPSILON)
    }

    @Test
    fun strokesForThumbnailKeepsOnlyStrokesReachingTheRegion() {
        val inside = stroke("inside", y = 0.1f, sequence = 2)
        val outside = stroke("outside", y = 5f, sequence = 0)

        assertEquals(listOf(inside), SheetThumbnailGeometry.strokesForThumbnail(listOf(outside, inside)))
    }

    @Test
    fun strokesForThumbnailOrdersByDrawSequence() {
        val first = stroke("first", y = 0.1f, sequence = 0)
        val second = stroke("second", y = 0.2f, sequence = 1)

        val result = SheetThumbnailGeometry.strokesForThumbnail(listOf(second, first))

        assertEquals(listOf(first, second), result)
    }

    private fun stroke(id: String, y: Float, sequence: Long): InkStroke {
        val width = 0.01f
        return InkStroke(
            id = StrokeId(id),
            tool = InkTool.PEN,
            tip = InkTip.BALLPOINT,
            colorArgb = 0xFF000000.toInt(),
            widthSheetUnits = width,
            inputKind = InkInputKind.STYLUS,
            samples = listOf(InkSample(0.1f, y, 0), InkSample(0.2f, y, 10)),
            sequence = sequence
        )
    }
}
