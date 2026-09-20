package com.folium.reader.ink

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.folium.reader.R
import com.folium.reader.core.ink.OpenSheet
import com.folium.reader.library.searchFieldBorder
import com.folium.reader.reader.ChromeBar
import com.folium.reader.reader.GlyphButton
import com.folium.reader.reader.drawChevron
import com.folium.reader.ui.FoliumDialog
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
    const val TOOL_VIEW = "sheet-rail-tool-view"
    const val TOOL_PEN = "sheet-rail-tool-pen"
    const val TOOL_ERASER = "sheet-rail-tool-eraser"
    const val PUNTA = "sheet-rail-punta"
    const val PERSISTENCE_BANNER = "sheet-pane-persistence-banner"
    const val SELECTOR_PANEL_OVERLAY = "sheet-selector-panel-overlay"
    const val SELECTOR_PANEL = "sheet-selector-panel"
    const val SELECTOR_TIP_BALLPOINT = "sheet-selector-tip-ballpoint"
    const val SELECTOR_TIP_FOUNTAIN = "sheet-selector-tip-fountain"
    const val SELECTOR_TIP_PENCIL = "sheet-selector-tip-pencil"
    const val SELECTOR_WIDTH_MINUS = "sheet-selector-width-minus"
    const val SELECTOR_WIDTH_PLUS = "sheet-selector-width-plus"
    const val SELECTOR_WIDTH_VALUE = "sheet-selector-width-value"
    const val SELECTOR_COLOUR_BLACK = "sheet-selector-colour-black"
    const val SELECTOR_COLOUR_RED = "sheet-selector-colour-red"
    const val SELECTOR_COLOUR_BLUE = "sheet-selector-colour-blue"
    const val SELECTOR_COLOUR_GREEN = "sheet-selector-colour-green"
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
    penSettings: PenSettings = PenSettings.DEFAULT,
    onPenSettingsChange: (PenSettings) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var title by remember { mutableStateOf(openSheet.sheet.title) }
    var tool by remember { mutableStateOf(InkSurfaceTool.PEN) }
    var selectorState by remember { mutableStateOf(SheetSelectorState(activeTool = SheetRailTool.PEN, openPanel = null)) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var persistenceFailed by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf<InkDrawingSurface?>(null) }
    var renameDialogOpen by remember { mutableStateOf(false) }

    val paperColor = MaterialTheme.colorScheme.surface
    val fieldColor = MaterialTheme.colorScheme.surfaceVariant
    val ruleColor = MaterialTheme.colorScheme.outlineVariant
    val themeInkArgb = MaterialTheme.colorScheme.onSurface.toArgb()

    fun reduceSelector(event: SheetSelectorEvent) {
        selectorState = selectorState.reduce(event)
    }

    DisposableEffect(Unit) {
        onDispose {
            surface?.let {
                it.flushAndWait(CLOSE_TIMEOUT_MILLIS)
                it.close()
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().testTag(SheetPaneTestTags.PANE)) {
        val paneWidth = maxWidth
        val widthClass = FoliumWidthClass.of(paneWidth)
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

                                override fun onStrokeStarted() {
                                    reduceSelector(SheetSelectorEvent.StrokeStarted)
                                }
                            }
                            surface = this
                        }
                    },
                    update = { view ->
                        view.setTool(tool)
                        view.setPenTip(penSettings.tip)
                        view.setPenColorArgb(penSettings.colorChoice.resolveArgb(themeInkArgb))
                        view.setPenWidthSheetUnits(mmToSheetUnits(penSettings.widthTenthsMm / 10f))
                    }
                )
            }

            val rail: @Composable () -> Unit = {
                SheetPaneToolRail(
                    orientation = orientation,
                    tool = selectorState.activeTool,
                    penColorArgb = penSettings.colorChoice.resolveArgb(themeInkArgb),
                    penWidthMm = penSettings.widthTenthsMm / 10f,
                    onToolTapped = { tapped ->
                        reduceSelector(SheetSelectorEvent.ToolTapped(tapped))
                        tapped.toSurfaceTool()?.let { tool = it }
                    },
                    onPuntaTapped = {
                        reduceSelector(SheetSelectorEvent.PuntaTapped)
                        tool = InkSurfaceTool.PEN
                    }
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

                SheetSelectorOverlay(
                    orientation = orientation,
                    paneWidth = paneWidth,
                    openPanel = selectorState.openPanel,
                    penSettings = penSettings,
                    onPenSettingsChange = onPenSettingsChange,
                    onOutsideTapped = { reduceSelector(SheetSelectorEvent.OutsideTapped) },
                    onBackPressed = { reduceSelector(SheetSelectorEvent.BackPressed) }
                )
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
