package com.folium.reader.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.folium.reader.R
import com.folium.reader.core.ink.OpenSheet
import com.folium.reader.library.searchFieldBorder
import com.folium.reader.reader.ChromeBar
import com.folium.reader.reader.GlyphButton
import com.folium.reader.reader.drawChevron
import com.folium.reader.ui.FoliumDialog
import com.folium.reader.ui.FoliumDivider
import com.folium.reader.ui.FoliumRuleEdge
import com.folium.reader.ui.FoliumSpacing
import com.folium.reader.ui.FoliumType
import com.folium.reader.ui.FoliumWidthClass
import com.folium.reader.ui.foliumBorder
import com.folium.reader.ui.foliumRule

/** Test tags a UI test drives [SheetPane] with. */
object SheetPaneTestTags {
    const val PANE = "sheet-pane"
    const val TOP_BAR = "sheet-pane-top-bar"
    const val BACK = "sheet-pane-back"
    const val TITLE = "sheet-pane-title"
    const val UNDO = "sheet-pane-undo"
    const val REDO = "sheet-pane-redo"
    const val TOOL_RAIL = "sheet-pane-tool-rail"
    const val TOOL_PEN = "sheet-pane-tool-pen"
    const val TOOL_ERASER = "sheet-pane-tool-eraser"
    const val WIDTH_THIN = "sheet-pane-width-thin"
    const val WIDTH_MEDIUM = "sheet-pane-width-medium"
    const val WIDTH_THICK = "sheet-pane-width-thick"
    const val PERSISTENCE_BANNER = "sheet-pane-persistence-banner"
    const val SURFACE = "sheet-pane-surface"
    const val RENAME_DIALOG = "sheet-pane-rename-dialog"
    const val RENAME_FIELD = "sheet-pane-rename-field"
    const val RENAME_SAVE = "sheet-pane-rename-save"
    const val RENAME_CANCEL = "sheet-pane-rename-cancel"
}

/**
 * A sheet title fit to store: leading and trailing whitespace trimmed, every inner run of
 * whitespace collapsed to one space, and capped at [SHEET_TITLE_MAX_LENGTH] characters. `null` when
 * the result is empty, so a caller never has to check for blankness itself.
 */
internal fun normalizedSheetTitle(input: String): String? {
    val collapsed = input.trim().replace(Regex("\\s+"), " ")
    return collapsed.take(SHEET_TITLE_MAX_LENGTH).ifEmpty { null }
}

private const val SHEET_TITLE_MAX_LENGTH = 120

/** Which axis [SheetPane]'s tool rail lays its cells out along. */
internal enum class SheetPaneRailOrientation { COLUMN, ROW }

/**
 * Whether the tool rail runs down the left edge or along the bottom, mirroring the design's own
 * split between a tablet's vertical rail (T-Lapiz.dc.html, T-Selectores.dc.html) and a phone-width
 * row.
 */
internal fun sheetPaneRailOrientation(widthClass: FoliumWidthClass): SheetPaneRailOrientation =
    if (widthClass == FoliumWidthClass.COMPACT) SheetPaneRailOrientation.ROW else SheetPaneRailOrientation.COLUMN

/** The three pen widths the rail offers, paired with the sheet-unit value each one draws at and its own test tag. */
internal enum class SheetPaneWidthOption(val sheetUnits: Float, val testTag: String, val lineThickness: Dp) {
    THIN(InkPenWidths.THIN_SHEET_UNITS, SheetPaneTestTags.WIDTH_THIN, 2.dp),
    MEDIUM(InkPenWidths.MEDIUM_SHEET_UNITS, SheetPaneTestTags.WIDTH_MEDIUM, 4.dp),
    THICK(InkPenWidths.THICK_SHEET_UNITS, SheetPaneTestTags.WIDTH_THICK, 7.dp)
}

private val RailBreadth = 80.dp
private val RailCellWidth = 64.dp
private val RailCellHeight = 60.dp
private const val CLOSE_TIMEOUT_MILLIS = 5_000L

/**
 * The pane that hosts one open sheet's drawing surface, its top bar and its tool rail. Not a
 * screen: a host places this beside a book, or gives it the whole width itself, and owns
 * navigating away from it through [onBack].
 *
 * [openSheet] is never closed here except through the drawing surface it backs: this pane only
 * flushes and closes the [InkDrawingSurface] it creates when it leaves composition, the same
 * contract [InkDrawingSurface.close] documents. A host that wants [openSheet] itself closed does
 * so only after this composable has left composition.
 */
@Composable
fun SheetPane(
    openSheet: OpenSheet,
    onBack: () -> Unit,
    onRename: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var title by remember { mutableStateOf(openSheet.sheet.title) }
    var tool by remember { mutableStateOf(InkSurfaceTool.PEN) }
    var widthOption by remember { mutableStateOf(SheetPaneWidthOption.MEDIUM) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var persistenceFailed by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf<InkDrawingSurface?>(null) }
    var renameDialogOpen by remember { mutableStateOf(false) }

    val paperColor = MaterialTheme.colorScheme.surface
    val fieldColor = MaterialTheme.colorScheme.surfaceVariant
    val ruleColor = MaterialTheme.colorScheme.outlineVariant

    DisposableEffect(Unit) {
        onDispose {
            surface?.let {
                it.flushAndWait(CLOSE_TIMEOUT_MILLIS)
                it.close()
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().testTag(SheetPaneTestTags.PANE)) {
        val widthClass = FoliumWidthClass.of(maxWidth)
        val orientation = sheetPaneRailOrientation(widthClass)

        Column(Modifier.fillMaxSize()) {
            SheetPaneTopBar(
                title = title,
                canUndo = canUndo,
                canRedo = canRedo,
                widthClass = widthClass,
                onBack = onBack,
                onTitleClick = { renameDialogOpen = true },
                onUndo = { surface?.undo() },
                onRedo = { surface?.redo() }
            )

            if (persistenceFailed) SheetPanePersistenceBanner()

            if (renameDialogOpen) {
                SheetPaneRenameDialog(
                    currentTitle = title,
                    onDismiss = { renameDialogOpen = false },
                    onSave = { normalized ->
                        title = normalized
                        renameDialogOpen = false
                        onRename(normalized)
                    }
                )
            }

            val canvas: @Composable () -> Unit = {
                AndroidView(
                    modifier = Modifier.fillMaxSize().testTag(SheetPaneTestTags.SURFACE),
                    factory = { context ->
                        InkDrawingSurface(context, openSheet).apply {
                            setColors(InkSurfaceColors(paper = paperColor.toArgb(), field = fieldColor.toArgb(), rule = ruleColor.toArgb()))
                            setTemplate(openSheet.sheet.template)
                            listener = object : InkSurfaceListener {
                                override fun onHistoryChanged(newCanUndo: Boolean, newCanRedo: Boolean) {
                                    canUndo = newCanUndo
                                    canRedo = newCanRedo
                                }

                                override fun onPersistenceFailure(error: Throwable) {
                                    persistenceFailed = true
                                }
                            }
                            surface = this
                        }
                    },
                    update = { view ->
                        view.setTool(tool)
                        view.setPenWidthSheetUnits(widthOption.sheetUnits)
                    }
                )
            }

            val rail: @Composable () -> Unit = {
                SheetPaneToolRail(
                    orientation = orientation,
                    tool = tool,
                    widthOption = widthOption,
                    onToolSelected = { tool = it },
                    onWidthSelected = { widthOption = it }
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (orientation == SheetPaneRailOrientation.ROW) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { canvas() }
                        rail()
                    }
                } else {
                    Row(Modifier.fillMaxSize()) {
                        rail()
                        Box(Modifier.weight(1f)) { canvas() }
                    }
                }
            }
        }
    }
}

/**
 * The top bar: back, the sheet's title, then undo and redo. Reuses [ChromeBar] and [GlyphButton]
 * rather than duplicating the reader's chrome, since this pane and the reader draw the same bar.
 */
@Composable
private fun SheetPaneTopBar(
    title: String,
    canUndo: Boolean,
    canRedo: Boolean,
    widthClass: FoliumWidthClass,
    onBack: () -> Unit,
    onTitleClick: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit
) {
    ChromeBar(
        modifier = Modifier.testTag(SheetPaneTestTags.TOP_BAR),
        insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        dividerBelow = true,
        widthClass = widthClass
    ) {
        GlyphButton(
            glyph = { tint -> drawChevron(tint, pointingRight = false) },
            description = stringResource(R.string.sheet_pane_back),
            onClick = onBack,
            testTag = SheetPaneTestTags.BACK
        )

        Text(
            text = title,
            style = FoliumType.BodyMidMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onTitleClick)
                .padding(horizontal = FoliumSpacing.xs)
                .testTag(SheetPaneTestTags.TITLE)
        )

        GlyphButton(
            glyph = { tint -> drawUndo(tint) },
            description = stringResource(R.string.sheet_pane_undo),
            onClick = onUndo,
            enabled = canUndo,
            testTag = SheetPaneTestTags.UNDO
        )

        GlyphButton(
            glyph = { tint -> drawRedo(tint) },
            description = stringResource(R.string.sheet_pane_redo),
            onClick = onRedo,
            enabled = canRedo,
            testTag = SheetPaneTestTags.REDO
        )
    }
}

/** The non-dismissable banner shown once the sheet's writer has refused an edit. */
@Composable
private fun SheetPanePersistenceBanner() {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .foliumRule(FoliumRuleEdge.BOTTOM, 1.dp, MaterialTheme.colorScheme.error)
            .padding(horizontal = FoliumSpacing.m, vertical = FoliumSpacing.s)
            .testTag(SheetPaneTestTags.PERSISTENCE_BANNER)
    ) {
        Text(
            text = stringResource(R.string.sheet_pane_persistence_failure),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

/**
 * Asks for a new title, prefilled with [currentTitle] and fully selected so typing replaces it
 * outright. The field is drawn like [com.folium.reader.library.LibraryScreen]'s own search field —
 * a flat, bordered box rather than Material's text field chrome — reusing [searchFieldBorder] so the
 * two stay in step. [onSave] only runs for a title [normalizedSheetTitle] accepts; an empty result
 * leaves the dialog open rather than saving or dismissing.
 */
@Composable
private fun SheetPaneRenameDialog(currentTitle: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(currentTitle, TextRange(0, currentTitle.length))) }
    val focus = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val border = searchFieldBorder(focused, MaterialTheme.colorScheme)

    fun trySave() {
        normalizedSheetTitle(field.text)?.let(onSave)
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    FoliumDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(SheetPaneTestTags.RENAME_DIALOG),
        title = { Text(stringResource(R.string.sheet_pane_rename)) },
        text = {
            BasicTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                interactionSource = interactionSource,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { trySave() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FoliumSpacing.touchTarget)
                    .foliumBorder(border.width, border.color)
                    .padding(horizontal = FoliumSpacing.s)
                    .focusRequester(focus)
                    .testTag(SheetPaneTestTags.RENAME_FIELD)
            )
        },
        confirmButton = {
            TextButton(
                onClick = { trySave() },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(SheetPaneTestTags.RENAME_SAVE)
            ) {
                Text(stringResource(R.string.sheet_pane_rename_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(SheetPaneTestTags.RENAME_CANCEL)
            ) {
                Text(stringResource(R.string.sheet_pane_rename_cancel))
            }
        }
    )
}

/**
 * The tool rail: PEN and ERASER, then a width control for the three widths [InkPenWidths] defines.
 * Only these tools exist today; a future tool is an additive entry here, never a disabled
 * placeholder.
 */
@Composable
private fun SheetPaneToolRail(
    orientation: SheetPaneRailOrientation,
    tool: InkSurfaceTool,
    widthOption: SheetPaneWidthOption,
    onToolSelected: (InkSurfaceTool) -> Unit,
    onWidthSelected: (SheetPaneWidthOption) -> Unit
) {
    val ruleEdge = if (orientation == SheetPaneRailOrientation.ROW) FoliumRuleEdge.TOP else FoliumRuleEdge.END
    val ruleModifier = Modifier.foliumRule(ruleEdge, 1.dp, MaterialTheme.colorScheme.outlineVariant)

    val toolCells: @Composable () -> Unit = {
        SheetPaneToolCell(
            label = stringResource(R.string.sheet_pane_tool_pen),
            glyph = { tint -> drawPenGlyph(tint) },
            active = tool == InkSurfaceTool.PEN,
            onClick = { onToolSelected(InkSurfaceTool.PEN) },
            testTag = SheetPaneTestTags.TOOL_PEN
        )
        SheetPaneToolCell(
            label = stringResource(R.string.sheet_pane_tool_eraser),
            glyph = { tint -> drawEraserGlyph(tint) },
            active = tool == InkSurfaceTool.ERASER,
            onClick = { onToolSelected(InkSurfaceTool.ERASER) },
            testTag = SheetPaneTestTags.TOOL_ERASER
        )
    }

    val widthCells: @Composable () -> Unit = {
        SheetPaneWidthOption.entries.forEach { option ->
            SheetPaneWidthCell(
                option = option,
                active = widthOption == option,
                onClick = { onWidthSelected(option) }
            )
        }
    }

    if (orientation == SheetPaneRailOrientation.ROW) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(ruleModifier)
                .padding(FoliumSpacing.xxs)
                .testTag(SheetPaneTestTags.TOOL_RAIL),
            horizontalArrangement = Arrangement.spacedBy(FoliumSpacing.xxs, Alignment.CenterHorizontally)
        ) {
            toolCells()
            widthCells()
        }
    } else {
        Column(
            modifier = Modifier
                .width(RailBreadth)
                .fillMaxHeight()
                .then(ruleModifier)
                .padding(vertical = FoliumSpacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FoliumSpacing.xxs)
        ) {
            toolCells()
            FoliumDivider.Horizontal(modifier = Modifier.width(RailCellWidth - FoliumSpacing.m))
            widthCells()
        }
    }
}

@Composable
private fun SheetPaneToolCell(
    label: String,
    glyph: DrawScope.(Color) -> Unit,
    active: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val background = if (active) MaterialTheme.colorScheme.onSurface else Color.Transparent
    val tint = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .size(RailCellWidth, RailCellHeight)
            .background(background)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(Modifier.size(22.dp)) { glyph(tint) }
        Text(text = label.uppercase(), style = FoliumType.Caption, color = tint)
    }
}

@Composable
private fun SheetPaneWidthCell(option: SheetPaneWidthOption, active: Boolean, onClick: () -> Unit) {
    val background = if (active) MaterialTheme.colorScheme.onSurface else Color.Transparent
    val lineColor = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .size(RailCellWidth, RailCellHeight)
            .background(background)
            .clickable(onClick = onClick)
            .testTag(option.testTag),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(width = 28.dp, height = option.lineThickness).background(lineColor))
    }
}

/** The back chevron this pane draws through [drawChevron] shares; undo curves left, redo mirrors it. */
private fun DrawScope.drawUndo(tint: Color) = drawHistoryArrow(tint, pointingLeft = true)

private fun DrawScope.drawRedo(tint: Color) = drawHistoryArrow(tint, pointingLeft = false)

/**
 * The undo mark and, mirrored, the redo mark: an open arrowhead whose tip starts a straight shaft
 * that turns back on itself through a half circle. The head is two strokes meeting at the shaft's own
 * end, never a filled triangle, so it stays legible at the system's 1.6dp stroke in a 20-unit box.
 */
private fun DrawScope.drawHistoryArrow(tint: Color, pointingLeft: Boolean) {
    val unit = size.width / 20f
    val stroke = 1.6.dp.toPx()

    fun x(units: Float): Float = (if (pointingLeft) units else 20f - units) * unit
    fun y(units: Float): Float = units * unit

    val head = Path().apply {
        moveTo(x(7f), y(4f))
        lineTo(x(3f), y(8f))
        lineTo(x(7f), y(12f))
    }

    val turnLeft = minOf(x(7.5f), x(16.5f))
    val turnRight = maxOf(x(7.5f), x(16.5f))
    val shaft = Path().apply {
        moveTo(x(3f), y(8f))
        lineTo(x(12f), y(8f))
        arcTo(
            rect = Rect(turnLeft, y(8f), turnRight, y(17f)),
            startAngleDegrees = -90f,
            sweepAngleDegrees = if (pointingLeft) 180f else -180f,
            forceMoveTo = false
        )
        lineTo(x(9f), y(17f))
    }

    val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(head, tint, style = style)
    drawPath(shaft, tint, style = style)
}

/** The pen tool's own mark, the design's "LÁPIZ" glyph (T-Lapiz.dc.html, T-Selectores.dc.html). */
private fun DrawScope.drawPenGlyph(tint: Color) {
    val unit = size.width / 22f
    val stroke = 1.6.dp.toPx()
    val path = Path().apply {
        moveTo(4f * unit, 18f * unit)
        lineTo(6.5f * unit, 17f * unit)
        lineTo(17f * unit, 6.5f * unit)
        lineTo(15.5f * unit, 5f * unit)
        lineTo(5f * unit, 15.5f * unit)
        close()
    }
    drawPath(path, tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The eraser tool's own mark, the design's "GOMA" glyph (T-Lapiz.dc.html, T-Selectores.dc.html). */
private fun DrawScope.drawEraserGlyph(tint: Color) {
    val unit = size.width / 22f
    val stroke = 1.6.dp.toPx()
    drawLine(tint, Offset(5f * unit, 17f * unit), Offset(17f * unit, 17f * unit), stroke, cap = StrokeCap.Round)
    val path = Path().apply {
        moveTo(6f * unit, 14f * unit)
        lineTo(12f * unit, 5f * unit)
        lineTo(16.5f * unit, 8.5f * unit)
        lineTo(11f * unit, 17f * unit)
    }
    drawPath(path, tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
