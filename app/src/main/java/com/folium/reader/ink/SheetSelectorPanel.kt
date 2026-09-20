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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folium.reader.R
import com.folium.reader.core.ink.InkShape
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

/** The panel's own maximum width; clamped further to the pane by [sheetSelectorPanelWidth] (`rail-spec.md` 2.1: the artboard's own panel is about 454px wide on a 1180px canvas). */
private val PanelMaxWidth = 400.dp

/**
 * The rail's own breadth to anchor against: [RailBreadth] when the full rail is showing, or
 * [RailHiddenTabWidth] once it is collapsed to [SheetRailHiddenTab] — a pure function of visibility
 * alone, so the panel's horizontal anchor never depends on anything but that one flag.
 */
internal fun railAnchorBreadth(railHidden: Boolean): Dp = if (railHidden) RailHiddenTabWidth else RailBreadth

/**
 * The vertical offset from the rail's own top edge to [tool]'s cell's top edge, since a panel always
 * anchors to whichever rail cell is currently active. With the rail hidden, the active tool's cell is
 * always [SheetRailHiddenTab]'s own top cell, sitting flush with the tab's own top edge rather than at
 * whatever index [tool] would occupy in the full rail.
 */
internal fun railAnchorCellTopOffset(railHidden: Boolean, tool: SheetRailTool): Dp {
    if (railHidden) return 0.dp

    val index = SheetRailTools.indexOf(tool)
    return RailColumnTopPadding + (RailColumnCellHeight + RailColumnCellGap) * index
}

/**
 * The connector rule's own vertical offset: [tool]'s cell's vertical middle (`rail-spec.md` 2.1:
 * "margin-top: 30px" on a 60px cell; `design5-diff.md`, T-EscribirOculta: the tab's own 44px cell).
 */
internal fun railAnchorConnectorTopOffset(railHidden: Boolean, tool: SheetRailTool): Dp {
    val cellHeight = if (railHidden) RailHiddenTabCellSize else RailColumnCellHeight
    return railAnchorCellTopOffset(railHidden, tool) + cellHeight / 2
}

/**
 * The COLUMN-layout panel's own width: 400dp — the artboard's own panel is about 454px wide on a
 * 1180px canvas (`rail-spec.md` 2.1) — clamped to whatever room is left of the pane once the rail's
 * own breadth and the connector are subtracted. The rail sits flush with the pane's own start edge in
 * both states — docked with the full rail, or floating with the hidden tab — so no outer rail inset
 * enters this calculation, and the panel is free to reach the pane's own far edge.
 */
internal fun sheetSelectorPanelWidth(paneWidth: Dp, railBreadth: Dp = RailBreadth): Dp {
    val available = (paneWidth - railBreadth - ConnectorWidth).coerceAtLeast(0.dp)
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
    railHidden: Boolean,
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

        val railBreadth = railAnchorBreadth(railHidden)

        if (orientation == SheetPaneRailOrientation.COLUMN) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = railBreadth, y = railAnchorConnectorTopOffset(railHidden, activeTool))
                    .width(ConnectorWidth)
                    .height(ConnectorHeight)
                    .background(MaterialTheme.colorScheme.onSurface)
            )
        }

        val panelModifier = if (orientation == SheetPaneRailOrientation.COLUMN) {
            Modifier
                .align(Alignment.TopStart)
                .offset(x = railBreadth + ConnectorWidth, y = railAnchorCellTopOffset(railHidden, activeTool))
                .width(sheetSelectorPanelWidth(paneWidth, railBreadth))
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
                SheetSelectorPanel.HIGHLIGHT -> SheetHighlighterSelectorPanel(penSettings, onPenSettingsChange)
                SheetSelectorPanel.SHAPE -> SheetShapeSelectorPanel(penSettings, onPenSettingsChange)
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

    SheetSelectorHelperText(stringResource(R.string.sheet_selector_view_fit_to_helper))
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
        SheetSelectorGlyphOptionRow(
            options = PenTipOption.entries,
            label = { stringResource(it.labelRes) },
            testTag = { it.testTag },
            isSelected = { it == PenTipOption.of(settings.tip) },
            onSelect = { onChange(settings.copy(tip = it.tip)) },
            glyph = { option, tint -> drawPenTipGlyph(option.tip, tint) }
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

    SheetSelectorSection(label = stringResource(R.string.sheet_selector_pen_straighten)) {
        SheetSelectorTextOptionRow(
            options = InkStraightenMode.entries,
            label = { stringResource(it.labelRes()) },
            testTag = { it.testTag() },
            isSelected = { it == settings.straightenMode },
            onSelect = { onChange(settings.copy(straightenMode = it)) }
        )
    }

    SheetSelectorHelperText(stringResource(R.string.sheet_selector_pen_straighten_helper))
}

private fun InkStraightenMode.labelRes(): Int = when (this) {
    InkStraightenMode.NEVER -> R.string.sheet_selector_pen_straighten_never
    InkStraightenMode.ON_HOLD -> R.string.sheet_selector_pen_straighten_hold
    InkStraightenMode.ALWAYS -> R.string.sheet_selector_pen_straighten_always
}

private fun InkStraightenMode.testTag(): String = when (this) {
    InkStraightenMode.NEVER -> SheetPaneTestTags.SELECTOR_STRAIGHTEN_NEVER
    InkStraightenMode.ON_HOLD -> SheetPaneTestTags.SELECTOR_STRAIGHTEN_HOLD
    InkStraightenMode.ALWAYS -> SheetPaneTestTags.SELECTOR_STRAIGHTEN_ALWAYS
}

private fun PenColorChoice.nameRes(): Int = when (this) {
    PenColorChoice.THEME -> R.string.sheet_selector_pen_color_black
    PenColorChoice.RED -> R.string.sheet_selector_pen_color_red
    PenColorChoice.BLUE -> R.string.sheet_selector_pen_color_blue
    PenColorChoice.GREEN -> R.string.sheet_selector_pen_color_green
}

/**
 * The highlighter panel: WIDTH and COLOR (`rail-spec.md` 2.2, RESALTA panel). Every one of the five
 * colours is always offered, on every appearance including e-ink: the app cannot know an e-ink screen
 * shows monochrome only, since the same appearance is also used on a colour screen, so the helper
 * text below the row names the monochrome trade-off instead of the row hiding colours itself.
 */
@Composable
private fun SheetHighlighterSelectorPanel(settings: PenSettings, onChange: (PenSettings) -> Unit) {
    SheetSelectorPanelTitle(stringResource(R.string.sheet_selector_highlight_title))

    SheetSelectorSection(
        label = stringResource(R.string.sheet_selector_highlight_width),
        value = formatHighlighterWidthMm(settings.highlighterWidthMm)
    ) {
        SheetSelectorStepper(
            valueText = formatHighlighterWidthMm(settings.highlighterWidthMm),
            fraction = (settings.highlighterWidthMm - HIGHLIGHTER_WIDTH_MIN_MM).toFloat() / (HIGHLIGHTER_WIDTH_MAX_MM - HIGHLIGHTER_WIDTH_MIN_MM),
            onFractionSelected = { picked ->
                onChange(settings.copy(highlighterWidthMm = snapToStep(HIGHLIGHTER_WIDTH_MIN_MM, HIGHLIGHTER_WIDTH_MAX_MM, HIGHLIGHTER_WIDTH_STEP_MM, picked)))
            },
            canDecrement = settings.highlighterWidthMm > HIGHLIGHTER_WIDTH_MIN_MM,
            canIncrement = settings.highlighterWidthMm < HIGHLIGHTER_WIDTH_MAX_MM,
            onDecrement = { onChange(settings.copy(highlighterWidthMm = clampHighlighterWidthMm(settings.highlighterWidthMm - HIGHLIGHTER_WIDTH_STEP_MM))) },
            onIncrement = { onChange(settings.copy(highlighterWidthMm = clampHighlighterWidthMm(settings.highlighterWidthMm + HIGHLIGHTER_WIDTH_STEP_MM))) },
            decrementTestTag = SheetPaneTestTags.SELECTOR_HIGHLIGHT_WIDTH_MINUS,
            incrementTestTag = SheetPaneTestTags.SELECTOR_HIGHLIGHT_WIDTH_PLUS,
            valueTestTag = SheetPaneTestTags.SELECTOR_HIGHLIGHT_WIDTH_VALUE,
            decrementDescription = stringResource(R.string.sheet_selector_highlight_width_decrease),
            incrementDescription = stringResource(R.string.sheet_selector_highlight_width_increase)
        )
    }

    SheetSelectorSection(label = stringResource(R.string.sheet_selector_highlight_color)) {
        SheetSelectorColourRow(
            options = HighlighterColorChoice.entries,
            selectedOption = settings.highlighterColorChoice,
            colorFor = { choice -> Color(choice.storedArgb) },
            nameFor = { stringResource(it.nameRes()) },
            testTag = { it.testTag },
            onSelect = { onChange(settings.copy(highlighterColorChoice = it)) }
        )
    }

    SheetSelectorHelperText(stringResource(R.string.sheet_selector_highlight_color_helper))
}

private fun HighlighterColorChoice.nameRes(): Int = when (this) {
    HighlighterColorChoice.YELLOW -> R.string.sheet_selector_highlight_color_yellow
    HighlighterColorChoice.GREEN -> R.string.sheet_selector_highlight_color_green
    HighlighterColorChoice.PINK -> R.string.sheet_selector_highlight_color_pink
    HighlighterColorChoice.BLUE -> R.string.sheet_selector_highlight_color_blue
    HighlighterColorChoice.GREY -> R.string.sheet_selector_highlight_color_grey
}

/**
 * The shapes the SHAPE tool panel itself offers: [InkShape.TRIANGLE] is deliberately absent, since it
 * is only ever recognised from a straightened freehand stroke, never chosen from this panel.
 */
private val SHAPE_PANEL_OPTIONS = listOf(InkShape.LINE, InkShape.ARROW, InkShape.BOX, InkShape.ELLIPSE)

/**
 * The shape panel: FIGURA, its own GROSOR (width) and its own COLOR, independent of the pen's
 * (`rail-spec.md` 2.2, FORMA panel). A shape still commits as an ordinary [InkTool.PEN] stroke
 * ([InkSurfaceTool.SHAPE]'s own contract), so a THEME-coloured shape keeps following the theme's own
 * ink exactly as a THEME-coloured pen stroke does.
 */
@Composable
private fun SheetShapeSelectorPanel(settings: PenSettings, onChange: (PenSettings) -> Unit) {
    SheetSelectorPanelTitle(stringResource(R.string.sheet_selector_shape_title))

    SheetSelectorSection(label = stringResource(R.string.sheet_selector_shape_figure)) {
        SheetSelectorGlyphOptionRow(
            options = SHAPE_PANEL_OPTIONS,
            label = { stringResource(it.labelRes()) },
            testTag = { it.testTag() },
            isSelected = { it == settings.shape },
            onSelect = { onChange(settings.copy(shape = it)) },
            glyph = { shape, tint -> drawShapeOptionGlyph(shape, tint) }
        )
    }

    SheetSelectorSection(
        label = stringResource(R.string.sheet_selector_shape_width),
        value = formatPenWidthMm(settings.shapeWidthTenthsMm)
    ) {
        SheetSelectorStepper(
            valueText = formatPenWidthMm(settings.shapeWidthTenthsMm),
            fraction = (settings.shapeWidthTenthsMm - PEN_WIDTH_MIN_TENTHS_MM).toFloat() / (PEN_WIDTH_MAX_TENTHS_MM - PEN_WIDTH_MIN_TENTHS_MM),
            onFractionSelected = { picked ->
                onChange(settings.copy(shapeWidthTenthsMm = snapToStep(PEN_WIDTH_MIN_TENTHS_MM, PEN_WIDTH_MAX_TENTHS_MM, PEN_WIDTH_STEP_TENTHS_MM, picked)))
            },
            canDecrement = settings.shapeWidthTenthsMm > PEN_WIDTH_MIN_TENTHS_MM,
            canIncrement = settings.shapeWidthTenthsMm < PEN_WIDTH_MAX_TENTHS_MM,
            onDecrement = { onChange(settings.copy(shapeWidthTenthsMm = clampPenWidthTenthsMm(settings.shapeWidthTenthsMm - PEN_WIDTH_STEP_TENTHS_MM))) },
            onIncrement = { onChange(settings.copy(shapeWidthTenthsMm = clampPenWidthTenthsMm(settings.shapeWidthTenthsMm + PEN_WIDTH_STEP_TENTHS_MM))) },
            decrementTestTag = SheetPaneTestTags.SELECTOR_SHAPE_WIDTH_MINUS,
            incrementTestTag = SheetPaneTestTags.SELECTOR_SHAPE_WIDTH_PLUS,
            valueTestTag = SheetPaneTestTags.SELECTOR_SHAPE_WIDTH_VALUE,
            decrementDescription = stringResource(R.string.sheet_selector_shape_width_decrease),
            incrementDescription = stringResource(R.string.sheet_selector_shape_width_increase)
        )
    }

    SheetSelectorSection(label = stringResource(R.string.sheet_selector_shape_color)) {
        val themeInkArgb = MaterialTheme.colorScheme.onSurface.toArgb()

        SheetSelectorColourRow(
            options = PenColorChoice.entries,
            selectedOption = settings.shapeColorChoice,
            colorFor = { choice -> Color(choice.resolveArgb(themeInkArgb)) },
            nameFor = { stringResource(it.nameRes()) },
            testTag = { it.shapeTestTag() },
            onSelect = { onChange(settings.copy(shapeColorChoice = it)) }
        )
    }
}

private fun PenColorChoice.shapeTestTag(): String = when (this) {
    PenColorChoice.THEME -> SheetPaneTestTags.SELECTOR_SHAPE_COLOUR_BLACK
    PenColorChoice.RED -> SheetPaneTestTags.SELECTOR_SHAPE_COLOUR_RED
    PenColorChoice.BLUE -> SheetPaneTestTags.SELECTOR_SHAPE_COLOUR_BLUE
    PenColorChoice.GREEN -> SheetPaneTestTags.SELECTOR_SHAPE_COLOUR_GREEN
}

/** Never called on [InkShape.TRIANGLE]: [SHAPE_PANEL_OPTIONS] never offers it, since it is only ever recognised from a straightened freehand stroke. */
private fun triangleNeverOffered(): Nothing =
    error("InkShape.TRIANGLE is not offered by the shape panel; it only appears from the recogniser")

private fun InkShape.labelRes(): Int = when (this) {
    InkShape.LINE -> R.string.sheet_selector_shape_line
    InkShape.ARROW -> R.string.sheet_selector_shape_arrow
    InkShape.BOX -> R.string.sheet_selector_shape_box
    InkShape.ELLIPSE -> R.string.sheet_selector_shape_ellipse
    InkShape.TRIANGLE -> triangleNeverOffered()
}

private fun InkShape.testTag(): String = when (this) {
    InkShape.LINE -> SheetPaneTestTags.SELECTOR_SHAPE_LINE
    InkShape.ARROW -> SheetPaneTestTags.SELECTOR_SHAPE_ARROW
    InkShape.BOX -> SheetPaneTestTags.SELECTOR_SHAPE_BOX
    InkShape.ELLIPSE -> SheetPaneTestTags.SELECTOR_SHAPE_ELLIPSE
    InkShape.TRIANGLE -> triangleNeverOffered()
}

private fun DrawScope.drawShapeOptionGlyph(shape: InkShape, tint: Color) = when (shape) {
    InkShape.LINE -> drawShapeOptionLineGlyph(tint)
    InkShape.ARROW -> drawShapeOptionArrowGlyph(tint)
    InkShape.BOX -> drawShapeOptionBoxGlyph(tint)
    InkShape.ELLIPSE -> drawShapeOptionEllipseGlyph(tint)
    InkShape.TRIANGLE -> triangleNeverOffered()
}

private fun InkEraserMode.labelRes(): Int = when (this) {
    InkEraserMode.WHOLE_STROKE -> R.string.sheet_selector_eraser_mode_whole_stroke
    InkEraserMode.PARTIAL -> R.string.sheet_selector_eraser_mode_partial
}

private fun InkEraserMode.testTag(): String = when (this) {
    InkEraserMode.WHOLE_STROKE -> SheetPaneTestTags.SELECTOR_ERASER_MODE_WHOLE
    InkEraserMode.PARTIAL -> SheetPaneTestTags.SELECTOR_ERASER_MODE_PARTIAL
}

private fun DrawScope.drawEraserModeOptionGlyph(mode: InkEraserMode, tint: Color) = when (mode) {
    InkEraserMode.WHOLE_STROKE -> drawEraserModeWholeStrokeGlyph(tint)
    InkEraserMode.PARTIAL -> drawEraserModePartialGlyph(tint)
}

private fun DrawScope.drawPenTipGlyph(tip: InkTip, tint: Color) = when (tip) {
    InkTip.BALLPOINT -> drawPenTipBallpointGlyph(tint)
    InkTip.FOUNTAIN -> drawPenTipFountainGlyph(tint)
    InkTip.PENCIL -> drawPenTipPencilGlyph(tint)
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
 * The eraser panel: MODO (whole stroke versus partial erasing), TAMAÑO (size, shared by both modes),
 * and a destructive action that clears every stroke on the sheet (`rail-spec.md` 2.2, GOMA panel).
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

    SheetSelectorSection(label = stringResource(R.string.sheet_selector_eraser_mode)) {
        SheetSelectorGlyphOptionRow(
            options = InkEraserMode.entries,
            label = { stringResource(it.labelRes()) },
            testTag = { it.testTag() },
            isSelected = { it == settings.eraserMode },
            onSelect = { onChange(settings.copy(eraserMode = it)) },
            glyph = { mode, tint -> drawEraserModeOptionGlyph(mode, tint) }
        )
    }

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
