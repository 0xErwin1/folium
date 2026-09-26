package com.folium.reader.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folium.reader.R
import com.folium.reader.reader.drawNewSheetGlyph
import com.folium.reader.ui.FoliumColors
import com.folium.reader.ui.FoliumDivider
import com.folium.reader.ui.FoliumRuleEdge
import com.folium.reader.ui.FoliumType
import com.folium.reader.ui.FoliumWidthClass
import com.folium.reader.ui.foliumBorder
import com.folium.reader.ui.foliumRule

/** Which axis the tool rail lays its cells out along. */
internal enum class SheetPaneRailOrientation { COLUMN, ROW }

/**
 * Whether the tool rail runs down the left edge or along the bottom, mirroring the design's own
 * split between a tablet's vertical rail (T-Lapiz, T-Hoja) and a phone-width row (S-Escribir).
 */
internal fun sheetPaneRailOrientation(widthClass: FoliumWidthClass): SheetPaneRailOrientation =
    if (widthClass == FoliumWidthClass.COMPACT) SheetPaneRailOrientation.ROW else SheetPaneRailOrientation.COLUMN

/**
 * A tool the rail draws a cell for, with its own glyph and test tag. Only tools with a working
 * engine behind them get an entry here: a future tool is an additive entry, never a disabled
 * placeholder.
 */
internal enum class SheetRailTool(val labelRes: Int, val testTag: String, val glyph: DrawScope.(Color) -> Unit) {
    VIEW(R.string.sheet_pane_tool_view, SheetPaneTestTags.TOOL_VIEW, { tint -> drawViewRailGlyph(tint) }),
    PEN(R.string.sheet_pane_tool_pen, SheetPaneTestTags.TOOL_PEN, { tint -> drawPenRailGlyph(tint) }),
    HIGHLIGHT(R.string.sheet_pane_tool_highlight, SheetPaneTestTags.TOOL_HIGHLIGHT, { tint -> drawHighlightRailGlyph(tint) }),
    TEXT(R.string.sheet_pane_tool_text, SheetPaneTestTags.TOOL_TEXT, { tint -> drawTextRailGlyph(tint) }),
    SHAPE(R.string.sheet_pane_tool_shape, SheetPaneTestTags.TOOL_SHAPE, { tint -> drawShapeRailGlyph(tint) }),
    SELECT(R.string.sheet_pane_tool_select, SheetPaneTestTags.TOOL_SELECT, { tint -> drawSelectRailGlyph(tint) }),
    ERASER(R.string.sheet_pane_tool_eraser, SheetPaneTestTags.TOOL_ERASER, { tint -> drawEraserRailGlyph(tint) })
}

/** The drawing surface tool [SheetRailTool] drives. */
internal fun SheetRailTool.toSurfaceTool(): InkSurfaceTool = when (this) {
    SheetRailTool.VIEW -> InkSurfaceTool.VIEW
    SheetRailTool.PEN -> InkSurfaceTool.PEN
    SheetRailTool.HIGHLIGHT -> InkSurfaceTool.HIGHLIGHTER
    SheetRailTool.TEXT -> InkSurfaceTool.TEXT
    SheetRailTool.SHAPE -> InkSurfaceTool.SHAPE
    SheetRailTool.SELECT -> InkSurfaceTool.SELECT
    SheetRailTool.ERASER -> InkSurfaceTool.ERASER
}

/** The design's tool list filtered down to the tools this rail actually implements, in the design's own order. */
internal val SheetRailTools: List<SheetRailTool> = SheetRailTool.entries

/** One cell of the rail: a tool, the foot's "+ SHEET", or the foot's HIDE. */
internal sealed interface SheetRailCell {
    data class Tool(val tool: SheetRailTool) : SheetRailCell
    data object NewSheet : SheetRailCell
    data object Hide : SheetRailCell
}

/**
 * The cells the rail draws, in order. A column (T-Lapiz, T-Hoja) lists every tool, then its foot:
 * "+ SHEET" when [canCreateSheet] — a sheet read in a book, never the sheet screen opened from the
 * library — and HIDE. A compact row (S-Escribir) carries the tools alone: the phone design puts
 * "+ sheet" in the header and has no way to hide the row.
 */
internal fun sheetRailCells(orientation: SheetPaneRailOrientation, canCreateSheet: Boolean): List<SheetRailCell> {
    val tools = SheetRailTools.map { SheetRailCell.Tool(it) }
    if (orientation == SheetPaneRailOrientation.ROW) return tools

    val newSheet = if (canCreateSheet) listOf(SheetRailCell.NewSheet) else emptyList()
    return tools + newSheet + SheetRailCell.Hide
}

/**
 * The content row's own padding on every side and the gap between the rail and what it writes on
 * (T-Lapiz, T-Hoja: "padding: 24px; gap: 24px").
 */
internal val SheetBodyPadding = 24.dp
internal val SheetRailGap = 24.dp

/**
 * Where a column rail and the content beside it sit across [availableWidth]: [SheetBodyPadding] in
 * from the start, the rail's own width — [RailBreadth], or [RailHiddenTabWidth] while hidden — then
 * [SheetRailGap], and the content in everything left before the far [SheetBodyPadding].
 */
internal data class SheetDockColumnGeometry(val railStart: Dp, val railWidth: Dp, val contentStart: Dp, val contentWidth: Dp)

internal fun sheetDockColumnGeometry(availableWidth: Dp, railHidden: Boolean): SheetDockColumnGeometry {
    val railWidth = if (railHidden) RailHiddenTabWidth else RailBreadth
    val contentStart = SheetBodyPadding + railWidth + SheetRailGap
    val contentWidth = (availableWidth - contentStart - SheetBodyPadding).coerceAtLeast(0.dp)

    return SheetDockColumnGeometry(SheetBodyPadding, railWidth, contentStart, contentWidth)
}

/**
 * The compact layout's own side margin for the selector panel that opens above the row, so the
 * panel does not run into the screen's edges.
 */
internal val CompactPanelMargin = 16.dp

/** The column rail's breadth, its cell height, the gap between cells and its vertical padding (T-Lapiz: 80px wide, 64x60 cells, gap 4px, padding 8px 0). */
internal val RailBreadth = 80.dp
internal val RailColumnCellHeight = 60.dp
internal val RailColumnCellGap = 4.dp
internal val RailColumnTopPadding = 8.dp
private val RailColumnCellWidth = 64.dp
private val RailColumnGlyphSize = 22.dp
private val RailLabelGap = 3.dp

/** The column foot's short rule (T-Lapiz: "width: 40px; height: 1px; margin: 6px 0"). */
private val RailFootRuleWidth = 40.dp
private val RailFootRuleMargin = 6.dp

/** The compact row's cells and padding (S-Escribir: 44x48 cells, "padding: 0 8px 12px 8px", a 1px top rule). */
private val RailRowCellWidth = 44.dp
private val RailRowCellHeight = 48.dp
private val RailRowSidePadding = 8.dp
private val RailRowBottomPadding = 12.dp
private val RailRowGlyphSize = 20.dp
private val RailRuleWidth = 1.dp

/** The compact row's full height, which a selector panel opening above it clears. */
internal val RailRowHeight: Dp = RailRuleWidth + RailRowCellHeight + RailRowBottomPadding

/** The hidden rail's tab: a chevron over an "OPEN" label (T-EscribirOculta, as revised: 44x56). */
internal val RailHiddenTabWidth = 44.dp
internal val RailHiddenTabHeight = 56.dp
private val RailHiddenTabGlyphSize = 20.dp

/**
 * The tool rail. It carries no summary of the pen: a tool's settings are reached only by tapping the
 * tool that is already active. As a column it spans its slot's full height inside a hairline border
 * on paper, its tools from the top and its foot — a short rule, then [cells]' "+ SHEET" and HIDE — at
 * the bottom. As a compact row it spreads its tools icon-only across the width under a hairline rule.
 */
@Composable
internal fun SheetToolRail(
    orientation: SheetPaneRailOrientation,
    activeTool: SheetRailTool,
    cells: List<SheetRailCell>,
    onToolTapped: (SheetRailTool) -> Unit,
    onNewSheet: () -> Unit,
    newSheetEnabled: Boolean,
    onHideTapped: () -> Unit,
    modifier: Modifier = Modifier
) {
    val line = FoliumColors.line
    val paper = MaterialTheme.colorScheme.surface

    if (orientation == SheetPaneRailOrientation.ROW) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(paper)
                .foliumRule(FoliumRuleEdge.TOP, RailRuleWidth, line)
                .padding(start = RailRowSidePadding, top = RailRuleWidth, end = RailRowSidePadding, bottom = RailRowBottomPadding)
                .testTag(SheetPaneTestTags.TOOL_RAIL),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            cells.filterIsInstance<SheetRailCell.Tool>().forEach { cell ->
                SheetRailRowCell(tool = cell.tool, active = cell.tool == activeTool, onClick = { onToolTapped(cell.tool) })
            }
        }
        return
    }

    Column(
        modifier = modifier
            .width(RailBreadth)
            .fillMaxHeight()
            .background(paper)
            .foliumBorder(RailRuleWidth, line)
            .padding(vertical = RailColumnTopPadding)
            .testTag(SheetPaneTestTags.TOOL_RAIL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RailColumnCellGap)
    ) {
        cells.filterIsInstance<SheetRailCell.Tool>().forEach { cell ->
            SheetRailColumnCell(
                glyph = cell.tool.glyph,
                label = stringResource(cell.tool.labelRes),
                active = cell.tool == activeTool,
                testTag = cell.tool.testTag,
                onClick = { onToolTapped(cell.tool) }
            )
        }

        Spacer(Modifier.weight(1f))

        FoliumDivider.Horizontal(
            modifier = Modifier.width(RailFootRuleWidth).padding(vertical = RailFootRuleMargin),
            color = line
        )

        if (SheetRailCell.NewSheet in cells) {
            SheetRailColumnCell(
                glyph = { tint -> drawNewSheetGlyph(tint) },
                label = stringResource(R.string.sheet_pane_tool_rail_new_sheet),
                description = stringResource(R.string.reader_new_sheet),
                enabled = newSheetEnabled,
                testTag = SheetPaneTestTags.TOOL_RAIL_NEW_SHEET,
                onClick = onNewSheet
            )
        }

        if (SheetRailCell.Hide in cells) {
            SheetRailColumnCell(
                glyph = { tint -> drawHideRailGlyph(tint) },
                label = stringResource(R.string.sheet_pane_tool_rail_hide),
                testTag = SheetPaneTestTags.TOOL_RAIL_HIDE,
                onClick = onHideTapped
            )
        }
    }
}

/**
 * One column cell: its glyph over its uppercase label, inverted — ink ground, paper mark — while
 * [active]. [description] names the cell to accessibility when its visible label is not a full name.
 */
@Composable
private fun SheetRailColumnCell(
    glyph: DrawScope.(Color) -> Unit,
    label: String,
    testTag: String,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
    description: String = label
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val tint = when {
        active -> paper
        enabled -> ink
        else -> ink.copy(alpha = 0.38f)
    }

    Column(
        modifier = Modifier
            .size(RailColumnCellWidth, RailColumnCellHeight)
            .background(if (active) ink else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(Modifier.size(RailColumnGlyphSize)) { glyph(tint) }
        Spacer(Modifier.height(RailLabelGap))
        Text(text = label.uppercase(), style = FoliumType.RailLabel, color = tint)
    }
}

/** One compact row cell: the tool's glyph alone, inverted while [active]; its name stays its content description. */
@Composable
private fun SheetRailRowCell(tool: SheetRailTool, active: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val label = stringResource(tool.labelRes)

    Column(
        modifier = Modifier
            .size(RailRowCellWidth, RailRowCellHeight)
            .background(if (active) ink else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tool.testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(Modifier.size(RailRowGlyphSize)) { tool.glyph(this, if (active) paper else ink) }
    }
}

/**
 * The hidden rail's tab: a bordered 44x56 handle holding a chevron over an "OPEN" label, tapping
 * anywhere on it, border included, bringing the rail back. It sits at the top of the rail's own slot
 * on its own paper.
 */
@Composable
internal fun SheetRailHiddenTab(onShowTapped: () -> Unit, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val description = stringResource(R.string.sheet_pane_tool_rail_show)

    Column(
        modifier = modifier
            .size(RailHiddenTabWidth, RailHiddenTabHeight)
            .background(MaterialTheme.colorScheme.surface)
            .foliumBorder(RailRuleWidth, FoliumColors.line)
            .clickable(onClick = onShowTapped)
            .semantics { contentDescription = description }
            .testTag(SheetPaneTestTags.TOOL_RAIL_TAB),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(Modifier.size(RailHiddenTabGlyphSize)) { drawShowRailGlyph(ink) }
        Spacer(Modifier.height(RailLabelGap))
        Text(text = stringResource(R.string.sheet_pane_tool_rail_open).uppercase(), style = FoliumType.RailLabel, color = ink)
    }
}
