package com.folium.reader.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.folium.reader.ui.FoliumRuleEdge
import com.folium.reader.ui.FoliumSpacing
import com.folium.reader.ui.FoliumType
import com.folium.reader.ui.foliumBorder
import com.folium.reader.ui.foliumRule

/**
 * A section's small-capitals header (`rail-spec.md` 2.1: "font-size: 11px; font-weight: 700;
 * letter-spacing: 1.2px"), the same step [MaterialTheme.typography.labelMedium] already carries.
 */
@Composable
internal fun SheetSelectorSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * Piece 13, "opción": equal-width cells choosing one of [options], filled ink with paper text when
 * selected, a 1dp line border otherwise (`S-Componentes.dc.html:209-212`). Mirrors [BookSettingsSheet]'s
 * own `SegmentedRow` rather than reusing it directly, since that composable is private to its file.
 */
@Composable
internal fun <T> SheetSelectorTextOptionRow(
    options: List<T>,
    selectedOption: T,
    label: @Composable (T) -> String,
    testTag: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FoliumSpacing.xs)) {
        options.forEach { option ->
            val isSelected = option == selectedOption
            val ink = MaterialTheme.colorScheme.onSurface
            val paper = MaterialTheme.colorScheme.surface
            val line = MaterialTheme.colorScheme.outlineVariant

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = FoliumSpacing.touchTarget)
                    .background(if (isSelected) ink else paper)
                    .foliumBorder(1.dp, if (isSelected) ink else line)
                    .clickable { onSelect(option) }
                    .semantics { selected = isSelected }
                    .testTag(testTag(option)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(option).uppercase(),
                    style = FoliumType.CaptionEmphasis,
                    color = if (isSelected) paper else ink,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = FoliumSpacing.xs, vertical = FoliumSpacing.xxs)
                )
            }
        }
    }
}

/**
 * Piece 14, "paso": a stepper of a 44x44dp minus button, a centred value, and a 44x44dp plus button,
 * with a 1dp border around the whole group and 1dp rules between its three parts
 * (`S-Componentes.dc.html:215-219`). Tap is the only input this control accepts — the design's own
 * drag shortcut is not implemented, since e-ink makes tap the primary path already.
 */
@Composable
internal fun SheetSelectorStepper(
    valueText: String,
    canDecrement: Boolean,
    canIncrement: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    decrementTestTag: String,
    incrementTestTag: String,
    valueTestTag: String,
    decrementDescription: String,
    incrementDescription: String
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val line = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier.fillMaxWidth().foliumBorder(1.dp, ink),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SheetSelectorStepperButton(
            enabled = canDecrement,
            description = decrementDescription,
            testTag = decrementTestTag,
            onClick = onDecrement
        ) { tint -> drawStepperMinusGlyph(tint) }

        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = FoliumSpacing.touchTarget)
                .foliumRule(FoliumRuleEdge.START, 1.dp, line)
                .foliumRule(FoliumRuleEdge.END, 1.dp, line)
                .testTag(valueTestTag),
            contentAlignment = Alignment.Center
        ) {
            Text(text = valueText, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = ink)
        }

        SheetSelectorStepperButton(
            enabled = canIncrement,
            description = incrementDescription,
            testTag = incrementTestTag,
            onClick = onIncrement
        ) { tint -> drawStepperPlusGlyph(tint) }
    }
}

@Composable
private fun SheetSelectorStepperButton(
    enabled: Boolean,
    description: String,
    testTag: String,
    onClick: () -> Unit,
    glyph: DrawScope.(Color) -> Unit
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.outline
    val tint = if (enabled) ink else muted

    Box(
        modifier = Modifier
            .size(FoliumSpacing.touchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(20.dp)) { glyph(tint) }
    }
}

private val StepperGlyphStrokeWidth = 1.6.dp

/** The "−" (`S-Componentes.dc.html:219`: `M4 10H16`, drawn as a plain horizontal line on this control's own 20dp glyph box). */
private fun DrawScope.drawStepperMinusGlyph(tint: Color) {
    val strokeWidth = StepperGlyphStrokeWidth.toPx()
    drawLine(tint, start = Offset(2f, size.height / 2f), end = Offset(size.width - 2f, size.height / 2f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
}

/** The "+" (`S-Componentes.dc.html:219`: `M10 4V16 M4 10H16`). */
private fun DrawScope.drawStepperPlusGlyph(tint: Color) {
    val strokeWidth = StepperGlyphStrokeWidth.toPx()
    drawLine(tint, start = Offset(2f, size.height / 2f), end = Offset(size.width - 2f, size.height / 2f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
    drawLine(tint, start = Offset(size.width / 2f, 2f), end = Offset(size.width / 2f, size.height - 2f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
}

/** The colour chip's own size, inside the 44dp hit box (`S-Componentes.dc.html`, colour swatch piece). */
private val CHIP_SIZE = 26.dp

/**
 * Piece D, a colour swatch row: a 44x44dp hit box around a 26x26dp chip, the colour's own name under
 * it, and a 2dp ink outline with a 2dp paper gap on the selected chip so selection reads on any chip
 * colour rather than relying on the colour itself (`rail-spec.md` 2.1, `D3/T-Reglas.dc.html:161`).
 */
@Composable
internal fun <T> SheetSelectorColourRow(
    options: List<T>,
    selectedOption: T,
    colorFor: (T) -> Color,
    nameFor: @Composable (T) -> String,
    testTag: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(FoliumSpacing.xs)) {
        options.forEach { option ->
            val isSelected = option == selectedOption
            val ink = MaterialTheme.colorScheme.onSurface
            val line = MaterialTheme.colorScheme.outlineVariant

            Column(
                modifier = Modifier
                    .width(FoliumSpacing.touchTarget)
                    .clickable { onSelect(option) }
                    .semantics { selected = isSelected }
                    .testTag(testTag(option)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FoliumSpacing.xxs)
            ) {
                val outlineWidth = if (isSelected) 2.dp else 1.dp
                val gap = if (isSelected) 2.dp else 0.dp
                val ringSize = CHIP_SIZE + gap * 2 + outlineWidth * 2

                Box(Modifier.size(FoliumSpacing.touchTarget), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier.size(ringSize).foliumBorder(outlineWidth, if (isSelected) ink else line),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(CHIP_SIZE).background(colorFor(option)))
                    }
                }
                Text(text = nameFor(option).uppercase(), style = FoliumType.Caption, color = ink)
            }
        }
    }
}
