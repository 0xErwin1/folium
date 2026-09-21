package com.folium.reader.ink

import android.view.View
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.folium.reader.R
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.core.ink.OpenSheet
import com.folium.reader.core.ink.StrokeId
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
    const val TOOL_HIGHLIGHT = "sheet-rail-tool-highlight"
    const val TOOL_TEXT = "sheet-rail-tool-text"
    const val TOOL_SHAPE = "sheet-rail-tool-shape"
    const val TOOL_SELECT = "sheet-rail-tool-select"
    const val TOOL_ERASER = "sheet-rail-tool-eraser"
    const val TOOL_RAIL_HIDE = "sheet-rail-hide"
    const val TOOL_RAIL_TAB = "sheet-rail-tab"
    const val TOOL_RAIL_TAB_TOOL = "sheet-rail-tab-tool"
    const val TOOL_RAIL_TAB_SHOW = "sheet-rail-show"
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
    const val SELECTOR_ZOOM_MINUS = "sheet-selector-zoom-minus"
    const val SELECTOR_ZOOM_PLUS = "sheet-selector-zoom-plus"
    const val SELECTOR_ZOOM_VALUE = "sheet-selector-zoom-value"
    const val SELECTOR_FIT_WIDTH = "sheet-selector-fit-width"
    const val SELECTOR_FIT_ACTUAL = "sheet-selector-fit-actual"
    const val SELECTOR_ERASER_MODE_WHOLE = "sheet-selector-eraser-mode-whole"
    const val SELECTOR_ERASER_MODE_PARTIAL = "sheet-selector-eraser-mode-partial"
    const val SELECTOR_ERASER_SIZE_MINUS = "sheet-selector-eraser-size-minus"
    const val SELECTOR_ERASER_SIZE_PLUS = "sheet-selector-eraser-size-plus"
    const val SELECTOR_ERASER_SIZE_VALUE = "sheet-selector-eraser-size-value"
    const val SELECTOR_ERASER_CLEAR = "sheet-selector-eraser-clear"
    const val SELECTOR_ERASER_CLEAR_CONFIRM = "sheet-selector-eraser-clear-confirm"
    const val SELECTOR_ERASER_CLEAR_CANCEL = "sheet-selector-eraser-clear-cancel"
    const val SELECTOR_HIGHLIGHT_WIDTH_MINUS = "sheet-selector-highlight-width-minus"
    const val SELECTOR_HIGHLIGHT_WIDTH_PLUS = "sheet-selector-highlight-width-plus"
    const val SELECTOR_HIGHLIGHT_WIDTH_VALUE = "sheet-selector-highlight-width-value"
    const val SELECTOR_HIGHLIGHT_COLOUR_YELLOW = "sheet-selector-highlight-colour-yellow"
    const val SELECTOR_HIGHLIGHT_COLOUR_GREEN = "sheet-selector-highlight-colour-green"
    const val SELECTOR_HIGHLIGHT_COLOUR_PINK = "sheet-selector-highlight-colour-pink"
    const val SELECTOR_HIGHLIGHT_COLOUR_BLUE = "sheet-selector-highlight-colour-blue"
    const val SELECTOR_HIGHLIGHT_COLOUR_GREY = "sheet-selector-highlight-colour-grey"
    const val SELECTOR_TEXT_STYLE_BODY = "sheet-selector-text-style-body"
    const val SELECTOR_TEXT_STYLE_TITLE = "sheet-selector-text-style-title"
    const val SELECTOR_TEXT_COLOUR_BLACK = "sheet-selector-text-colour-black"
    const val SELECTOR_TEXT_COLOUR_RED = "sheet-selector-text-colour-red"
    const val SELECTOR_TEXT_COLOUR_BLUE = "sheet-selector-text-colour-blue"
    const val SELECTOR_TEXT_COLOUR_GREEN = "sheet-selector-text-colour-green"
    const val SELECTOR_SHAPE_LINE = "sheet-selector-shape-line"
    const val SELECTOR_SHAPE_ARROW = "sheet-selector-shape-arrow"
    const val SELECTOR_SHAPE_BOX = "sheet-selector-shape-box"
    const val SELECTOR_SHAPE_ELLIPSE = "sheet-selector-shape-ellipse"
    const val SELECTOR_SHAPE_WIDTH_MINUS = "sheet-selector-shape-width-minus"
    const val SELECTOR_SHAPE_WIDTH_PLUS = "sheet-selector-shape-width-plus"
    const val SELECTOR_SHAPE_WIDTH_VALUE = "sheet-selector-shape-width-value"
    const val SELECTOR_SHAPE_COLOUR_BLACK = "sheet-selector-shape-colour-black"
    const val SELECTOR_SHAPE_COLOUR_RED = "sheet-selector-shape-colour-red"
    const val SELECTOR_SHAPE_COLOUR_BLUE = "sheet-selector-shape-colour-blue"
    const val SELECTOR_SHAPE_COLOUR_GREEN = "sheet-selector-shape-colour-green"
    const val SELECTOR_STRAIGHTEN_NEVER = "sheet-selector-straighten-never"
    const val SELECTOR_STRAIGHTEN_HOLD = "sheet-selector-straighten-hold"
    const val SELECTOR_STRAIGHTEN_ALWAYS = "sheet-selector-straighten-always"
    const val SELECTOR_SELECT_MODE_TAP = "sheet-selector-select-mode-tap"
    const val SELECTOR_SELECT_MODE_LASSO = "sheet-selector-select-mode-lasso"
    const val SELECTOR_SELECT_MODE_BOX = "sheet-selector-select-mode-box"
    const val SELECTION_MENU = "sheet-selection-menu"
    const val SELECTION_MENU_CONVERT = "sheet-selection-menu-convert"
    const val SELECTION_MENU_COPY = "sheet-selection-menu-copy"
    const val SELECTION_MENU_DELETE = "sheet-selection-menu-delete"
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
    onConvertToText: ((List<InkStroke>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var title by remember { mutableStateOf(openSheet.sheet.title) }
    var tool by remember { mutableStateOf(InkSurfaceTool.PEN) }
    var selectorState by remember {
        mutableStateOf(SheetSelectorState(activeTool = SheetRailTool.PEN, openPanel = null, railHidden = penSettings.railHidden))
    }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var strokeCount by remember { mutableStateOf(0) }
    var persistenceFailed by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf<InkDrawingSurface?>(null) }
    var renameDialogOpen by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf<SheetViewport?>(null) }
    var selectedStrokeIds by remember { mutableStateOf<Set<StrokeId>>(emptySet()) }
    var selectionBoundsViewPx by remember { mutableStateOf<ViewRect?>(null) }
    var selectionEditing by remember { mutableStateOf(false) }
    var textEditing by remember { mutableStateOf(false) }

    val paperColor = MaterialTheme.colorScheme.surface
    val fieldColor = MaterialTheme.colorScheme.surfaceVariant
    val ruleColor = MaterialTheme.colorScheme.outlineVariant
    val themeInkArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    val xdpi = LocalContext.current.resources.displayMetrics.xdpi
    val zoomPercent = viewport?.let { zoomPercentOf(it.zoom) } ?: ZOOM_MIN_PERCENT
    val actualSizeZoomPercent = viewport?.let { zoomPercentOf(actualSizeZoom(xdpi, it.viewWidthPx)) } ?: ZOOM_MIN_PERCENT

    fun reduceSelector(event: SheetSelectorEvent) {
        selectorState = selectorState.reduce(event)
    }

    // A text session mid-edit takes back over leaving the sheet screen, the same way an open selector
    // panel already does in `SheetSelectorOverlay`: back closes the editor first, committing whatever
    // it holds, rather than closing the sheet screen under the keyboard.
    BackHandler(enabled = textEditing) { surface?.commitTextEditingIfOpen() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) surface?.commitTextEditingIfOpen()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose {
            surface?.let {
                it.flushAndWait(CLOSE_TIMEOUT_MILLIS)
                it.close()
            }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag(SheetPaneTestTags.PANE)
    ) {
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

            // No border of its own on the drawing surface: the page area beside the rail draws none
            // in the artboard either (`D3/T-Lapiz.dc.html:43`, no `border` or `background` on that
            // div), reading as a continuation of the body's own paper rather than a separate sheet.
            val canvas: @Composable () -> Unit = {
                AndroidView(
                    modifier = Modifier.fillMaxSize().testTag(SheetPaneTestTags.SURFACE),
                    factory = { context ->
                        InkDrawingSurface(context, openSheet).apply {
                            setColors(
                                InkSurfaceColors(
                                    paper = paperColor.toArgb(),
                                    field = fieldColor.toArgb(),
                                    rule = ruleColor.toArgb(),
                                    themeInk = themeInkArgb
                                )
                            )
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

                                override fun onViewportChanged(newViewport: SheetViewport) {
                                    viewport = newViewport
                                }

                                override fun onStrokeCountChanged(count: Int) {
                                    strokeCount = count
                                }

                                override fun onSelectionChanged(strokeIds: Set<StrokeId>, boundsViewPx: ViewRect?) {
                                    selectedStrokeIds = strokeIds
                                    selectionBoundsViewPx = boundsViewPx
                                }

                                override fun onSelectionEditingChanged(editing: Boolean) {
                                    selectionEditing = editing
                                }

                                override fun onTextEditingChanged(editing: Boolean) {
                                    textEditing = editing
                                }
                            }
                            surface = this
                        }
                    },
                    update = { view ->
                        view.setColors(
                            InkSurfaceColors(
                                paper = paperColor.toArgb(),
                                field = fieldColor.toArgb(),
                                rule = ruleColor.toArgb(),
                                themeInk = themeInkArgb
                            )
                        )
                        view.setTool(tool)
                        view.setPenTip(penSettings.tip)
                        view.setPenColorArgb(penSettings.colorChoice.storedArgb())
                        view.setPenWidthSheetUnits(mmToSheetUnits(penSettings.widthTenthsMm / 10f))
                        view.setHighlighterColorArgb(penSettings.highlighterColorChoice.storedArgb)
                        view.setHighlighterWidthSheetUnits(mmToSheetUnits(penSettings.highlighterWidthMm.toFloat()))
                        view.setShape(penSettings.shape)
                        view.setShapeColorArgb(penSettings.shapeColorChoice.storedArgb())
                        view.setShapeWidthSheetUnits(mmToSheetUnits(penSettings.shapeWidthTenthsMm / 10f))
                        view.setEraserSizeMm(penSettings.eraserSizeMm.toFloat())
                        view.setEraserMode(penSettings.eraserMode)
                        view.setStraightenMode(penSettings.straightenMode)
                        view.setHighlighterStraightenMode(penSettings.highlighterStraightenMode)
                        view.setSelectMode(penSettings.selectMode)
                        view.setTextStyle(penSettings.textStyle)
                        view.setTextColorArgb(penSettings.textColorChoice.storedArgb())
                    }
                )
            }

            val onToolTapped: (SheetRailTool) -> Unit = { tapped ->
                reduceSelector(SheetSelectorEvent.ToolTapped(tapped))
                tool = tapped.toSurfaceTool()
            }

            val rail: @Composable () -> Unit = {
                SheetPaneToolRail(
                    orientation = orientation,
                    tool = selectorState.activeTool,
                    onToolTapped = onToolTapped,
                    onHideTapped = {
                        reduceSelector(SheetSelectorEvent.RailHidden)
                        onPenSettingsChange(penSettings.copy(railHidden = true))
                    }
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (orientation == SheetPaneRailOrientation.ROW) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    ) {
                        Box(Modifier.weight(1f)) { canvas() }
                        rail()
                    }
                } else {
                    // Shown: the rail is docked in its own column flush with the body's start edge, no
                    // margin, no gap — the surface starts right after it and fills the rest edge to
                    // edge. Hidden: the surface fills the whole body and the tab floats over its own
                    // top-start corner instead, since a sheet — unlike the artboard's own book page —
                    // has no margin of its own to absorb padding, and a padded dead zone there clips
                    // ink that should have been captured (`D3/T-Lapiz.dc.html:29`).
                    Box(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    ) {
                        if (selectorState.railHidden) {
                            canvas()
                            Box(Modifier.align(Alignment.TopStart)) {
                                SheetRailHiddenTab(
                                    activeTool = selectorState.activeTool,
                                    onToolTapped = { onToolTapped(selectorState.activeTool) },
                                    onShowTapped = {
                                        reduceSelector(SheetSelectorEvent.RailShown)
                                        onPenSettingsChange(penSettings.copy(railHidden = false))
                                    }
                                )
                            }
                        } else {
                            Row(Modifier.fillMaxSize()) {
                                rail()
                                Box(Modifier.weight(1f)) { canvas() }
                            }
                        }
                    }
                }

                SheetSelectorOverlay(
                    orientation = orientation,
                    paneWidth = paneWidth,
                    railHidden = selectorState.railHidden,
                    activeTool = selectorState.activeTool,
                    openPanel = selectorState.openPanel,
                    penSettings = penSettings,
                    onPenSettingsChange = onPenSettingsChange,
                    zoomPercent = zoomPercent,
                    actualSizeZoomPercent = actualSizeZoomPercent,
                    onZoomPercentChange = { percent -> surface?.setZoom(zoomFractionOf(percent)) },
                    onFitWidth = { surface?.fitWidth() },
                    onFitActualSize = { surface?.setZoom(actualSizeZoom(xdpi, viewport?.viewWidthPx ?: 1f)) },
                    strokeCount = strokeCount,
                    onClearAll = { surface?.clearAll() },
                    onOutsideTapped = { reduceSelector(SheetSelectorEvent.OutsideTapped) },
                    onBackPressed = { reduceSelector(SheetSelectorEvent.BackPressed) }
                )

                val menuBounds = selectionBoundsViewPx
                val paneViewport = viewport
                val menuHidden = selectionEditing || selectorState.openPanel != null
                if (menuBounds != null && paneViewport != null && selectedStrokeIds.isNotEmpty() && !menuHidden) {
                    SelectionMenuOverlay(
                        boundsViewPx = menuBounds,
                        surfaceOriginInWindow = { surface?.originInWindow() ?: IntOffset.Zero },
                        paneWidthPx = paneViewport.viewWidthPx,
                        paneHeightPx = paneViewport.viewHeightPx,
                        hasConvertToTextHandler = onConvertToText != null,
                        onAction = { action ->
                            when (action) {
                                SelectionMenuAction.CONVERT_TO_TEXT -> onConvertToText?.invoke(surface?.selectedStrokesInZOrder().orEmpty())
                                SelectionMenuAction.COPY -> surface?.copySelection()
                                SelectionMenuAction.DELETE -> surface?.deleteSelection()
                            }
                        }
                    )
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

/** A menu item's own horizontal padding (`rail-spec.md` 2.2: "padding: 0 14px"); its own min-height reuses [FoliumSpacing.touchTarget], the same 44dp the spec calls for. */
private val SelectionMenuItemHorizontalPadding = 14.dp

/** Where this view's own top-left corner sits in its window: a [Popup] is positioned in window pixels, the selection in this view's. */
private fun View.originInWindow(): IntOffset {
    val location = IntArray(2)
    getLocationInWindow(location)

    return IntOffset(location[0], location[1])
}

/**
 * The selection menu: a box of items in a row, aligned with the selection's own left edge under it, or
 * above it once there is no room below. The design's leader tick is left out on purpose: the menu has
 * to stand clear of the corner handles, and a tick floating in that gap reads as a stray mark. Positioned through a [PopupPositionProvider] built from [selectionMenuPlacement]
 * rather than a fixed offset, since the box's own width depends on how many items [hasConvertToTextHandler]
 * puts in it and Compose only reports a [Popup]'s own content size once it has been measured.
 */
@Composable
internal fun SelectionMenuOverlay(
    boundsViewPx: ViewRect,
    surfaceOriginInWindow: () -> IntOffset,
    paneWidthPx: Float,
    paneHeightPx: Float,
    hasConvertToTextHandler: Boolean,
    onAction: (SelectionMenuAction) -> Unit
) {
    val density = LocalDensity.current
    // The menu is its own window and takes every touch inside it, so it has to stay clear of the corner handles' hit areas.
    val handleClearancePx = with(density) { (FoliumSpacing.touchTarget / 2).roundToPx() }

    val positionProvider = remember(boundsViewPx, paneWidthPx, paneHeightPx, handleClearancePx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val placement = selectionMenuPlacement(
                    selectionLeftPx = boundsViewPx.left.toInt(),
                    selectionTopPx = boundsViewPx.top.toInt() - handleClearancePx,
                    selectionBottomPx = boundsViewPx.bottom.toInt() + handleClearancePx,
                    paneWidthPx = paneWidthPx.toInt(),
                    paneHeightPx = paneHeightPx.toInt(),
                    marginStartPx = 0,
                    contentWidthPx = popupContentSize.width,
                    contentHeightPx = popupContentSize.height
                )
                val origin = surfaceOriginInWindow()

                return IntOffset(origin.x + placement.leftPx, origin.y + placement.topPx)
            }
        }
    }

    Popup(popupPositionProvider = positionProvider) {
        SelectionMenuBox(hasConvertToTextHandler = hasConvertToTextHandler, onAction = onAction)
    }
}

/** The box itself: a 1dp ink border on a paper background, its items in a row separated by 1dp rules (`rail-spec.md` 2.2, ELEGIR panel's own menu anatomy). */
@Composable
private fun SelectionMenuBox(hasConvertToTextHandler: Boolean, onAction: (SelectionMenuAction) -> Unit) {
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.surface)
            .foliumBorder(1.dp, MaterialTheme.colorScheme.onSurface)
            .testTag(SheetPaneTestTags.SELECTION_MENU)
    ) {
        val items = selectionMenuItems(hasConvertToTextHandler)
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .heightIn(min = FoliumSpacing.touchTarget)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            SelectionMenuItemButton(item = item, onClick = { onAction(item.action) })
        }
    }
}

@Composable
private fun SelectionMenuItemButton(item: SelectionMenuItem, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val alarm = MaterialTheme.colorScheme.error

    val textColor = when {
        item.isPrimary -> paper
        item.action == SelectionMenuAction.DELETE -> alarm
        else -> ink
    }
    val backgroundColor = if (item.isPrimary) ink else paper

    Box(
        Modifier
            .background(backgroundColor)
            .heightIn(min = FoliumSpacing.touchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = SelectionMenuItemHorizontalPadding)
            .testTag(item.action.testTag()),
        contentAlignment = Alignment.Center
    ) {
        Text(text = stringResource(item.action.labelRes()).uppercase(), style = FoliumType.CaptionEmphasis, color = textColor)
    }
}

private fun SelectionMenuAction.labelRes(): Int = when (this) {
    SelectionMenuAction.CONVERT_TO_TEXT -> R.string.sheet_selection_menu_convert_to_text
    SelectionMenuAction.COPY -> R.string.sheet_selection_menu_copy
    SelectionMenuAction.DELETE -> R.string.sheet_selection_menu_delete
}

private fun SelectionMenuAction.testTag(): String = when (this) {
    SelectionMenuAction.CONVERT_TO_TEXT -> SheetPaneTestTags.SELECTION_MENU_CONVERT
    SelectionMenuAction.COPY -> SheetPaneTestTags.SELECTION_MENU_COPY
    SelectionMenuAction.DELETE -> SheetPaneTestTags.SELECTION_MENU_DELETE
}
