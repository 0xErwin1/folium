package com.folium.reader.core.ink

import com.folium.reader.core.library.BookId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkModelTest {

    private fun sample(x: Float, y: Float, elapsedMillis: Int = 0) = InkSample(x, y, elapsedMillis)

    @Test(expected = IllegalArgumentException::class)
    fun aStrokeWithNoSamplesIsRejected() {
        InkStroke(
            StrokeId("11111111-1111-1111-1111-111111111111"),
            InkTool.PEN, InkTip.BALLPOINT, colorArgb = 0xFF000000.toInt(),
            widthSheetUnits = 0.01f, inputKind = InkInputKind.STYLUS,
            samples = emptyList(), sequence = 0
        )
    }

    @Test
    fun boundsIncludeHalfTheWidthOnEverySide() {
        val width = 0.02f
        val stroke = InkStroke(
            StrokeId("11111111-1111-1111-1111-111111111111"),
            InkTool.PEN, InkTip.BALLPOINT, colorArgb = 0xFF000000.toInt(),
            widthSheetUnits = width, inputKind = InkInputKind.STYLUS,
            samples = listOf(sample(0.3f, 1f), sample(0.5f, 1.2f)), sequence = 0
        )
        val half = width / 2f
        assertEquals(SheetRect(0.3f - half, 1f - half, 0.5f + half, 1.2f + half), stroke.bounds)
    }

    @Test
    fun aSingleSampleStrokeStillGetsANonDegenerateBoundsBox() {
        val width = 0.04f
        val stroke = InkStroke(
            StrokeId("11111111-1111-1111-1111-111111111111"),
            InkTool.PEN, InkTip.PENCIL, colorArgb = 0xFF000000.toInt(),
            widthSheetUnits = width, inputKind = InkInputKind.FINGER,
            samples = listOf(sample(0.5f, 2f)), sequence = 3
        )
        assertEquals(width.toDouble(), stroke.bounds.width.toDouble(), 1e-6)
        assertEquals(width.toDouble(), stroke.bounds.height.toDouble(), 1e-6)
        assertEquals(3L, stroke.sequence)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNonPositiveWidthIsRejected() {
        InkStroke(
            StrokeId("11111111-1111-1111-1111-111111111111"),
            InkTool.PEN, InkTip.BALLPOINT, colorArgb = 0,
            widthSheetUnits = 0f, inputKind = InkInputKind.MOUSE,
            samples = listOf(sample(0.1f, 0.1f)), sequence = 0
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSampleWithOutOfRangePressureIsRejected() {
        InkSample(0.1f, 0.1f, 0, pressure = 1.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBlankSheetTitleIsRejected() {
        Sheet(
            SheetId("11111111-1111-1111-1111-111111111111"),
            title = "   ", createdAtEpochMillis = 0, updatedAtEpochMillis = 0,
            template = SheetTemplate.BLANK, anchor = null
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSheetUpdatedBeforeItWasCreatedIsRejected() {
        Sheet(
            SheetId("11111111-1111-1111-1111-111111111111"),
            title = "Notes", createdAtEpochMillis = 100, updatedAtEpochMillis = 50,
            template = SheetTemplate.BLANK, anchor = null
        )
    }

    @Test
    fun aStandaloneSheetHasNoAnchor() {
        val sheet = Sheet(
            SheetId("11111111-1111-1111-1111-111111111111"),
            title = "Notes", createdAtEpochMillis = 0, updatedAtEpochMillis = 0,
            template = SheetTemplate.RULED, anchor = null
        )
        assertTrue(sheet.anchor == null)
    }

    @Test
    fun anAnchoredSheetCarriesTheBookAndPage() {
        val anchor = SheetAnchor(BookId("some-book-id"), pageIndex = 4)
        val sheet = Sheet(
            SheetId("11111111-1111-1111-1111-111111111111"),
            title = "Margin note", createdAtEpochMillis = 0, updatedAtEpochMillis = 0,
            template = SheetTemplate.BLANK, anchor = anchor
        )
        assertEquals(4, sheet.anchor?.pageIndex)
    }

    private fun textBox(
        id: String = "22222222-2222-2222-2222-222222222222",
        topLeft: SheetPoint = SheetPoint(0.1f, 0.2f),
        widthSheetUnits: Float = 0.5f,
        heightSheetUnits: Float = 0.1f,
        text: String = "hello",
        style: SheetTextStyle = SheetTextStyle.BODY,
        sequence: Long = 0
    ) = SheetTextBox(StrokeId(id), topLeft, widthSheetUnits, heightSheetUnits, text, style, colorArgb = 0xFF000000.toInt(), sequence = sequence)

    @Test
    fun aTextBoxsBoundsExtendFromItsTopLeftByItsWidthAndHeight() {
        val box = textBox(topLeft = SheetPoint(1f, 2f), widthSheetUnits = 0.4f, heightSheetUnits = 0.3f)
        assertEquals(SheetRect(1f, 2f, 1.4f, 2.3f), box.bounds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aTextBoxWithNonPositiveWidthIsRejected() {
        textBox(widthSheetUnits = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aTextBoxWithNegativeHeightIsRejected() {
        textBox(heightSheetUnits = -0.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aTextBoxWithNegativeSequenceIsRejected() {
        textBox(sequence = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aTextBoxWithANonFiniteTopLeftIsRejected() {
        textBox(topLeft = SheetPoint(Float.NaN, 0f))
    }

    @Test
    fun aZeroHeightTextBoxIsAccepted() {
        val box = textBox(heightSheetUnits = 0f)
        assertEquals(0f, box.bounds.height, 0f)
    }

    @Test
    fun sheetItemReadsThroughToTheWrappedStrokeOrTextBox() {
        val stroke = InkStroke(
            StrokeId("11111111-1111-1111-1111-111111111111"),
            InkTool.PEN, InkTip.BALLPOINT, colorArgb = 0,
            widthSheetUnits = 0.01f, inputKind = InkInputKind.STYLUS,
            samples = listOf(sample(0f, 0f)), sequence = 5
        )
        val box = textBox(sequence = 6)

        val strokeItem: SheetItem = SheetItem.Stroke(stroke)
        val textItem: SheetItem = SheetItem.Text(box)

        assertEquals(stroke.id, strokeItem.id)
        assertEquals(stroke.sequence, strokeItem.sequence)
        assertEquals(stroke.bounds, strokeItem.bounds)

        assertEquals(box.id, textItem.id)
        assertEquals(box.sequence, textItem.sequence)
        assertEquals(box.bounds, textItem.bounds)
    }
}
