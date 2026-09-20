package com.folium.reader.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
 * [isSelected] answers true for that option, a 1dp line border otherwise
 * (`S-Componentes.dc.html:209-212`). [isSelected] is a predicate rather than a single value compared
 * by equality, so a group whose options are one-shot actions rather than a persistent choice — the
 * VIEW panel's FIT TO row — can report no option selected at all. Mirrors [BookSettingsSheet]'s own
 * `SegmentedRow` rather than reusing it directly, since that composable is private to its file.
 */
@Composable
internal fun <T> SheetSelectorTextOptionRow(
    options: List<T>,
    label: @Composable (T) -> String,
    testTag: (T) -> String,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FoliumSpacing.xs)) {
        options.forEach { option ->
            val optionIsSelected = isSelected(option)
            val ink = MaterialTheme.colorScheme.onSurface
            val paper = MaterialTheme.colorScheme.surface
            val line = MaterialTheme.colorScheme.outlineVariant

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = FoliumSpacing.touchTarget)
                    .background(if (optionIsSelected) ink else paper)
                    .foliumBorder(1.dp, if (optionIsSelected) ink else line)
                    .clickable { onSelect(option) }
                    .semantics { selected = optionIsSelected }
                    .testTag(testTag(option)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(option).uppercase(),
                    style = FoliumType.CaptionEmphasis,
                    color = if (optionIsSelected) paper else ink,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = FoliumSpacing.xs, vertical = FoliumSpacing.xxs)
                )
            }
        }
    }
}

/**
 * A panel's own helper text, run below a section's control (`rail-spec.md` 2.1: "font-size: 12px;
 * line-height: 17px; color: {{c.muted}}"). [FoliumType.Caption] already carries the spec's 12px size;
 * its own 16px line-height is close enough to the spec's 17px that a dedicated step is not worth
 * adding.
 */
@Composable
internal fun SheetSelectorHelperText(text: String) {
    Text(text = text, style = FoliumType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A glyph option cell's own minimum height, and its glyph's own size, per the design's Piece A (`rail-spec.md` 2.1: "min-height: 64px", `<svg width="56" height="20">`). */
private val GlyphOptionCellMinHeight = 64.dp
private val GlyphOptionGlyphWidth = 56.dp
private val GlyphOptionGlyphHeight = 20.dp

/**
 * Piece A, a glyph option cell: equal-width cells choosing one of [options], each drawing [glyph]
 * above its own label rather than [SheetSelectorTextOptionRow]'s label alone, filled ink with paper
 * ink when [isSelected] answers true for that option, a 1dp line border otherwise (`rail-spec.md`
 * 2.1: "Cell min-height: 64px ... Glyph <svg width="56" height="20">").
 */
@Composable
internal fun <T> SheetSelectorGlyphOptionRow(
    options: List<T>,
    label: @Composable (T) -> String,
    testTag: (T) -> String,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    glyph: DrawScope.(T, Color) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FoliumSpacing.xs)) {
        options.forEach { option ->
            val optionIsSelected = isSelected(option)
            val ink = MaterialTheme.colorScheme.onSurface
            val paper = MaterialTheme.colorScheme.surface
            val line = MaterialTheme.colorScheme.outlineVariant
            val tint = if (optionIsSelected) paper else ink

            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = GlyphOptionCellMinHeight)
                    .background(if (optionIsSelected) ink else Color.Transparent)
                    .foliumBorder(1.dp, if (optionIsSelected) ink else line)
                    .clickable { onSelect(option) }
                    .semantics { selected = optionIsSelected }
                    .testTag(testTag(option)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Canvas(Modifier.width(GlyphOptionGlyphWidth).height(GlyphOptionGlyphHeight)) { glyph(option, tint) }
                Spacer(Modifier.height(FoliumSpacing.xxs))
                Text(
                    text = label(option).uppercase(),
                    style = FoliumType.CaptionEmphasis,
                    color = tint,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Piece 14, "paso": the reader's progress bar with a 44x44dp button at each end
 * (`S-Componentes.dc.html:215-219`). Between the buttons runs a 4dp track in the field tone, filled in
 * the signal colour up to [fraction], with a 3x14dp ink cursor at the fill's end. The value itself is
 * not drawn here: the design reads it "arriba a la derecha, en la línea de la etiqueta", so the
 * section header carries it and [valueText] only names the state for accessibility.
 *
 * Tapping a button is the main input, since on e-ink one tap costs one refresh; dragging or tapping
 * the track is the shortcut the design allows, reported through [onFractionSelected] as a position
 * between 0 and 1 for the caller to snap to its own steps.
 */
@Composable
internal fun SheetSelectorStepper(
    valueText: String,
    fraction: Float,
    canDecrement: Boolean,
    canIncrement: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onFractionSelected: (Float) -> Unit,
    decrementTestTag: String,
    incrementTestTag: String,
    valueTestTag: String,
    decrementDescription: String,
    incrementDescription: String
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val field = MaterialTheme.colorScheme.surfaceVariant
    val signal = MaterialTheme.colorScheme.tertiary
    val filled = fraction.coerceIn(0f, 1f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FoliumSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SheetSelectorStepperButton(
            enabled = canDecrement,
            description = decrementDescription,
            testTag = decrementTestTag,
            onClick = onDecrement
        ) { tint -> drawStepperMinusGlyph(tint) }

        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(FoliumSpacing.touchTarget)
                .pointerInput(Unit) {
                    detectTapGestures { position -> onFractionSelected((position.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        onFractionSelected((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                }
                .semantics { stateDescription = valueText }
                .testTag(valueTestTag)
        ) {
            val trackHeight = StepperTrackHeight.toPx()
            val trackTop = (size.height - trackHeight) / 2f
            val fillWidth = size.width * filled

            drawRect(field, topLeft = Offset(0f, trackTop), size = Size(size.width, trackHeight))
            drawRect(signal, topLeft = Offset(0f, trackTop), size = Size(fillWidth, trackHeight))

            val cursorWidth = StepperCursorWidth.toPx()
            val cursorHeight = StepperCursorHeight.toPx()
            drawRect(
                ink,
                topLeft = Offset((fillWidth - cursorWidth / 2f).coerceIn(0f, size.width - cursorWidth), (size.height - cursorHeight) / 2f),
                size = Size(cursorWidth, cursorHeight)
            )
        }

        SheetSelectorStepperButton(
            enabled = canIncrement,
            description = incrementDescription,
            testTag = incrementTestTag,
            onClick = onIncrement
        ) { tint -> drawStepperPlusGlyph(tint) }
    }
}

private val StepperTrackHeight = 4.dp
private val StepperCursorWidth = 3.dp
private val StepperCursorHeight = 14.dp

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
