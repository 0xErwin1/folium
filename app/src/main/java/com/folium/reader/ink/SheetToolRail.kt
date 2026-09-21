package com.folium.reader.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
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
import com.folium.reader.ui.FoliumDivider
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
 * The non-compact body's own rail geometry: `D3/T-Lapiz.dc.html:29` draws this body at "padding: 24px;
 * gap: 24px" on every side including the reading column's, but that artboard is a book page, which
 * absorbs an outer margin of its own; a handwritten sheet has none, so that same 24/24 became a dead
 * zone where the surface stopped accepting ink and a stroke was clipped at its edge. There is no body
 * padding or rail-to-surface gap here any more: with the full rail shown, it is docked flush with the
 * body's own start edge, and the drawing surface starts immediately after it, filling the rest of the
 * body edge to edge; with the rail hidden, no column is reserved at all, the surface fills the whole
 * body, and [SheetRailHiddenTab] floats over its own top-start corner instead.
 */
internal data class SheetPaneBodyLayout(val compactRailMargin: Dp)

internal fun sheetPaneBodyLayout(widthClass: FoliumWidthClass): SheetPaneBodyLayout =
    when (sheetPaneRailOrientation(widthClass)) {
        SheetPaneRailOrientation.COLUMN -> SheetPaneBodyLayout(compactRailMargin = 0.dp)
        SheetPaneRailOrientation.ROW -> SheetPaneBodyLayout(CompactPanelMargin)
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

/** The rail foot's own short rule, above the OCULTAR cell (`design5-diff.md`, T-Lapiz.dc.html:39: "width: 40px; height: 1px; margin: 6px 0"). */
private val RailHideRuleWidth = 40.dp
private val RailHideRuleMargin = 6.dp

/**
 * The hidden-rail tab's own breadth, its two cells' size and their glyph size (`design5-diff.md`,
 * T-EscribirOculta: "width: 46px" outer, "width: 44px; height: 44px" per cell, `viewBox="0 0 22 22"`
 * glyphs). Shared with [SheetSelectorPanel]'s anchor geometry, which anchors to the tab's tool cell
 * when the rail is hidden.
 */
internal val RailHiddenTabWidth = 46.dp
internal val RailHiddenTabCellSize = 44.dp
private val RailHiddenTabGlyphSize = 22.dp

/**
 * The tool rail: the tools this app implements, in the design's own order. It carries no summary of
 * the pen: "La barra no lleva ningún resumen de punta: el color y el grosor se ven en el selector"
 * (`canvas.json`, `nota-t-selectores`), so a tool's settings are reached only by tapping the tool that
 * is already active. The design's foot holds "+ HOJA" then OCULTAR, under a short rule; "+ HOJA" is
 * not drawn until a sheet can be attached to a book page, but OCULTAR — which collapses this rail to
 * [SheetRailHiddenTab] — is. Laid out as a left column on a tablet-width window and as a bottom row on
 * a phone-width one (`P-Partida.dc.html`); [onHideTapped] only fires from the column layout, since the
 * phone artboards for hiding a bottom row are not yet specified for this app. On a tablet-width window
 * the column is docked flush with the body's own start edge with no margin of its own — the drawing
 * surface starts immediately after it rather than the rail floating over the sheet — with an opaque
 * paper background so nothing under it (there is nothing, since the surface never extends beneath a
 * docked rail) could ever show through regardless.
 */
@Composable
internal fun SheetPaneToolRail(
    orientation: SheetPaneRailOrientation,
    tool: SheetRailTool,
    onToolTapped: (SheetRailTool) -> Unit,
    onHideTapped: () -> Unit
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
                .background(MaterialTheme.colorScheme.surface)
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

            Spacer(Modifier.weight(1f))
            FoliumDivider.Horizontal(
                modifier = Modifier.width(RailHideRuleWidth).padding(vertical = RailHideRuleMargin),
                color = lineColor
            )
            SheetRailHideCell(onClick = onHideTapped)
        }
    }
}

/**
 * The rail foot's own OCULTAR cell: a plain action, never drawn active, that collapses the rail to
 * [SheetRailHiddenTab] (`design5-diff.md`, T-Lapiz/T-Escribir/T-Hoja).
 */
@Composable
private fun SheetRailHideCell(onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurface
    val label = stringResource(R.string.sheet_pane_tool_rail_hide)

    Column(
        modifier = Modifier
            .size(RailColumnCellWidth, RailColumnCellHeight)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(SheetPaneTestTags.TOOL_RAIL_HIDE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(Modifier.size(RailColumnGlyphSize)) { drawHideRailGlyph(tint) }
        Spacer(Modifier.height(3.dp))
        Text(text = label.uppercase(), style = FoliumType.RailLabel, color = tint)
    }
}

/**
 * The rail-hidden state's own tab (`design5-diff.md`, T-EscribirOculta): a 46dp-wide, bordered handle,
 * only as tall as its two cells rather than stretched to the body's full height, floating with no
 * margin over the sheet's own top-start corner rather than reserving a column of its own. The top cell
 * shows [activeTool]'s own glyph inverted, the same highlight the full rail draws for the active tool,
 * and tapping it opens that tool's selector exactly like tapping the active cell in the full rail
 * (`nota-t-oculta`: "Un segundo toque sobre la celda activa de la pestaña abre su selector igual que en
 * la barra"); the bottom cell reopens the rail. Neither cell carries a text label — the tab is
 * icon-only in the design. Its own paper background is opaque and it swallows every touch inside its
 * bounds — including the sliver its border occupies, outside either cell — the same technique
 * [SheetSelectorPanelBox] uses, since it floats directly over the sheet and a stroke must never start
 * or leak through underneath it.
 */
@Composable
internal fun SheetRailHiddenTab(
    activeTool: SheetRailTool,
    onToolTapped: () -> Unit,
    onShowTapped: () -> Unit
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val toolDescription = stringResource(activeTool.labelRes)
    val showDescription = stringResource(R.string.sheet_pane_tool_rail_show)

    Column(
        modifier = Modifier
            .width(RailHiddenTabWidth)
            .background(paper)
            .foliumBorder(1.dp, lineColor)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .testTag(SheetPaneTestTags.TOOL_RAIL_TAB),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(RailHiddenTabCellSize)
                .background(ink)
                .clickable(onClick = onToolTapped)
                .semantics { contentDescription = toolDescription }
                .testTag(SheetPaneTestTags.TOOL_RAIL_TAB_TOOL),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(RailHiddenTabGlyphSize)) { activeTool.glyph(this, paper) }
        }

        Box(
            modifier = Modifier
                .size(RailHiddenTabCellSize)
                .clickable(onClick = onShowTapped)
                .semantics { contentDescription = showDescription }
                .testTag(SheetPaneTestTags.TOOL_RAIL_TAB_SHOW),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(RailHiddenTabGlyphSize)) { drawShowRailGlyph(ink) }
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
