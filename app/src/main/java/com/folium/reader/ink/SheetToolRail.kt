package com.folium.reader.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.folium.reader.ui.FoliumType
import com.folium.reader.ui.FoliumWidthClass
import com.folium.reader.ui.foliumBorder

/** Which axis [SheetPane]'s tool rail lays its cells out along. */
internal enum class SheetPaneRailOrientation { COLUMN, ROW }

/**
 * Whether the tool rail runs down the left edge or along the bottom, mirroring the design's own
 * split between a tablet's vertical rail (T-Lapiz.dc.html, T-Selectores.dc.html) and a phone-width
 * row.
 */
internal fun sheetPaneRailOrientation(widthClass: FoliumWidthClass): SheetPaneRailOrientation =
    if (widthClass == FoliumWidthClass.COMPACT) SheetPaneRailOrientation.ROW else SheetPaneRailOrientation.COLUMN

/**
 * The body's own outer padding and, between the rail and the drawing surface, its own gap: on a
 * non-compact width class, the rail is a free-standing bordered box rather than a column flush with
 * the pane's edges, so every one of its four `foliumBorder` edges shows paper around it instead of
 * three of them coinciding with the pane's own bounds (`D3/T-Lapiz.dc.html:29`, "padding: 24px; gap:
 * 24px").
 */
internal val SheetPaneBodyPadding = 24.dp
internal val SheetPaneBodyGap = 24.dp

/** The body's own padding, gap and compact-row margin for [widthClass], never read from a live composition so it stays pure and JVM-testable. */
internal data class SheetPaneBodyLayout(val outerPadding: Dp, val gap: Dp, val compactRailMargin: Dp)

internal fun sheetPaneBodyLayout(widthClass: FoliumWidthClass): SheetPaneBodyLayout =
    when (sheetPaneRailOrientation(widthClass)) {
        SheetPaneRailOrientation.COLUMN -> SheetPaneBodyLayout(SheetPaneBodyPadding, SheetPaneBodyGap, compactRailMargin = 0.dp)
        SheetPaneRailOrientation.ROW -> SheetPaneBodyLayout(outerPadding = 0.dp, gap = 0.dp, CompactPanelMargin)
    }

/**
 * A tool the rail draws a cell for, with its own glyph and test tag. Only tools with a working
 * engine behind them get an entry here: a future tool is an additive entry, never a disabled
 * placeholder.
 */
internal enum class SheetRailTool(val labelRes: Int, val testTag: String, val glyph: DrawScope.(Color) -> Unit) {
    VIEW(R.string.sheet_pane_tool_view, SheetPaneTestTags.TOOL_VIEW, { tint -> drawViewRailGlyph(tint) }),
    PEN(R.string.sheet_pane_tool_pen, SheetPaneTestTags.TOOL_PEN, { tint -> drawPenRailGlyph(tint) }),
    HIGHLIGHT(R.string.sheet_pane_tool_highlight, SheetPaneTestTags.TOOL_HIGHLIGHT, { tint -> drawHighlightRailGlyph(tint) }),
    SHAPE(R.string.sheet_pane_tool_shape, SheetPaneTestTags.TOOL_SHAPE, { tint -> drawShapeRailGlyph(tint) }),
    ERASER(R.string.sheet_pane_tool_eraser, SheetPaneTestTags.TOOL_ERASER, { tint -> drawEraserRailGlyph(tint) })
}

/** The drawing surface tool [SheetRailTool] drives. */
internal fun SheetRailTool.toSurfaceTool(): InkSurfaceTool = when (this) {
    SheetRailTool.VIEW -> InkSurfaceTool.VIEW
    SheetRailTool.PEN -> InkSurfaceTool.PEN
    SheetRailTool.HIGHLIGHT -> InkSurfaceTool.HIGHLIGHTER
    SheetRailTool.SHAPE -> InkSurfaceTool.SHAPE
    SheetRailTool.ERASER -> InkSurfaceTool.ERASER
}

/** The nine-tool list (`D3/T-Lapiz.dc.html:98-108`) filtered down to the tools this rail actually implements, in the design's own order. */
internal val SheetRailTools: List<SheetRailTool> = SheetRailTool.entries

/**
 * The compact layout's own side margin, shared by the bottom tool row and the selector panel that
 * opens above it, so the panel's edges line up with the row's (`D3/P-Partida.dc.html` L18; the panel
 * margin is task instructions, not read from an artboard).
 */
internal val CompactPanelMargin = 16.dp

/** The rail's own breadth, its column cell height, and the gap between cells: shared with [SheetSelectorPanel]'s anchor geometry, which anchors to the PEN cell without a rail of its own. */
internal val RailBreadth = 80.dp
internal val RailColumnCellHeight = 60.dp
internal val RailColumnCellGap = 4.dp
internal val RailColumnTopPadding = 8.dp
internal val RailRowCellHeight = 52.dp
private val RailColumnCellWidth = 64.dp
private val RailRowCellWidth = 64.dp
private val RailColumnGlyphSize = 22.dp
private val RailRowGlyphSize = 20.dp

/**
 * The tool rail: the tools this app implements, in the design's own order. It carries no summary of
 * the pen: "La barra no lleva ningún resumen de punta: el color y el grosor se ven en el selector"
 * (`canvas.json`, `nota-t-selectores`), so a tool's settings are reached only by tapping the tool that
 * is already active. The design's foot holds "+ HOJA" alone, under a short rule; neither is drawn
 * until a sheet can be attached to a book page. Laid out as a left column on a tablet-width window
 * and as a bottom row on a phone-width one (`P-Partida.dc.html`).
 */
@Composable
internal fun SheetPaneToolRail(
    orientation: SheetPaneRailOrientation,
    tool: SheetRailTool,
    onToolTapped: (SheetRailTool) -> Unit
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant

    if (orientation == SheetPaneRailOrientation.ROW) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CompactPanelMargin)
                .foliumBorder(1.dp, lineColor)
                .testTag(SheetPaneTestTags.TOOL_RAIL),
            horizontalArrangement = Arrangement.Center
        ) {
            SheetRailTools.forEach { railTool ->
                SheetRailCell(
                    tool = railTool,
                    active = tool == railTool,
                    cellSize = RailRowCellWidth to RailRowCellHeight,
                    glyphSize = RailRowGlyphSize,
                    onClick = { onToolTapped(railTool) }
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .width(RailBreadth)
                .fillMaxHeight()
                .foliumBorder(1.dp, lineColor)
                .padding(vertical = RailColumnTopPadding)
                .testTag(SheetPaneTestTags.TOOL_RAIL),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RailColumnCellGap)
        ) {
            SheetRailTools.forEach { railTool ->
                SheetRailCell(
                    tool = railTool,
                    active = tool == railTool,
                    cellSize = RailColumnCellWidth to RailColumnCellHeight,
                    glyphSize = RailColumnGlyphSize,
                    onClick = { onToolTapped(railTool) }
                )
            }

        }
    }
}

@Composable
private fun SheetRailCell(
    tool: SheetRailTool,
    active: Boolean,
    cellSize: Pair<Dp, Dp>,
    glyphSize: Dp,
    onClick: () -> Unit
) {
    val background = if (active) MaterialTheme.colorScheme.onSurface else Color.Transparent
    val tint = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    val label = stringResource(tool.labelRes)

    Column(
        modifier = Modifier
            .size(cellSize.first, cellSize.second)
            .background(background)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tool.testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(Modifier.size(glyphSize)) { tool.glyph(this, tint) }
        Spacer(Modifier.height(3.dp))
        Text(text = label.uppercase(), style = FoliumType.RailLabel, color = tint)
    }
}
