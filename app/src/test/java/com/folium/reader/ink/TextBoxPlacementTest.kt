package com.folium.reader.ink

import com.folium.reader.core.ink.SheetPoint
import com.folium.reader.core.ink.SheetTextBox
import com.folium.reader.core.ink.SheetTextFont
import com.folium.reader.core.ink.SheetTextStyle
import com.folium.reader.core.ink.StrokeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EPSILON = 1e-5f

class TextBoxPlacementTest {

    @Test fun `a new box's left edge sits at the tap and its width reaches the right margin`() {
        val geometry = newTextBoxGeometry(tapXSheetUnits = 0.2f, rightMarginSheetUnits = 0.1f, minWidthSheetUnits = 0.2f)

        assertEquals(0.2f, geometry.left, EPSILON)
        assertEquals(0.7f, geometry.widthSheetUnits, EPSILON)
    }

    @Test fun `a tap too close to the right edge shifts the box left to keep its own minimum width`() {
        val geometry = newTextBoxGeometry(tapXSheetUnits = 0.85f, rightMarginSheetUnits = 0.1f, minWidthSheetUnits = 0.3f)

        assertEquals(0.6f, geometry.left, EPSILON)
        assertEquals(0.3f, geometry.widthSheetUnits, EPSILON)
    }

    @Test fun `a text size's own line height is the smallest rule multiple at least 1_25 times its size`() {
        assertEquals(32f, SheetRuleGrid.lineHeightForTextSize(16f), EPSILON)
        assertEquals(32f, SheetRuleGrid.lineHeightForTextSize(24f), EPSILON)
        assertEquals(64f, SheetRuleGrid.lineHeightForTextSize(26f), EPSILON)
        assertEquals(64f, SheetRuleGrid.lineHeightForTextSize(36f), EPSILON)
    }

    @Test fun `a top edge snaps down to the nearest rule at or above it`() {
        val ruleSpacing = SheetRuleGrid.SPACING_SHEET_UNITS

        assertEquals(0f, snappedTextBoxTop(0f), EPSILON)
        assertEquals(0f, snappedTextBoxTop(ruleSpacing * 0.5f), EPSILON)
        assertEquals(ruleSpacing, snappedTextBoxTop(ruleSpacing * 1.9f), EPSILON)
    }

    @Test fun `ascii text counts one byte per character`() {
        assertEquals(5, utf8ByteCount("hello"))
        assertTrue(fitsUtf8ByteLimit("hello", maxBytes = 5))
        assertFalse(fitsUtf8ByteLimit("hello", maxBytes = 4))
    }

    @Test fun `a multi-byte character counts its own full UTF-8 encoding`() {
        assertEquals(2, utf8ByteCount("é"))
        assertEquals(3, utf8ByteCount("€"))
    }

    @Test fun `a surrogate pair counts as one code point's own four-byte encoding, never two`() {
        val emoji = "😀"
        assertEquals(2, emoji.length)
        assertEquals(4, utf8ByteCount(emoji))
    }

    @Test fun `a brand-new box with blank text commits nothing`() {
        val decision = decideTextCommit(null, "   ", SheetTextFont.SERIF, 16f, SheetTextStyle.NORMAL, 0, SheetPoint(0f, 0f), 0.5f)
        assertEquals(TextCommitDecision.Noop, decision)
    }

    @Test fun `a brand-new box with real text is added`() {
        val decision = decideTextCommit(null, "hello", SheetTextFont.SERIF, 16f, SheetTextStyle.NORMAL, 0, SheetPoint(0f, 0f), 0.5f)
        assertEquals(TextCommitDecision.AddNew("hello"), decision)
    }

    @Test fun `an edited box whose text, attributes, colour and geometry are all unchanged commits nothing`() {
        val original = textBox(text = "hello", colorArgb = 1, topLeft = SheetPoint(0.1f, 0.2f), width = 0.5f)
        val decision = decideTextCommit(
            original, "hello", original.font, original.sizePt, original.style, 1, SheetPoint(0.1f, 0.2f), 0.5f
        )
        assertEquals(TextCommitDecision.Noop, decision)
    }

    @Test fun `an edited box with new text is replaced`() {
        val original = textBox(text = "hello")
        val decision = decideTextCommit(
            original, "goodbye", original.font, original.sizePt, original.style, original.colorArgb, original.topLeft, original.widthSheetUnits
        )
        assertEquals(TextCommitDecision.Replace(original, "goodbye"), decision)
    }

    @Test fun `an edited box with a new font is replaced even when the text is unchanged`() {
        val original = textBox(text = "hello", font = SheetTextFont.SERIF)
        val decision = decideTextCommit(
            original, "hello", SheetTextFont.SANS, original.sizePt, original.style, original.colorArgb, original.topLeft, original.widthSheetUnits
        )
        assertEquals(TextCommitDecision.Replace(original, "hello"), decision)
    }

    @Test fun `an edited box with a new size is replaced even when the text is unchanged`() {
        val original = textBox(text = "hello", sizePt = 16f)
        val decision = decideTextCommit(
            original, "hello", original.font, 24f, original.style, original.colorArgb, original.topLeft, original.widthSheetUnits
        )
        assertEquals(TextCommitDecision.Replace(original, "hello"), decision)
    }

    @Test fun `an edited box with a new style is replaced even when the text is unchanged`() {
        val original = textBox(text = "hello", style = SheetTextStyle.NORMAL)
        val decision = decideTextCommit(
            original, "hello", original.font, original.sizePt, SheetTextStyle.BOLD, original.colorArgb, original.topLeft, original.widthSheetUnits
        )
        assertEquals(TextCommitDecision.Replace(original, "hello"), decision)
    }

    @Test fun `an existing box edited down to blank text is removed`() {
        val original = textBox(text = "hello")
        val decision = decideTextCommit(
            original, "  ", original.font, original.sizePt, original.style, original.colorArgb, original.topLeft, original.widthSheetUnits
        )
        assertEquals(TextCommitDecision.RemoveExisting(original), decision)
    }

    private fun textBox(
        text: String,
        font: SheetTextFont = SheetTextFont.SERIF,
        sizePt: Float = 16f,
        style: SheetTextStyle = SheetTextStyle.NORMAL,
        colorArgb: Int = 0,
        topLeft: SheetPoint = SheetPoint(0f, 0f),
        width: Float = 0.5f
    ): SheetTextBox = SheetTextBox(
        id = StrokeId("text-1"),
        topLeft = topLeft,
        widthSheetUnits = width,
        heightSheetUnits = 0.1f,
        text = text,
        font = font,
        sizePt = sizePt,
        style = style,
        colorArgb = colorArgb,
        sequence = 1L
    )
}
