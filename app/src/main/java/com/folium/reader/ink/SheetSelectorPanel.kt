package com.folium.reader.ink

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folium.reader.R
import com.folium.reader.core.ink.InkTip
import com.folium.reader.ui.FoliumDialog
import com.folium.reader.ui.FoliumRuleEdge
import com.folium.reader.ui.FoliumSpacing
import com.folium.reader.ui.FoliumType
import com.folium.reader.ui.foliumBorder
import com.folium.reader.ui.foliumRule

/** The rule that visually joins a selector panel to the rail cell that opened it (`rail-spec.md` 2.1: "leader rule ... width: 12px"). */
private val ConnectorWidth = 12.dp
private val ConnectorHeight = 1.dp

/** Panel box padding: `rail-spec.md` 2.1, "padding: 14px 16px 16px 16px". */
private val PanelPaddingTop = 14.dp
private val PanelPaddingSides = 16.dp
private val PanelPaddingBottom = 16.dp
private val PanelSectionGap = 14.dp

/** The panel's own maximum width; clamped further to the pane by [sheetSelectorPanelWidth]. */
private val PanelMaxWidth = 320.dp

/**
 * The vertical offset from the rail's own top edge to [tool]'s cell's top edge, since a panel always
 * anchors to whichever rail cell is currently active.
 */
private fun railCellTopOffset(tool: SheetRailTool): Dp {
    val index = SheetRailTools.indexOf(tool)
    return RailColumnTopPadding + (RailColumnCellHeight + RailColumnCellGap) * index
}

/** The connector rule's own vertical offset: [tool]'s cell's vertical middle (`rail-spec.md` 2.1: "margin-top: 30px" on a 60px cell). */
private fun railConnectorTopOffset(tool: SheetRailTool): Dp = railCellTopOffset(tool) + RailColumnCellHeight / 2

/**
 * The COLUMN-layout panel's own width: 320dp, clamped to whatever room is left of the pane once the
 * rail's own inset, breadth and connector are subtracted on the left, and a matching margin is left
 * on the right (`rail-spec.md` task instructions: "Width 320dp clamped to the pane width minus the
 * rail minus 16dp"; the rail's own inset replaces that flat margin now that the rail floats rather
 * than sitting flush with the pane's edge).
 */
internal fun sheetSelectorPanelWidth(paneWidth: Dp, railInset: Dp = SheetPaneBodyPadding, railBreadth: Dp = RailBreadth): Dp {
    val available = (paneWidth - railInset - railBreadth - ConnectorWidth - railInset).coerceAtLeast(0.dp)
    return minOf(PanelMaxWidth, available)
}

/** The COMPACT-layout panel's own width: the full pane width minus a margin on each side. */
internal fun sheetSelectorCompactPanelWidth(paneWidth: Dp): Dp =
    (paneWidth - CompactPanelMargin * 2).coerceAtLeast(0.dp)

/**
 * The selector panel overlay: a transparent full-size tap catcher behind the panel box, so a tap
 * anywhere else on the pane closes the panel without a `Popup`/`Dialog`, keeping the panel's own
 * border on the pixel grid and the drawing surface's state untouched (`rail-spec.md` task
 * instructions, panel anatomy). System back takes precedence over leaving the sheet while a panel is
 * open, so this installs its own [BackHandler] rather than deferring to the host's.
 */
@Composable
internal fun SheetSelectorOverlay(
    orientation: SheetPaneRailOrientation,
    paneWidth: Dp,
    railInset: Dp,
    activeTool: SheetRailTool,
    openPanel: SheetSelectorPanel?,
    penSettings: PenSettings,
    onPenSettingsChange: (PenSettings) -> Unit,
    zoomPercent: Int,
    actualSizeZoomPercent: Int,
    onZoomPercentChange: (Int) -> Unit,
    onFitWidth: () -> Unit,
    onFitActualSize: () -> Unit,
    strokeCount: Int,
    onClearAll: () -> Unit,
    onOutsideTapped: () -> Unit,
    onBackPressed: () -> Unit
) {
    if (openPanel == null) return

    BackHandler(onBack = onBackPressed)

    Box(Modifier.fillMaxSize().testTag(SheetPaneTestTags.SELECTOR_PANEL_OVERLAY)) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOutsideTapped
                )
        )

        if (orientation == SheetPaneRailOrientation.COLUMN) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = railInset + RailBreadth, y = railInset + railConnectorTopOffset(activeTool))
                    .width(ConnectorWidth)
                    .height(ConnectorHeight)
                    .background(MaterialTheme.colorScheme.onSurface)
            )
        }

        val panelModifier = if (orientation == SheetPaneRailOrientation.COLUMN) {
            Modifier
                .align(Alignment.TopStart)
                .offset(x = railInset + RailBreadth + ConnectorWidth, y = railInset + railCellTopOffset(activeTool))
                .width(sheetSelectorPanelWidth(paneWidth, railInset))
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = CompactPanelMargin)
                .offset(y = -RailRowCellHeight)
                .width(sheetSelectorCompactPanelWidth(paneWidth))
        }

        SheetSelectorPanelBox(modifier = panelModifier) {
            when (openPanel) {
                SheetSelectorPanel.VIEW -> SheetViewSelectorPanel(
                    zoomPercent = zoomPercent,
                    actualSizeZoomPercent = actualSizeZoomPercent,
                    onZoomPercentChange = onZoomPercentChange,
                    onFitWidth = onFitWidth,
                    onFitActualSize = onFitActualSize
                )
                SheetSelectorPanel.PEN -> SheetPenSelectorPanel(penSettings, onPenSettingsChange)
                SheetSelectorPanel.ERASER -> SheetEraserSelectorPanel(penSettings, onPenSettingsChange, strokeCount, onClearAll)
            }
        }
    }
}

/**
 * The panel's own shared chrome: 1dp ink border, paper background, no shadow and no animation
 * (`rail-spec.md` 2.1, `D3/T-Reglas.dc.html:157-159`), swallowing a tap on itself rather than the
 * catcher underneath so tapping the panel's own padding does not close it.
 */
@Composable
private fun SheetSelectorPanelBox(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .widthIn(max = PanelMaxWidth)
            .background(MaterialTheme.colorScheme.surface)
            .foliumBorder(1.dp, MaterialTheme.colorScheme.onSurface)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(top = PanelPaddingTop, start = PanelPaddingSides, end = PanelPaddingSides, bottom = PanelPaddingBottom)
            .testTag(SheetPaneTestTags.SELECTOR_PANEL),
        verticalArrangement = Arrangement.spacedBy(PanelSectionGap)
    ) {
        content()
    }
}

/**
 * The pen panel's own title, drawn at [FoliumType.PanelTitle] — a step no existing
 * [com.folium.reader.ui.FoliumTypography] slot matches exactly (`rail-spec.md` 2.1: "font-size: 17px;
 * font-weight: 500; letter-spacing: -0.3px").
 */
@Composable
private fun SheetSelectorPanelTitle(text: String) {
    Text(text = text, style = FoliumType.PanelTitle, color = MaterialTheme.colorScheme.onSurface)
}

/**
 * A panel section: a 1dp top rule, its own small-capitals label and an optional right-aligned value
 * on the same line, then the section's own control (`rail-spec.md` 2.1: "border-top: 1px solid
 * {{c.line}}; padding-top: 10px").
 */
@Composable
private fun SheetSelectorSection(label: String, value: String? = null, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .foliumRule(FoliumRuleEdge.TOP, 1.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            SheetSelectorSectionLabel(label)
            value?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        content()
    }
}

/**
 * The view panel: ZOOM, a stepper of the live zoom as a percentage, and FIT TO, one-shot actions
 * that jump to a fixed zoom rather than remembering a choice (`rail-spec.md` 2.2, VISTA panel). The
 * design's third FIT TO option, PÁGINA, is omitted: an endless sheet has no fixed page to fit to.
 */
@Composable
private fun SheetViewSelectorPanel(
    zoomPercent: Int,
    actualSizeZoomPercent: Int,
    onZoomPercentChange: (Int) -> Unit,
    onFitWidth: () -> Unit,
    onFitActualSize: () -> Unit
) {
    SheetSelectorPanelTitle(stringResource(R.string.sheet_selector_view_title))

    SheetSelectorSection(
        label = stringResource(R.string.sheet_selector_view_zoom),
        value = formatZoomPercent(zoomPercent)
    ) {
        SheetSelectorStepper(
            valueText = formatZoomPercent(zoomPercent),
            fraction = (zoomPercent - ZOOM_MIN_PERCENT).toFloat() / (ZOOM_MAX_PERCENT - ZOOM_MIN_PERCENT),
            onFractionSelected = { picked ->
                onZoomPercentChange(snapToStep(ZOOM_MIN_PERCENT, ZOOM_MAX_PERCENT, ZOOM_STEP_PERCENT, picked))
            },
            canDecrement = zoomPercent > ZOOM_MIN_PERCENT,
            canIncrement = zoomPercent < ZOOM_MAX_PERCENT,
            onDecrement = { onZoomPercentChange(nextZoomStep(zoomPercent, ZoomStepDirection.DECREASE)) },
            onIncrement = { onZoomPercentChange(nextZoomStep(zoomPercent, ZoomStepDirection.INCREASE)) },
            decrementTestTag = SheetPaneTestTags.SELECTOR_ZOOM_MINUS,
            incrementTestTag = SheetPaneTestTags.SELECTOR_ZOOM_PLUS,
            valueTestTag = SheetPaneTestTags.SELECTOR_ZOOM_VALUE,
            decrementDescription = stringResource(R.string.sheet_selector_view_zoom_decrease),
            incrementDescription = stringResource(R.string.sheet_selector_view_zoom_increase)
        )
    }

    SheetSelectorSection(label = stringResource(R.string.sheet_selector_view_fit_to)) {
        val selectedOption = selectedFitToOption(zoomPercent, actualSizeZoomPercent)

        SheetSelectorTextOptionRow(
            options = FitToOption.entries,
            label = { stringResource(it.labelRes()) },
            testTag = { it.testTag() },
            isSelected = { it == selectedOption },
            onSelect = { option ->
                when (option) {
                    FitToOption.WIDTH -> onFitWidth()
                    FitToOption.ACTUAL_SIZE -> onFitActualSize()
                }
            }
        )
    }
}

private fun formatZoomPercent(percent: Int): String = "$percent %"

private fun FitToOption.labelRes(): Int = when (this) {
    FitToOption.WIDTH -> R.string.sheet_selector_view_fit_width
    FitToOption.ACTUAL_SIZE -> R.string.sheet_selector_view_fit_actual
}

private fun FitToOption.testTag(): String = when (this) {
    FitToOption.WIDTH -> SheetPaneTestTags.SELECTOR_FIT_WIDTH
    FitToOption.ACTUAL_SIZE -> SheetPaneTestTags.SELECTOR_FIT_ACTUAL
}

/**
 * The pen panel: PUNTA (tip), GROSOR (width) and COLOR (`rail-spec.md` 2.2, LÁPIZ panel). ENDEREZAR
 * is not implemented — the design's own straightening engine does not exist yet (`rail-spec.md`
 * section 6).
 */
@Composable
private fun SheetPenSelectorPanel(settings: PenSettings, onChange: (PenSettings) -> Unit) {
    SheetSelectorPanelTitle(stringResource(R.string.sheet_selector_pen_title))

    SheetSelectorSection(label = stringResource(R.string.sheet_selector_pen_tip)) {
        SheetSelectorTextOptionRow(
            options = PenTipOption.entries,
            label = { stringResource(it.labelRes) },
            testTag = { it.testTag },
            isSelected = { it == PenTipOption.of(settings.tip) },
            onSelect = { onChange(settings.copy(tip = it.tip)) }
        )
    }

    SheetSelectorSection(
        label = stringResource(R.string.sheet_selector_pen_width),
        value = formatPenWidthMm(settings.widthTenthsMm)
    ) {
        SheetSelectorStepper(
            valueText = formatPenWidthMm(settings.widthTenthsMm),
            fraction = (settings.widthTenthsMm - PEN_WIDTH_MIN_TENTHS_MM).toFloat() / (PEN_WIDTH_MAX_TENTHS_MM - PEN_WIDTH_MIN_TENTHS_MM),
            onFractionSelected = { picked ->
                onChange(settings.copy(widthTenthsMm = snapToStep(PEN_WIDTH_MIN_TENTHS_MM, PEN_WIDTH_MAX_TENTHS_MM, PEN_WIDTH_STEP_TENTHS_MM, picked)))
            },
            canDecrement = settings.widthTenthsMm > PEN_WIDTH_MIN_TENTHS_MM,
            canIncrement = settings.widthTenthsMm < PEN_WIDTH_MAX_TENTHS_MM,
            onDecrement = { onChange(settings.copy(widthTenthsMm = clampPenWidthTenthsMm(settings.widthTenthsMm - PEN_WIDTH_STEP_TENTHS_MM))) },
            onIncrement = { onChange(settings.copy(widthTenthsMm = clampPenWidthTenthsMm(settings.widthTenthsMm + PEN_WIDTH_STEP_TENTHS_MM))) },
            decrementTestTag = SheetPaneTestTags.SELECTOR_WIDTH_MINUS,
            incrementTestTag = SheetPaneTestTags.SELECTOR_WIDTH_PLUS,
            valueTestTag = SheetPaneTestTags.SELECTOR_WIDTH_VALUE,
            decrementDescription = stringResource(R.string.sheet_selector_pen_width_decrease),
            incrementDescription = stringResource(R.string.sheet_selector_pen_width_increase)
        )
    }

    SheetSelectorSection(label = stringResource(R.string.sheet_selector_pen_color)) {
        val themeInkArgb = MaterialTheme.colorScheme.onSurface.toArgb()

        SheetSelectorColourRow(
            options = PenColorChoice.entries,
            selectedOption = settings.colorChoice,
            colorFor = { choice -> Color(choice.resolveArgb(themeInkArgb)) },
            nameFor = { stringResource(it.nameRes()) },
            testTag = { it.testTag },
            onSelect = { onChange(settings.copy(colorChoice = it)) }
        )
    }
}

private fun PenColorChoice.nameRes(): Int = when (this) {
    PenColorChoice.THEME -> R.string.sheet_selector_pen_color_black
    PenColorChoice.RED -> R.string.sheet_selector_pen_color_red
    PenColorChoice.BLUE -> R.string.sheet_selector_pen_color_blue
    PenColorChoice.GREEN -> R.string.sheet_selector_pen_color_green
}

/** The three tip choices the pen panel offers, paired with their [InkTip] and own label and test tag. */
private enum class PenTipOption(val tip: InkTip, val labelRes: Int, val testTag: String) {
    BALLPOINT(InkTip.BALLPOINT, R.string.sheet_selector_pen_tip_ballpoint, SheetPaneTestTags.SELECTOR_TIP_BALLPOINT),
    FOUNTAIN(InkTip.FOUNTAIN, R.string.sheet_selector_pen_tip_fountain, SheetPaneTestTags.SELECTOR_TIP_FOUNTAIN),
    PENCIL(InkTip.PENCIL, R.string.sheet_selector_pen_tip_pencil, SheetPaneTestTags.SELECTOR_TIP_PENCIL);

    companion object {
        fun of(tip: InkTip): PenTipOption = entries.first { it.tip == tip }
    }
}

/**
 * The eraser panel: TAMAÑO (size) and a destructive action that clears every stroke on the sheet
 * (`rail-spec.md` 2.2, GOMA panel). The design's MODE section — whole stroke versus partial erasing —
 * is not implemented: partial erasing has no engine yet.
 */
@Composable
private fun SheetEraserSelectorPanel(
    settings: PenSettings,
    onChange: (PenSettings) -> Unit,
    strokeCount: Int,
    onClearAll: () -> Unit
) {
    var confirmOpen by remember { mutableStateOf(false) }

    SheetSelectorPanelTitle(stringResource(R.string.sheet_selector_eraser_title))

    SheetSelectorSection(
        label = stringResource(R.string.sheet_selector_eraser_size),
        value = formatEraserSizeMm(settings.eraserSizeMm)
    ) {
        SheetSelectorStepper(
            valueText = formatEraserSizeMm(settings.eraserSizeMm),
            fraction = (settings.eraserSizeMm - ERASER_SIZE_MIN_MM).toFloat() / (ERASER_SIZE_MAX_MM - ERASER_SIZE_MIN_MM),
            onFractionSelected = { picked ->
                onChange(settings.copy(eraserSizeMm = snapToStep(ERASER_SIZE_MIN_MM, ERASER_SIZE_MAX_MM, ERASER_SIZE_STEP_MM, picked)))
            },
            canDecrement = settings.eraserSizeMm > ERASER_SIZE_MIN_MM,
            canIncrement = settings.eraserSizeMm < ERASER_SIZE_MAX_MM,
            onDecrement = { onChange(settings.copy(eraserSizeMm = clampEraserSizeMm(settings.eraserSizeMm - ERASER_SIZE_STEP_MM))) },
            onIncrement = { onChange(settings.copy(eraserSizeMm = clampEraserSizeMm(settings.eraserSizeMm + ERASER_SIZE_STEP_MM))) },
            decrementTestTag = SheetPaneTestTags.SELECTOR_ERASER_SIZE_MINUS,
            incrementTestTag = SheetPaneTestTags.SELECTOR_ERASER_SIZE_PLUS,
            valueTestTag = SheetPaneTestTags.SELECTOR_ERASER_SIZE_VALUE,
            decrementDescription = stringResource(R.string.sheet_selector_eraser_size_decrease),
            incrementDescription = stringResource(R.string.sheet_selector_eraser_size_increase)
        )
    }

    SheetEraserClearButton(enabled = strokeCount > 0, onClick = { confirmOpen = true })

    if (confirmOpen) {
        SheetEraserClearConfirmDialog(
            onDismiss = { confirmOpen = false },
            onConfirm = {
                confirmOpen = false
                onClearAll()
            }
        )
    }
}

/**
 * The panel's own destructive action, drawn in the design's alarm tone rather than as a menu item
 * (`rail-spec.md` 2.2, GOMA panel: "border: 1px solid {{c.alarma}}; color: {{c.alarma}}"). Disabled
 * and drawn muted once the sheet has no strokes left to clear.
 */
@Composable
private fun SheetEraserClearButton(enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = FoliumSpacing.touchTarget)
            .foliumBorder(1.dp, color)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(SheetPaneTestTags.SELECTOR_ERASER_CLEAR),
        contentAlignment = Alignment.Center
    ) {
        Text(text = stringResource(R.string.sheet_selector_eraser_clear), style = FoliumType.BodyMidMedium, color = color)
    }
}

/** Gates [SheetEraserClearButton] behind one confirmation naming exactly what is lost, the same way [SheetPaneRenameDialog]'s sibling dialogs do. */
@Composable
private fun SheetEraserClearConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    FoliumDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sheet_selector_eraser_clear_confirm_title)) },
        text = { Text(stringResource(R.string.sheet_selector_eraser_clear_confirm_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(SheetPaneTestTags.SELECTOR_ERASER_CLEAR_CONFIRM)
            ) {
                Text(text = stringResource(R.string.sheet_selector_eraser_clear_confirm_action), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(SheetPaneTestTags.SELECTOR_ERASER_CLEAR_CANCEL)
            ) {
                Text(stringResource(R.string.sheet_selector_eraser_clear_confirm_cancel))
            }
        }
    )
}
