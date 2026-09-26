package com.folium.reader.ink

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How far the rail and the content beside it keep in from the top and bottom of the dock. The rail's
 * pair is also the selector overlay's, since a panel anchors to the rail's own cells; [rowBottom] is
 * what a compact row keeps clear below itself.
 */
internal data class SheetDockInsets(
    val railTop: Dp,
    val railBottom: Dp,
    val contentTop: Dp,
    val contentBottom: Dp,
    val rowBottom: Dp
)

/**
 * The sheet screen's own insets: [SheetBodyPadding] on every side of both the rail and the surface in
 * a column (T-Lapiz, T-Hoja), and nothing at all in a compact row, where the surface runs from the
 * top bar down to the row.
 */
internal fun standaloneSheetDockInsets(orientation: SheetPaneRailOrientation): SheetDockInsets =
    when (orientation) {
        SheetPaneRailOrientation.COLUMN -> SheetDockInsets(
            railTop = SheetBodyPadding,
            railBottom = SheetBodyPadding,
            contentTop = SheetBodyPadding,
            contentBottom = SheetBodyPadding,
            rowBottom = 0.dp
        )
        SheetPaneRailOrientation.ROW -> SheetDockInsets(0.dp, 0.dp, 0.dp, 0.dp, 0.dp)
    }

/**
 * A sheet's tool rail laid out beside [content], with the selector panels anchored to it. The one
 * rail both the sheet screen and the reader draw: [tools] is the state the sheet's own [SheetPane]
 * reads, so the rail acts on that pane's live surface wherever the pane itself sits.
 *
 * In a column, [SheetBodyPadding] in from each side, the rail — or its hidden tab — then
 * [SheetRailGap], then [content] in the rest of the width (see [sheetDockColumnGeometry]). In a
 * compact row, [content] above and the row below it, full width. With no [orientation] or no
 * [tools], [content] fills the dock alone. [content] is always composed at the same place whichever
 * of the three applies, so a host that shows the rail only some of the time — the reader, on a sheet
 * — never loses what [content] remembers when the rail comes or goes. [onNewSheet] puts "+ SHEET" in
 * a column's foot; `null` leaves it out. While not [toolsEnabled] the rail is drawn muted and ignores
 * taps and no selector panel opens, so a host can keep the rail's place while there is nothing live
 * for it to act on.
 */
@Composable
internal fun SheetToolDock(
    tools: SheetTools?,
    orientation: SheetPaneRailOrientation?,
    penSettings: PenSettings,
    onPenSettingsChange: (PenSettings) -> Unit,
    insets: SheetDockInsets,
    onNewSheet: (() -> Unit)?,
    newSheetEnabled: Boolean,
    modifier: Modifier = Modifier,
    toolsEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val railHidden = tools?.selectorState?.railHidden == true
    val railOrientation = orientation.takeIf { tools != null }
    val toolScroll = rememberScrollState()
    val railCellCount = railOrientation?.let { sheetRailCells(it, canCreateSheet = onNewSheet != null).size } ?: 0

    Layout(
        content = {
            Box(Modifier.layoutId(SheetDockSlot.CONTENT)) { content() }

            if (tools != null && railOrientation != null) {
                BoxWithConstraints(Modifier.layoutId(SheetDockSlot.RAIL)) {
                    val metrics = railColumnMetrics(maxHeight, railCellCount)

                    SheetDockRail(tools, railOrientation, metrics, toolScroll, penSettings, onPenSettingsChange, onNewSheet, newSheetEnabled, toolsEnabled)
                }

                BoxWithConstraints(Modifier.layoutId(SheetDockSlot.OVERLAY)) {
                    if (toolsEnabled) {
                        val metrics = railColumnMetrics(maxHeight, railCellCount)
                        val scrolled = with(LocalDensity.current) { toolScroll.value.toDp() }

                        SheetDockSelectorOverlay(tools, railOrientation, maxWidth, metrics, scrolled, penSettings, onPenSettingsChange)
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val content = measurables.first { it.layoutId == SheetDockSlot.CONTENT }
        val rail = measurables.firstOrNull { it.layoutId == SheetDockSlot.RAIL }
        val overlay = measurables.firstOrNull { it.layoutId == SheetDockSlot.OVERLAY }

        fun fixed(slotWidth: Int, slotHeight: Int) =
            Constraints.fixed(slotWidth.coerceAtLeast(0), slotHeight.coerceAtLeast(0))

        if (rail == null || overlay == null || railOrientation == null) {
            val placeable = content.measure(fixed(width, height))
            return@Layout layout(width, height) { placeable.place(0, 0) }
        }

        if (railOrientation == SheetPaneRailOrientation.ROW) {
            val rowBottom = insets.rowBottom.roundToPx()
            val contentTop = insets.contentTop.roundToPx()
            val railPlaceable = rail.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height.coerceAtLeast(0)))
            val railTop = height - rowBottom - railPlaceable.height
            val contentPlaceable = content.measure(fixed(width, railTop - contentTop))
            val overlayPlaceable = overlay.measure(fixed(width, height - rowBottom))

            return@Layout layout(width, height) {
                contentPlaceable.place(0, contentTop)
                railPlaceable.place(0, railTop)
                overlayPlaceable.place(0, 0)
            }
        }

        val geometry = sheetDockColumnGeometry(width.toDp(), railHidden)
        val railTop = insets.railTop.roundToPx()
        val railHeight = height - railTop - insets.railBottom.roundToPx()
        val contentTop = insets.contentTop.roundToPx()
        val contentHeight = height - contentTop - insets.contentBottom.roundToPx()
        val railStart = geometry.railStart.roundToPx()

        val railPlaceable = rail.measure(fixed(geometry.railWidth.roundToPx(), railHeight))
        val contentPlaceable = content.measure(fixed(geometry.contentWidth.roundToPx(), contentHeight))
        val overlayPlaceable = overlay.measure(fixed(width - railStart - SheetBodyPadding.roundToPx(), railHeight))

        layout(width, height) {
            railPlaceable.place(railStart, railTop)
            contentPlaceable.place(geometry.contentStart.roundToPx(), contentTop)
            overlayPlaceable.place(railStart, railTop)
        }
    }
}

private enum class SheetDockSlot { CONTENT, RAIL, OVERLAY }

/**
 * The rail itself at [metrics], its tools scrolling through [toolScroll] when they must, or its hidden
 * tab at the top of the rail's slot; hiding and showing are remembered in [PenSettings]. Either one is
 * muted and ignores taps while not [enabled].
 */
@Composable
private fun SheetDockRail(
    tools: SheetTools,
    orientation: SheetPaneRailOrientation,
    metrics: RailColumnMetrics,
    toolScroll: ScrollState,
    penSettings: PenSettings,
    onPenSettingsChange: (PenSettings) -> Unit,
    onNewSheet: (() -> Unit)?,
    newSheetEnabled: Boolean,
    enabled: Boolean
) {
    val selector = tools.selectorState

    if (orientation == SheetPaneRailOrientation.COLUMN && selector.railHidden) {
        Box(Modifier.fillMaxSize()) {
            SheetRailHiddenTab(
                onShowTapped = {
                    tools.reduce(SheetSelectorEvent.RailShown)
                    onPenSettingsChange(penSettings.copy(railHidden = false))
                },
                modifier = Modifier.align(Alignment.TopStart),
                enabled = enabled
            )
        }
        return
    }

    SheetToolRail(
        orientation = orientation,
        activeTool = selector.activeTool,
        cells = sheetRailCells(orientation, canCreateSheet = onNewSheet != null),
        onToolTapped = tools::toolTapped,
        onNewSheet = { onNewSheet?.invoke() },
        newSheetEnabled = newSheetEnabled,
        onHideTapped = {
            tools.reduce(SheetSelectorEvent.RailHidden)
            onPenSettingsChange(penSettings.copy(railHidden = true))
        },
        metrics = metrics,
        toolScroll = toolScroll,
        enabled = enabled
    )
}

/** [SheetSelectorOverlay] wired to [tools]' own state and live surface, anchored to the rail as drawn at [railMetrics] and scrolled by [toolScroll]. */
@Composable
private fun SheetDockSelectorOverlay(
    tools: SheetTools,
    orientation: SheetPaneRailOrientation,
    paneWidth: Dp,
    railMetrics: RailColumnMetrics,
    toolScroll: Dp,
    penSettings: PenSettings,
    onPenSettingsChange: (PenSettings) -> Unit
) {
    val selector = tools.selectorState
    val surface = tools.surface
    val viewport = tools.viewport
    val xdpi = LocalContext.current.resources.displayMetrics.xdpi
    val zoomPercent = viewport?.let { zoomPercentOf(it.zoom) } ?: ZOOM_MIN_PERCENT
    val actualSizeZoomPercent = viewport?.let { zoomPercentOf(actualSizeZoom(xdpi, it.viewWidthPx)) } ?: ZOOM_MIN_PERCENT

    SheetSelectorOverlay(
        orientation = orientation,
        paneWidth = paneWidth,
        railHidden = selector.railHidden,
        activeTool = selector.activeTool,
        openPanel = selector.openPanel,
        penSettings = penSettings,
        onPenSettingsChange = onPenSettingsChange,
        zoomPercent = zoomPercent,
        actualSizeZoomPercent = actualSizeZoomPercent,
        onZoomPercentChange = { percent -> surface?.setZoom(zoomFractionOf(percent)) },
        onFitWidth = { surface?.fitWidth() },
        onFitActualSize = { surface?.setZoom(actualSizeZoom(xdpi, viewport?.viewWidthPx ?: 1f)) },
        strokeCount = tools.strokeCount,
        onClearAll = { surface?.clearAll() },
        onOutsideTapped = { tools.reduce(SheetSelectorEvent.OutsideTapped) },
        onBackPressed = { tools.reduce(SheetSelectorEvent.BackPressed) },
        selectionTextAttributes = surface?.selectedTextAttributes(),
        onSelectionTextFont = { font -> surface?.setSelectedTextFont(font) },
        onSelectionTextSizePt = { sizePt -> surface?.setSelectedTextSizePt(sizePt) },
        onSelectionTextStyle = { style -> surface?.setSelectedTextStyle(style) },
        onSelectionTextAlignment = { alignment -> surface?.setSelectedTextAlignment(alignment) },
        onSelectionTextColorArgb = { colorArgb -> surface?.setSelectedTextColorArgb(colorArgb) },
        editingTextAttributes = tools.editingTextAttributes,
        onEditingTextFont = { font -> surface?.setEditingTextFont(font) },
        onEditingTextSizePt = { sizePt -> surface?.setEditingTextSizePt(sizePt) },
        onEditingTextStyle = { style -> surface?.setEditingTextStyle(style) },
        onEditingTextAlignment = { alignment -> surface?.setEditingTextAlignment(alignment) },
        onEditingTextColorArgb = { colorArgb -> surface?.setEditingTextColorArgb(colorArgb) },
        railMetrics = railMetrics,
        toolScroll = toolScroll,
        onPage = surface?.drawsOnPage == true
    )
}
