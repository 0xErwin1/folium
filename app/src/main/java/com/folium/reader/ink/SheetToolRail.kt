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
 * A tool the rail draws a cell for, with its own glyph and test tag. Only tools with a working
 * engine behind them get an entry here: a future tool is an additive entry, never a disabled
 * placeholder.
 */
internal enum class SheetRailTool(val labelRes: Int, val testTag: String, val glyph: DrawScope.(Color) -> Unit) {
    VIEW(R.string.sheet_pane_tool_view, SheetPaneTestTags.TOOL_VIEW, { tint -> drawViewRailGlyph(tint) }),
    PEN(R.string.sheet_pane_tool_pen, SheetPaneTestTags.TOOL_PEN, { tint -> drawPenRailGlyph(tint) }),
    ERASER(R.string.sheet_pane_tool_eraser, SheetPaneTestTags.TOOL_ERASER, { tint -> drawEraserRailGlyph(tint) })
}

/** The drawing surface tool [SheetRailTool] drives, or `null` when the tool has no drawing-surface behavior yet (VIEW, until it gains one). */
internal fun SheetRailTool.toSurfaceTool(): InkSurfaceTool? = when (this) {
    SheetRailTool.VIEW -> null
    SheetRailTool.PEN -> InkSurfaceTool.PEN
    SheetRailTool.ERASER -> InkSurfaceTool.ERASER
}

/** The nine-tool list (`D3/T-Lapiz.dc.html:98-108`) filtered down to the tools this rail actually implements, in the design's own order. */
internal val SheetRailTools: List<SheetRailTool> = SheetRailTool.entries

/** Whether [orientation] draws the foot's PUNTA summary cell: the design's portrait tool row has no foot at all (`D3/P-Partida.dc.html` L18). */
internal fun sheetRailShowsPunta(orientation: SheetPaneRailOrientation): Boolean =
    orientation == SheetPaneRailOrientation.COLUMN

/**
 * PUNTA's width bar height for a pen of [widthMm] millimetres: 1mm reads as [PUNTA_BAR_DP_PER_MM]dp,
 * a value chosen so the stepper's own 0.1–3.0mm range spans the bar's visible floor to its cap
 * without either end reading as identical to its neighbours. Floored at 1dp so the bar never
 * disappears, capped at 8dp so it never dwarfs the 60dp cell.
 */
internal fun puntaWidthBarHeight(widthMm: Float): Dp = (widthMm * PUNTA_BAR_DP_PER_MM).dp.coerceIn(1.dp, 8.dp)

private const val PUNTA_BAR_DP_PER_MM = 4f

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
private val RailFootDividerWidth = 40.dp
private val RailFootDividerMargin = 6.dp
private val PuntaSquareSize = 14.dp
private val PuntaBarWidth = 22.dp

/**
 * The tool rail: VIEW, PEN and ERASER, then a PUNTA cell that summarizes the pen's own current
 * colour and width and, once selectors exist, opens the same panel the pen cell does
 * (`D3/T-Lapiz.dc.html:38-40`). Laid out as a left column on a tablet-width window and as a bottom
 * row on a phone-width one (`D3/P-Partida.dc.html`).
 */
@Composable
internal fun SheetPaneToolRail(
    orientation: SheetPaneRailOrientation,
    tool: SheetRailTool,
    penColorArgb: Int,
    penWidthMm: Float,
    onToolTapped: (SheetRailTool) -> Unit,
    onPuntaTapped: () -> Unit
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant

    if (orientation == SheetPaneRailOrientation.ROW) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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

            Box(Modifier.weight(1f))

            if (sheetRailShowsPunta(orientation)) {
                Box(Modifier.padding(vertical = RailFootDividerMargin).width(RailFootDividerWidth).height(1.dp).background(lineColor))
                SheetPuntaCell(penColorArgb = penColorArgb, penWidthMm = penWidthMm, onClick = onPuntaTapped)
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

/**
 * PUNTA: the foot cell that summarizes the pen's current colour and width, in the rail's own idiom
 * rather than as a disabled placeholder — it is always live, since the pen tool always has a colour
 * and a width (`D3/T-Lapiz.dc.html:38-40`, "PUNTA resume color y grosor actuales y abre el mismo
 * selector").
 */
@Composable
private fun SheetPuntaCell(penColorArgb: Int, penWidthMm: Float, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val label = stringResource(R.string.sheet_pane_tool_tip)
    val barHeight = puntaWidthBarHeight(penWidthMm)

    Column(
        modifier = Modifier
            .size(RailColumnCellWidth, RailColumnCellHeight)
            .foliumBorder(1.dp, ink)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(SheetPaneTestTags.PUNTA),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(PuntaSquareSize).background(Color(penColorArgb)))
            Box(Modifier.width(PuntaBarWidth).height(barHeight).background(ink))
        }
        Spacer(Modifier.height(3.dp))
        Text(text = label.uppercase(), style = FoliumType.RailLabel, color = ink)
    }
}
