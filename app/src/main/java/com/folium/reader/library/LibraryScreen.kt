package com.folium.reader.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folium.reader.R
import com.folium.reader.ui.FoliumSpacing
import com.folium.reader.ui.FoliumWidthClass
import com.folium.reader.ui.FoliumGrid
import com.folium.reader.ui.FoliumDialog
import com.folium.reader.ui.FoliumDivider
import com.folium.reader.ui.FoliumMenu
import com.folium.reader.ui.FoliumType
import com.folium.reader.ui.foliumBorder
import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.ink.SheetSummary
import com.folium.reader.core.library.AppearanceMode
import com.folium.reader.core.library.BookId
import com.folium.reader.core.library.ImportOutcome
import com.folium.reader.core.library.ImportProgress
import com.folium.reader.core.library.ImportReport
import com.folium.reader.core.library.LibraryHomeState
import com.folium.reader.core.library.LibraryViewMode
import com.folium.reader.core.library.ShelfEntry
import kotlin.math.roundToInt

object LibraryTestTags {
    const val LOADING = "library-loading"
    const val ADD = "library-add"
    const val ADD_MENU = "library-add-menu"
    const val ADD_IMPORT = "library-add-import"
    const val ADD_NEW_SHEET = "library-add-new-sheet"
    const val SHEET_FAILURE = "library-sheet-failure"
    const val SHEET_FAILURE_DISMISS = "library-sheet-failure-dismiss"
    const val IMPORT_REPORT = "library-import-report"
    const val IMPORT_REPORT_DISMISS = "library-import-report-dismiss"
    const val IMPORTING = "library-importing"
    const val EMPTY = "library-empty"
    const val EMPTY_ADD = "library-empty-add"
    const val BOOKS = "library-books"
    const val BOOKS_GRID = "library-books-grid"
    const val VIEW_MENU = "library-view-menu"
    const val VIEW_LIST = "library-view-list"
    const val VIEW_GRID = "library-view-grid"
    const val APPEARANCE_SYSTEM = "library-appearance-system"
    const val APPEARANCE_LIGHT = "library-appearance-light"
    const val APPEARANCE_DARK = "library-appearance-dark"
    const val APPEARANCE_E_INK_LIGHT = "library-appearance-e-ink-light"
    const val APPEARANCE_E_INK_DARK = "library-appearance-e-ink-dark"
    const val REMOVE_CONFIRM = "library-remove-confirm"
    const val REMOVE_DELETE_SHEETS = "library-remove-delete-sheets"
    const val REMOVE_PAGE_INK = "library-remove-page-ink"
    const val DETAIL_PANE = "library-detail-pane"
    const val SEARCH = "library-search"
    const val SEARCH_FIELD = "library-search-field"
    const val SEARCH_DONE = "library-search-done"
    const val CONTINUE = "library-continue"
    const val FILTER_ALL = "library-filter-all"
    const val FILTER_STARTED = "library-filter-started"
    const val FILTER_UNOPENED = "library-filter-unopened"

    fun book(id: BookId): String = "library-book/${id.value}"
    fun gridBook(id: BookId): String = "library-book-grid/${id.value}"
    fun removeBook(id: BookId): String = "library-book-remove/${id.value}"
    fun bookThumbnail(id: BookId): String = "library-book-thumbnail/${id.value}"
    fun bookProgress(id: BookId): String = "library-book-progress/${id.value}"
    fun untitled(id: BookId): String = "library-book-untitled/${id.value}"
    fun bookDetail(id: BookId): String = "library-book-detail/${id.value}"
    fun bookMenu(id: BookId): String = "library-book-menu/${id.value}"
}

/**
 * Why the shelf is showing its one sheet-failure banner, typed by the operation that failed rather
 * than carried as a single flag: a sheet that failed to open and one that failed to be created are
 * both "something did not work", but not the same something, and each needs its own text to say so.
 */
internal sealed class SheetFailure {
    data object CREATE : SheetFailure()
    data object OPEN : SheetFailure()
    data object DELETE : SheetFailure()
}

private val MessageWidth = 480.dp
internal val ThumbnailWidth = 60.dp

/**
 * [ThumbnailWidth] at the system's own cover ratio ([FoliumGrid.COVER_ASPECT]) — the row's thumbnail
 * is a smaller print of the same cover atom the grid and the detail hero draw, not a shape of its
 * own.
 */
internal val ThumbnailHeight = ThumbnailWidth / FoliumGrid.COVER_ASPECT
internal val RowMinHeight = 96.dp
private val ProgressBarThickness = 4.dp

/**
 * Columns are derived from the system's cover floor rather than a size of their own. The design
 * asks for four columns, which a 412dp phone gets exactly; a 360dp one gets three, because four
 * would put the cover at 71dp and a cover stops being recognizable below eighty.
 */
private val GridCellMinWidth = FoliumGrid.minCover

/**
 * The continue-reading hero's own exception to the system's 1:1.4 cover ratio: two cover columns
 * wide by 240px tall in the shelf's own layout (S-Library.dc.html), which is 3:4 rather than the
 * cover atom's own proportion. Every other cover-shaped surface — the grid cell, the row thumbnail,
 * the detail hero, the two-pane hero — draws at [FoliumGrid.COVER_ASPECT] instead.
 */
internal const val ContinueReadingCoverAspectRatio = 3f / 4f
private val CoverEdgeThickness = 4.dp

/** The system's own rule weight, drawn around the book a wide layout is showing beside the shelf. */
private val SelectionBorder = 2.dp

/**
 * The ink outline a wide layout draws around the book it is showing beside the shelf.
 *
 * T-Library.dc.html draws this mark on the cover box alone, with `outline-offset: 0` — never around
 * the whole cell, which is what let a selected book's title spill outside its own border. The list
 * artboards never draw a selection mark of their own, so the row reuses the same target on its
 * thumbnail instead of inventing a second style for it.
 */
@Composable
private fun Modifier.selectionMarker(isSelected: Boolean): Modifier =
    if (isSelected) foliumBorder(SelectionBorder, MaterialTheme.colorScheme.onSurface) else this

/** Eight of twelve modules to the shelf, four to the book: the split the design draws. */
private const val SHELF_PANE_WEIGHT = 8f
private const val DETAIL_PANE_WEIGHT = 4f

private val SearchFieldRestingBorder = 1.dp
private val SearchFieldFocusedBorder = 2.dp

private val MenuItemHorizontalPadding = 14.dp

/**
 * A cover's own tone, so a missing thumbnail — or one that happens to be a blank white page — still
 * reads as a slot on the shelf rather than vanishing into the paper behind it. The system never
 * outlines a cover; the field tone is the only thing that has to carry that distinction.
 */
internal fun coverBackgroundColor(scheme: ColorScheme): Color = scheme.surfaceVariant

/**
 * The row's own separator. The same line token every other hairline rule in the shelf reads from,
 * kept apart from ink so the 2px rule the system draws stays reserved for the header.
 */
internal fun rowDividerColor(scheme: ColorScheme): Color = scheme.outlineVariant

/** A field's border, as the system states it: a 1px line at rest, a 2px ink border once it has focus. */
internal data class SearchFieldBorder(val width: Dp, val color: Color)

internal fun searchFieldBorder(focused: Boolean, scheme: ColorScheme): SearchFieldBorder =
    if (focused) SearchFieldBorder(SearchFieldFocusedBorder, scheme.onSurface)
    else SearchFieldBorder(SearchFieldRestingBorder, scheme.outline)

/**
 * The library home, and the surface the app opens on.
 *
 * Stateless by design: every state it can render arrives as a [LibraryHomeState] and every
 * thumbnail in [thumbnails] rather than being decoded or looked up here, which is what lets each
 * state be exercised directly. The one thing it owns is which book a removal is currently asking
 * about, which is transient UI rather than library state.
 */
@Composable
internal fun LibraryScreen(
    state: LibraryHomeState,
    thumbnails: Map<BookId, Bitmap?>,
    viewMode: LibraryViewMode,
    appearanceMode: AppearanceMode,
    onAddBooks: () -> Unit,
    onNewSheet: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    onShowDetail: (BookId) -> Unit,
    onRemoveBook: (BookId, deleteSheets: Boolean) -> Unit,
    selectedBookId: BookId? = null,
    sidePane: (@Composable () -> Unit)? = null,
    onDismissReport: () -> Unit,
    onViewModeChange: (LibraryViewMode) -> Unit,
    onAppearanceModeChange: (AppearanceMode) -> Unit,
    windowWidthClass: FoliumWidthClass? = null,
    sheetFailure: SheetFailure? = null,
    onDismissSheetFailure: () -> Unit = {},
    sheets: List<SheetSummary> = emptyList(),
    sheetThumbnails: Map<SheetId, Bitmap?> = emptyMap(),
    unreadableSheetCount: Int = 0,
    onSheetOpen: (SheetId) -> Unit = {},
    onSheetDelete: (SheetId) -> Unit = {},
    onCountInkedPages: (BookId, onCount: (PageInkCount) -> Unit) -> Unit = { _, onCount -> onCount(PageInkCount.Known(0)) },
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (state) {
                is LibraryHomeState.Loading -> LoadingScene()

                is LibraryHomeState.Shelf -> ShelfScene(
                    state = state,
                    thumbnails = thumbnails,
                    viewMode = viewMode,
                    appearanceMode = appearanceMode,
                    onAddBooks = onAddBooks,
                    onNewSheet = onNewSheet,
                    onOpenBook = onOpenBook,
                    onShowDetail = onShowDetail,
                    onRemoveBook = onRemoveBook,
                    selectedBookId = selectedBookId,
                    sidePane = sidePane,
                    onDismissReport = onDismissReport,
                    onViewModeChange = onViewModeChange,
                    onAppearanceModeChange = onAppearanceModeChange,
                    windowWidthClass = windowWidthClass,
                    sheetFailure = sheetFailure,
                    onDismissSheetFailure = onDismissSheetFailure,
                    sheets = sheets,
                    sheetThumbnails = sheetThumbnails,
                    unreadableSheetCount = unreadableSheetCount,
                    onSheetOpen = onSheetOpen,
                    onSheetDelete = onSheetDelete,
                    onCountInkedPages = onCountInkedPages
                )
            }
        }
    }
}

@Composable
private fun LoadingScene() {
    Box(Modifier.fillMaxSize().testTag(LibraryTestTags.LOADING), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.library_home_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun ShelfScene(
    state: LibraryHomeState.Shelf,
    thumbnails: Map<BookId, Bitmap?>,
    viewMode: LibraryViewMode,
    appearanceMode: AppearanceMode,
    onAddBooks: () -> Unit,
    onNewSheet: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    onShowDetail: (BookId) -> Unit,
    onRemoveBook: (BookId, deleteSheets: Boolean) -> Unit,
    selectedBookId: BookId? = null,
    sidePane: (@Composable () -> Unit)? = null,
    onDismissReport: () -> Unit,
    onViewModeChange: (LibraryViewMode) -> Unit,
    onAppearanceModeChange: (AppearanceMode) -> Unit,
    windowWidthClass: FoliumWidthClass? = null,
    sheetFailure: SheetFailure? = null,
    onDismissSheetFailure: () -> Unit = {},
    sheets: List<SheetSummary> = emptyList(),
    sheetThumbnails: Map<SheetId, Bitmap?> = emptyMap(),
    unreadableSheetCount: Int = 0,
    onSheetOpen: (SheetId) -> Unit = {},
    onSheetDelete: (SheetId) -> Unit = {},
    onCountInkedPages: (BookId, onCount: (PageInkCount) -> Unit) -> Unit = { _, onCount -> onCount(PageInkCount.Known(0)) }
) {
    var pendingRemoval by remember { mutableStateOf<ShelfEntry?>(null) }
    var pendingSheetDeletion by remember { mutableStateOf<SheetSummary?>(null) }
    var filter by rememberSaveable { mutableStateOf(ShelfFilter.ALL) }
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    val importing = state.importing

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A caller that already knows the window's width class passes it down rather than have
        // this box measure its own, narrower one: the two-pane decision has to agree with whatever
        // else in the window made it, or a book can end up selected with neither a full-screen
        // detail screen nor a side pane there to show it.
        val widthClass = windowWidthClass ?: FoliumWidthClass.of(maxWidth)

        Column(Modifier.fillMaxSize()) {
            LibraryHeader(
                query = query,
                onQueryChange = { query = it },
                importing = importing != null,
                viewMode = viewMode,
                appearanceMode = appearanceMode,
                onAddBooks = onAddBooks,
                onNewSheet = onNewSheet,
                onSearch = { query = "" },
                onViewModeChange = onViewModeChange,
                onAppearanceModeChange = onAppearanceModeChange,
                margin = widthClass.margin
            )

            importing?.let { ImportingStrip(it) }

            state.report?.let { ImportReportBanner(it, onDismissReport) }

            if (sheetFailure != null) SheetFailureBanner(sheetFailure, onDismissSheetFailure)

            when {
                state.entries.isEmpty() && sheets.isEmpty() -> EmptyScene(onAddBooks)

                else -> ShelfBody(
                    state = state,
                    thumbnails = thumbnails,
                    viewMode = viewMode,
                    widthClass = widthClass,
                    enabled = importing == null,
                    filter = filter,
                    query = query,
                    selectedBookId = selectedBookId,
                    sidePane = sidePane,
                    onFilterChange = { filter = it },
                    onOpenBook = onOpenBook,
                    onShowDetail = onShowDetail,
                    onRemoveRequested = { pendingRemoval = it },
                    sheets = sheets,
                    sheetThumbnails = sheetThumbnails,
                    unreadableSheetCount = unreadableSheetCount,
                    onSheetOpen = onSheetOpen,
                    onSheetDeleteRequested = { pendingSheetDeletion = it }
                )
            }
        }
    }

    pendingRemoval?.let { entry ->
        var pageInk by remember(entry.book.id) { mutableStateOf<PageInkCount?>(null) }

        LaunchedEffect(entry.book.id) {
            onCountInkedPages(entry.book.id) { pageInk = it }
        }

        RemoveConfirmDialog(
            entry = entry,
            prompt = removeBookPrompt(entry.book.id, sheets, pageInk),
            onDismiss = { pendingRemoval = null },
            onConfirm = { deleteSheets ->
                pendingRemoval = null
                onRemoveBook(entry.book.id, deleteSheets)
            }
        )
    }

    pendingSheetDeletion?.let { sheet ->
        SheetDeleteConfirmDialog(
            sheet = sheet,
            onDismiss = { pendingSheetDeletion = null },
            onConfirm = {
                pendingSheetDeletion = null
                onSheetDelete(sheet.id)
            }
        )
    }
}

/**
 * Whichever shelf content the view mode picks, split beside a chosen book once the width class
 * says there is room for two panes at once.
 */
@Composable
private fun ShelfBody(
    state: LibraryHomeState.Shelf,
    thumbnails: Map<BookId, Bitmap?>,
    viewMode: LibraryViewMode,
    widthClass: FoliumWidthClass,
    enabled: Boolean,
    filter: ShelfFilter,
    query: String?,
    selectedBookId: BookId?,
    sidePane: (@Composable () -> Unit)?,
    onFilterChange: (ShelfFilter) -> Unit,
    onOpenBook: (BookId) -> Unit,
    onShowDetail: (BookId) -> Unit,
    onRemoveRequested: (ShelfEntry) -> Unit,
    sheets: List<SheetSummary> = emptyList(),
    sheetThumbnails: Map<SheetId, Bitmap?> = emptyMap(),
    unreadableSheetCount: Int = 0,
    onSheetOpen: (SheetId) -> Unit = {},
    onSheetDeleteRequested: (SheetSummary) -> Unit = {}
) {
    // A book can only be marked as the one the detail pane is showing if that pane is actually
    // showing: the width class alone says there is room for it, not that a caller supplied one.
    val showsSplit = widthClass.showsTwoPanes && sidePane != null

    val shelf = @Composable { modifier: Modifier ->
        if (viewMode == LibraryViewMode.GRID) {
            BookGrid(
                entries = state.entries,
                thumbnails = thumbnails,
                enabled = enabled,
                filter = filter,
                query = query,
                widthClass = widthClass,
                selectedBookId = selectedBookId.takeIf { showsSplit },
                onFilterChange = onFilterChange,
                onOpenBook = onOpenBook,
                onShowDetail = onShowDetail,
                onRemoveRequested = onRemoveRequested,
                sheets = sheets,
                sheetThumbnails = sheetThumbnails,
                unreadableSheetCount = unreadableSheetCount,
                onSheetOpen = onSheetOpen,
                onSheetDeleteRequested = onSheetDeleteRequested,
                modifier = modifier
            )
        } else {
            BookList(
                entries = state.entries,
                thumbnails = thumbnails,
                enabled = enabled,
                widthClass = widthClass,
                selectedBookId = selectedBookId.takeIf { showsSplit },
                onOpenBook = onOpenBook,
                onShowDetail = onShowDetail,
                onRemoveRequested = onRemoveRequested,
                sheets = sheets,
                sheetThumbnails = sheetThumbnails,
                unreadableSheetCount = unreadableSheetCount,
                onSheetOpen = onSheetOpen,
                onSheetDeleteRequested = onSheetDeleteRequested,
                modifier = modifier
            )
        }
    }

    if (showsSplit) {
        Row(Modifier.fillMaxSize()) {
            shelf(Modifier.weight(SHELF_PANE_WEIGHT))
            FoliumDivider.Vertical(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(DETAIL_PANE_WEIGHT).testTag(LibraryTestTags.DETAIL_PANE)) {
                sidePane()
            }
        }
    } else {
        shelf(Modifier.fillMaxSize())
    }
}

/**
 * One line: the mark, a way to search, a way to add.
 *
 * It used to spend three stacked lines on a headline and a count before the first book — a third of
 * the screen naming a screen the reader was already looking at. The count moved to the section rule
 * above the shelf, where it labels the thing it counts. What is left is the mark, which places the
 * app once, and the two actions a reader came here to take.
 *
 * The rule under it is the system's 2px section rule, and it is what separates this from the shelf
 * now that nothing is boxed.
 */
@Composable
private fun LibraryHeader(
    query: String?,
    onQueryChange: (String?) -> Unit,
    importing: Boolean,
    viewMode: LibraryViewMode,
    appearanceMode: AppearanceMode,
    onAddBooks: () -> Unit,
    onNewSheet: () -> Unit,
    onSearch: () -> Unit,
    onViewModeChange: (LibraryViewMode) -> Unit,
    onAppearanceModeChange: (AppearanceMode) -> Unit,
    margin: Dp
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = margin)) {
        if (query != null) {
            LibrarySearchField(query = query, onQueryChange = onQueryChange)
            Spacer(Modifier.height(FoliumSpacing.xs))
            FoliumDivider.Horizontal(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface)
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = FoliumSpacing.xl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.library_wordmark),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )

            HeaderIcon(
                onClick = onSearch,
                enabled = !importing,
                description = stringResource(R.string.library_search),
                testTag = LibraryTestTags.SEARCH,
                filled = false
            ) { tint -> drawSearchGlyph(tint) }

            LibraryAddMenu(
                enabled = !importing,
                onImport = onAddBooks,
                onNewSheet = onNewSheet
            )

            LibraryOptionsMenu(
                viewMode = viewMode,
                appearanceMode = appearanceMode,
                enabled = !importing,
                onViewModeChange = onViewModeChange,
                onAppearanceModeChange = onAppearanceModeChange
            )
        }

        Spacer(Modifier.height(FoliumSpacing.xs))

        FoliumDivider.Horizontal(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Filters the shelf by title while it is open, and gives the shelf back untouched when closed.
 *
 * The border is the only thing that says where a reader is typing: it stays a 1px line at rest and
 * thickens to a 2px ink border on focus, in place rather than the signal colour, which the system
 * keeps reserved for progress. `Modifier.border` draws that stroke inside the field's own bounds, so
 * neither the field's measured size nor the padding around its text moves when the border thickens.
 */
@Composable
private fun LibrarySearchField(query: String, onQueryChange: (String?) -> Unit) {
    val focus = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val border = searchFieldBorder(focused, MaterialTheme.colorScheme)
    LaunchedEffect(Unit) { focus.requestFocus() }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = FoliumSpacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(FoliumSpacing.touchTarget)
                .foliumBorder(border.width, border.color)
                .padding(horizontal = FoliumSpacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchFieldGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.width(FoliumSpacing.xs))

            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                interactionSource = interactionSource,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRequester(focus)
                    .testTag(LibraryTestTags.SEARCH_FIELD),
                decorationBox = { field ->
                    Box(
                        modifier = Modifier.fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.library_search),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        field()
                    }
                }
            )
        }

        Text(
            text = stringResource(R.string.library_search_done),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clickable { onQueryChange(null) }
                .heightIn(min = FoliumSpacing.touchTarget)
                .wrapContentHeight()
                .padding(horizontal = FoliumSpacing.s)
                .testTag(LibraryTestTags.SEARCH_DONE)
        )
    }
}

/**
 * The system's own search glyph (S-Componentes.dc.html "02 · ICONO" / "03 · CAMPO"): a magnifier
 * centred on whatever box calls it, drawn relative to [DrawScope.center] so the same shape fits the
 * header's 44dp touch target and the smaller box a field draws it in.
 */
private fun DrawScope.drawSearchGlyph(tint: Color) {
    drawCircle(
        color = tint,
        radius = 5.8f.dp.toPx(),
        center = center.copy(x = center.x - 1.4f.dp.toPx(), y = center.y - 1.4f.dp.toPx()),
        style = Stroke(width = 1.6f.dp.toPx())
    )
    drawLine(
        color = tint,
        start = center.copy(x = center.x + 2.6f.dp.toPx(), y = center.y + 2.6f.dp.toPx()),
        end = center.copy(x = center.x + 7.5f.dp.toPx(), y = center.y + 7.5f.dp.toPx()),
        strokeWidth = 1.6f.dp.toPx(),
        cap = StrokeCap.Round
    )
}

/** The search glyph at the field's own icon size, rather than the header's 44dp touch target. */
private val SearchFieldGlyphSize = 18.dp

@Composable
private fun SearchFieldGlyph(tint: Color) {
    Spacer(Modifier.size(SearchFieldGlyphSize).drawBehind { drawSearchGlyph(tint) })
}

/** The library menu's own overflow mark (S-Ajustes.dc.html): three filled dots, stacked. */
private fun DrawScope.drawKebab(tint: Color) {
    val radius = 1.5f.dp.toPx()

    listOf(-6f, 0f, 6f).forEach { offsetY ->
        drawCircle(color = tint, radius = radius, center = center.copy(y = center.y + offsetY.dp.toPx()))
    }
}

/** The check a selected layout or appearance option draws in its own menu row (S-Ajustes.dc.html). */
private fun DrawScope.drawCheck(tint: Color) {
    val unit = size.width / 20f
    val path = Path().apply {
        moveTo(4f * unit, 10.5f * unit)
        lineTo(8f * unit, 14.5f * unit)
        lineTo(16f * unit, 5.5f * unit)
    }
    drawPath(
        path = path,
        color = tint,
        style = Stroke(width = 1.9f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/** The library menu's own check mark, drawn at the row's own 16dp icon size (S-Ajustes.dc.html). */
private val CheckGlyphSize = 16.dp

@Composable
private fun CheckGlyph() {
    val tint = MaterialTheme.colorScheme.onSurface
    Spacer(Modifier.size(CheckGlyphSize).drawBehind { drawCheck(tint) })
}

/**
 * A drawn glyph in a 44dp square. Drawn rather than shipped as a vector because the system's icons
 * are a stroke width and a 20dp box, which is less than a drawable would cost to carry.
 */
@Composable
private fun HeaderIcon(
    onClick: () -> Unit,
    enabled: Boolean,
    description: String,
    testTag: String,
    filled: Boolean,
    glyph: DrawScope.(Color) -> Unit
) {
    val background = if (filled) MaterialTheme.colorScheme.onSurface else Color.Transparent
    val tint = if (filled) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface
    val enabledTint = if (enabled) tint else MaterialTheme.colorScheme.outlineVariant

    Spacer(
        Modifier
            .size(FoliumSpacing.touchTarget)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description; role = Role.Button }
            .testTag(testTag)
            .drawBehind { glyph(enabledTint) }
    )
}

/**
 * The library's own entry point for adding content, behind one menu rather than one button per kind:
 * importing a file keeps its previous behaviour unchanged, and creating a handwritten sheet is a
 * second row beside it rather than a second header icon.
 */
@Composable
private fun LibraryAddMenu(enabled: Boolean, onImport: () -> Unit, onNewSheet: () -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box {
        HeaderIcon(
            onClick = { open = true },
            enabled = enabled,
            description = stringResource(R.string.library_add_menu),
            testTag = LibraryTestTags.ADD,
            filled = true
        ) { tint ->
            val arm = 6f.dp.toPx()
            drawLine(tint, center.copy(y = center.y - arm), center.copy(y = center.y + arm), 1.6f.dp.toPx(), StrokeCap.Round)
            drawLine(tint, center.copy(x = center.x - arm), center.copy(x = center.x + arm), 1.6f.dp.toPx(), StrokeCap.Round)
        }

        FoliumMenu(
            expanded = open && enabled,
            onDismissRequest = { open = false },
            modifier = Modifier.testTag(LibraryTestTags.ADD_MENU)
        ) {
            MenuActionItem(
                text = stringResource(R.string.library_add_import),
                onClick = { open = false; onImport() },
                testTag = LibraryTestTags.ADD_IMPORT
            )
            FoliumDivider.Horizontal(color = MaterialTheme.colorScheme.outlineVariant)
            MenuActionItem(
                text = stringResource(R.string.library_add_new_sheet),
                onClick = { open = false; onNewSheet() },
                testTag = LibraryTestTags.ADD_NEW_SHEET
            )
        }
    }
}

/**
 * Global display choices live behind the overflow rather than beside Add: they are set occasionally,
 * while adding a book is why the reader came here. Layout and appearance are labeled and separated
 * so the expanded menu remains scannable as one control.
 */
@Composable
private fun LibraryOptionsMenu(
    viewMode: LibraryViewMode,
    appearanceMode: AppearanceMode,
    enabled: Boolean,
    onViewModeChange: (LibraryViewMode) -> Unit,
    onAppearanceModeChange: (AppearanceMode) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.library_menu)

    Box {
        HeaderIcon(
            onClick = { open = true },
            enabled = enabled,
            description = description,
            testTag = LibraryTestTags.VIEW_MENU,
            filled = false
        ) { tint -> drawKebab(tint) }

        FoliumMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            MenuSectionLabel(R.string.library_layout)
            ViewModeItem(R.string.library_view_list, LibraryTestTags.VIEW_LIST, LibraryViewMode.LIST, viewMode) {
                open = false
                onViewModeChange(it)
            }
            FoliumDivider.Horizontal(color = MaterialTheme.colorScheme.outlineVariant)
            ViewModeItem(R.string.library_view_grid, LibraryTestTags.VIEW_GRID, LibraryViewMode.GRID, viewMode) {
                open = false
                onViewModeChange(it)
            }

            FoliumDivider.Horizontal(color = MaterialTheme.colorScheme.onSurface)
            MenuSectionLabel(R.string.library_appearance)
            AppearanceModeItem(
                R.string.library_appearance_system,
                LibraryTestTags.APPEARANCE_SYSTEM,
                AppearanceMode.SYSTEM,
                appearanceMode
            ) {
                open = false
                onAppearanceModeChange(it)
            }
            FoliumDivider.Horizontal(color = MaterialTheme.colorScheme.outlineVariant)
            AppearanceModeItem(
                R.string.library_appearance_light,
                LibraryTestTags.APPEARANCE_LIGHT,
                AppearanceMode.LIGHT,
                appearanceMode
            ) {
                open = false
                onAppearanceModeChange(it)
            }
            FoliumDivider.Horizontal(color = MaterialTheme.colorScheme.outlineVariant)
            AppearanceModeItem(
                R.string.library_appearance_dark,
                LibraryTestTags.APPEARANCE_DARK,
                AppearanceMode.DARK,
                appearanceMode
            ) {
                open = false
                onAppearanceModeChange(it)
            }
            FoliumDivider.Horizontal(color = MaterialTheme.colorScheme.outlineVariant)
            AppearanceModeItem(
                R.string.library_appearance_e_ink_light,
                LibraryTestTags.APPEARANCE_E_INK_LIGHT,
                AppearanceMode.E_INK_LIGHT,
                appearanceMode
            ) {
                open = false
                onAppearanceModeChange(it)
            }
            FoliumDivider.Horizontal(color = MaterialTheme.colorScheme.outlineVariant)
            AppearanceModeItem(
                R.string.library_appearance_e_ink_dark,
                LibraryTestTags.APPEARANCE_E_INK_DARK,
                AppearanceMode.E_INK_DARK,
                appearanceMode
            ) {
                open = false
                onAppearanceModeChange(it)
            }
        }
    }
}

/** S-Ajustes.dc.html: "DISPOSICIÓN" and "APARIENCIA" — the same 11/700/1.2px label the shelf's own section rule draws. */
@Composable
private fun MenuSectionLabel(label: Int) {
    Text(
        text = stringResource(label).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = MenuItemHorizontalPadding, end = MenuItemHorizontalPadding, top = 12.dp, bottom = 6.dp)
            .semantics { heading() }
    )
}

@Composable
private fun ViewModeItem(
    label: Int,
    testTag: String,
    mode: LibraryViewMode,
    active: LibraryViewMode,
    onChosen: (LibraryViewMode) -> Unit
) {
    DropdownMenuItem(
        text = { Text(stringResource(label), style = FoliumType.BodyMid) },
        trailingIcon = if (mode != active) null else {
            { CheckGlyph() }
        },
        onClick = { onChosen(mode) },
        contentPadding = PaddingValues(horizontal = MenuItemHorizontalPadding),
        modifier = Modifier
            .sizeIn(minHeight = FoliumSpacing.touchTarget)
            .semantics { selected = mode == active }
            .testTag(testTag)
    )
}

@Composable
private fun AppearanceModeItem(
    label: Int,
    testTag: String,
    mode: AppearanceMode,
    active: AppearanceMode,
    onChosen: (AppearanceMode) -> Unit
) {
    DropdownMenuItem(
        text = { Text(stringResource(label), style = FoliumType.BodyMid) },
        trailingIcon = if (mode != active) null else {
            { CheckGlyph() }
        },
        onClick = { onChosen(mode) },
        contentPadding = PaddingValues(horizontal = MenuItemHorizontalPadding),
        modifier = Modifier
            .sizeIn(minHeight = FoliumSpacing.touchTarget)
            .semantics { selected = mode == active }
            .testTag(testTag)
    )
}

/**
 * An import holds the same worker that opens a book, so a batch in flight genuinely delays an open.
 * Naming the progress is what turns that wait into an explanation instead of a freeze.
 */
@Composable
private fun ImportingStrip(progress: ImportProgress) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag(LibraryTestTags.IMPORTING)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = stringResource(R.string.library_importing, progress.completed, progress.total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))

        ProgressBar(
            fraction = progress.completed.toFloat() / progress.total.coerceAtLeast(1).toFloat(),
            color = MaterialTheme.colorScheme.tertiary
        )
    }
}

/**
 * Successful imports need no row — they are the new books. Only the files that did not make it get
 * one, each with its typed explanation, so the report is never a generic apology.
 */
@Composable
private fun ImportReportBanner(report: ImportReport, onDismiss: () -> Unit) {
    val failures = report.failures
    val container =
        if (failures.isEmpty()) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
    val onContainer =
        if (failures.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .testTag(LibraryTestTags.IMPORT_REPORT)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)
    ) {
        Text(
            text = importSummary(report),
            style = MaterialTheme.typography.titleSmall,
            color = onContainer
        )

        failures.forEach { failure ->
            Spacer(Modifier.height(10.dp))

            ImportFailureRow(failure, onContainer)
        }

        TextButton(

            shape = MaterialTheme.shapes.small,
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.End)
                .heightIn(min = FoliumSpacing.touchTarget)
                .testTag(LibraryTestTags.IMPORT_REPORT_DISMISS)
        ) {
            Text(stringResource(R.string.library_import_dismiss), color = onContainer)
        }
    }
}

@Composable
private fun ImportFailureRow(failure: ImportOutcome.Failed, contentColor: Color) {
    Column(Modifier.padding(end = 8.dp)) {
        Text(
            text = sanitizedLabel(failure.label),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = stringResource(ImportCopy.explanation(failure.failure)),
            style = MaterialTheme.typography.bodySmall,
            color = contentColor
        )
    }
}

/**
 * Shown when something a reader did to a handwritten sheet — creating it, opening it, deleting it —
 * failed. [SheetFailure] says which, and each carries its own text: a full disk or a storage
 * permission failure looks the same to this screen whichever operation hit it, but the reader still
 * needs to be told what did not happen rather than a generic "something went wrong". The same visual
 * language [ImportReportBanner] uses for a failed import is what a reader already reads as "this did
 * not work" on this screen.
 */
@Composable
private fun SheetFailureBanner(failure: SheetFailure, onDismiss: () -> Unit) {
    val message = when (failure) {
        SheetFailure.CREATE -> stringResource(R.string.library_sheet_create_failed)
        SheetFailure.OPEN -> stringResource(R.string.library_sheet_open_failed)
        SheetFailure.DELETE -> stringResource(R.string.library_sheet_delete_failed)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .testTag(LibraryTestTags.SHEET_FAILURE)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )

        TextButton(
            shape = MaterialTheme.shapes.small,
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.End)
                .heightIn(min = FoliumSpacing.touchTarget)
                .testTag(LibraryTestTags.SHEET_FAILURE_DISMISS)
        ) {
            Text(stringResource(R.string.library_import_dismiss), color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun importSummary(report: ImportReport): String {
    val added = report.importedCount
    val failed = report.failures.size

    return when {
        failed == 0 -> stringResource(R.string.library_import_summary_added, added)
        added == 0 -> stringResource(R.string.library_import_summary_none, failed)
        else -> stringResource(R.string.library_import_summary_mixed, added, failed)
    }
}

@Composable
private fun EmptyScene(onAddBooks: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .widthIn(max = MessageWidth)
                .testTag(LibraryTestTags.EMPTY),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.library_home_empty_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.library_home_empty_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            Button(

                shape = MaterialTheme.shapes.small,
                onClick = onAddBooks,
                modifier = Modifier.heightIn(min = FoliumSpacing.touchTarget).testTag(LibraryTestTags.EMPTY_ADD)
            ) {
                Text(stringResource(R.string.library_add_books))
            }
        }
    }
}

@Composable
private fun BookList(
    entries: List<ShelfEntry>,
    thumbnails: Map<BookId, Bitmap?>,
    enabled: Boolean,
    widthClass: FoliumWidthClass,
    selectedBookId: BookId?,
    onOpenBook: (BookId) -> Unit,
    onShowDetail: (BookId) -> Unit,
    onRemoveRequested: (ShelfEntry) -> Unit,
    sheets: List<SheetSummary> = emptyList(),
    sheetThumbnails: Map<SheetId, Bitmap?> = emptyMap(),
    unreadableSheetCount: Int = 0,
    onSheetOpen: (SheetId) -> Unit = {},
    onSheetDeleteRequested: (SheetSummary) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // The list view has no filter chips or search-narrowed count of its own, so every sheet shows,
    // in the same order the grid would settle on with nothing narrowing it.
    val visible = remember(sheets, entries) {
        visibleSheets(sheets, ShelfFilter.ALL, query = null, shelfBooks = entries.mapTo(HashSet()) { it.book.id })
    }

    // No gap between rows: each one carries its own leading hairline, the system's own "Sin tarjeta
    // ni fondo" rule, so an extra gap here would read as a second, blank separator alongside it.
    LazyColumn(
        modifier = modifier.testTag(LibraryTestTags.BOOKS),
        contentPadding = PaddingValues(start = widthClass.margin, end = widthClass.margin, top = 12.dp, bottom = 32.dp)
    ) {
        items(entries, key = { it.book.id.value }) { entry ->
            BookRow(
                entry = entry,
                thumbnail = thumbnails[entry.book.id],
                enabled = enabled,
                isSelected = entry.book.id == selectedBookId,
                onOpen = { onOpenBook(entry.book.id) },
                onShowDetail = { onShowDetail(entry.book.id) },
                onRemoveRequested = { onRemoveRequested(entry) }
            )
        }

        items(visible, key = { "sheet-${it.id.value}" }) { sheet ->
            SheetRow(
                sheet = sheet,
                thumbnail = sheetThumbnails[sheet.id],
                enabled = enabled,
                onOpen = { onSheetOpen(sheet.id) },
                onDeleteRequested = { onSheetDeleteRequested(sheet) }
            )
        }

        if (unreadableSheetCount > 0) {
            item(key = "sheets-unreadable") { UnreadableSheetsRow(unreadableSheetCount) }
        }
    }
}

/**
 * The same shelf weighted for scanning rather than reading: covers at their largest, the text under
 * each one reduced to what tells two books apart. Columns are chosen by width rather than counted,
 * so a phone shows two and a wider screen simply shows more of the same cell.
 */
@Composable
private fun BookGrid(
    entries: List<ShelfEntry>,
    thumbnails: Map<BookId, Bitmap?>,
    enabled: Boolean,
    filter: ShelfFilter,
    query: String?,
    widthClass: FoliumWidthClass,
    selectedBookId: BookId?,
    onFilterChange: (ShelfFilter) -> Unit,
    onOpenBook: (BookId) -> Unit,
    onShowDetail: (BookId) -> Unit,
    onRemoveRequested: (ShelfEntry) -> Unit,
    sheets: List<SheetSummary> = emptyList(),
    sheetThumbnails: Map<SheetId, Bitmap?> = emptyMap(),
    unreadableSheetCount: Int = 0,
    onSheetOpen: (SheetId) -> Unit = {},
    onSheetDeleteRequested: (SheetSummary) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val (current, shelf) = remember(entries, filter, query, widthClass) {
        // On a two-pane layout the right pane already gives a book the room the hero would: showing
        // both puts the same book on screen twice and costs the shelf its first row.
        partitionShelf(
            entries = entries,
            filter = filter,
            query = query,
            liftCurrent = !widthClass.showsTwoPanes
        )
    }

    val visibleSheetsInGrid = remember(sheets, entries, filter, query) {
        visibleSheets(sheets, filter, query, shelfBooks = entries.mapTo(HashSet()) { it.book.id })
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = GridCellMinWidth * widthClass.coverSpan),
        modifier = modifier.testTag(LibraryTestTags.BOOKS_GRID),
        contentPadding = PaddingValues(
            start = widthClass.margin,
            end = widthClass.margin,
            // The head was the one edge left at zero, so whatever came first — the filters on a
            // shelf with nothing under way, the cover on one with — sat against the header rule.
            top = FoliumSpacing.m,
            bottom = FoliumSpacing.xxl
        ),
        verticalArrangement = Arrangement.spacedBy(widthClass.gutter),
        horizontalArrangement = Arrangement.spacedBy(widthClass.gutter)
    ) {
        current?.let { entry ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                ContinueReading(
                    entry = entry,
                    thumbnail = thumbnails[entry.book.id],
                    enabled = enabled,
                    gutter = widthClass.gutter,
                    coverWidth = GridCellMinWidth * widthClass.coverSpan,
                    onOpen = { onOpenBook(entry.book.id) },
                    onShowDetail = { onShowDetail(entry.book.id) }
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
            ShelfFilters(filter = filter, enabled = enabled, onFilterChange = onFilterChange)
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "section") {
            SectionRule(count = shelf.size + visibleSheetsInGrid.size)
        }

        items(shelf, key = { it.book.id.value }) { entry ->
            BookCell(
                entry = entry,
                thumbnail = thumbnails[entry.book.id],
                enabled = enabled,
                isSelected = entry.book.id == selectedBookId,
                onOpen = { onOpenBook(entry.book.id) },
                onShowDetail = { onShowDetail(entry.book.id) },
                onRemoveRequested = { onRemoveRequested(entry) }
            )
        }

        items(visibleSheetsInGrid, key = { "sheet-${it.id.value}" }) { sheet ->
            SheetCell(
                sheet = sheet,
                thumbnail = sheetThumbnails[sheet.id],
                enabled = enabled,
                onOpen = { onSheetOpen(sheet.id) },
                onDeleteRequested = { onSheetDeleteRequested(sheet) }
            )
        }

        if (unreadableSheetCount > 0) {
            item(key = "sheets-unreadable") {
                UnreadableSheetsCell(unreadableSheetCount)
            }
        }
    }
}

/**
 * The book the reader is furthest into, given the width of two cover columns and a name.
 *
 * A library's usual next action is to carry on with the one book already open, and every design
 * that buries it behind a wall of identical covers spends a scan on something the app already
 * knows. It is lifted out of the shelf below rather than repeated in it.
 */
@Composable
private fun ContinueReading(
    entry: ShelfEntry,
    thumbnail: Bitmap?,
    enabled: Boolean,
    gutter: Dp,
    coverWidth: Dp,
    onOpen: () -> Unit,
    onShowDetail: () -> Unit
) {
    val title = entry.book.title
    val openLabel = stringResource(R.string.library_open_book, title)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { onClick(label = openLabel, action = null) }
            .clickable(enabled = enabled, onClick = onOpen)
            .testTag(LibraryTestTags.CONTINUE)
    ) {
        Box(Modifier.width(coverWidth)) {
            BookCover(
                thumbnail = thumbnail,
                imageTag = LibraryTestTags.bookThumbnail(entry.book.id),
                aspectRatio = ContinueReadingCoverAspectRatio
            )

            CoverEdgeProgress(
                fraction = entry.fraction,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .testTag(LibraryTestTags.bookProgress(entry.book.id))
            )
        }

        Spacer(Modifier.width(gutter))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Bottom) {
            Text(
                text = stringResource(R.string.library_continue_label).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary
            )

            Spacer(Modifier.height(FoliumSpacing.xxs))

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable(enabled = enabled, onClick = onShowDetail)
                    .testTag(LibraryTestTags.bookDetail(entry.book.id))
            )

            Spacer(Modifier.height(FoliumSpacing.xxs))

            entry.book.author?.let { author ->
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = stringResource(
                    R.string.library_book_progress,
                    entry.displayPage,
                    entry.pageCount,
                    (entry.fraction * 100).roundToInt()
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(FoliumSpacing.s))

            Button(
                onClick = onOpen,
                enabled = enabled,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().heightIn(min = FoliumSpacing.touchTarget)
            ) {
                Text(stringResource(R.string.library_continue))
            }
        }
    }
}

/** Which slice of the shelf is on screen. Filled in ink when active: the accent means progress. */
@Composable
private fun ShelfFilters(filter: ShelfFilter, enabled: Boolean, onFilterChange: (ShelfFilter) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FoliumSpacing.xs)
    ) {
        ShelfFilter.entries.forEach { candidate ->
            val selected = candidate == filter
            Text(
                text = stringResource(candidate.label).uppercase(),
                style = FoliumType.CaptionEmphasis,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .then(
                        if (selected) {
                            Modifier.background(MaterialTheme.colorScheme.primary)
                        } else {
                            Modifier.foliumBorder(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        }
                    )
                    .clickable(enabled = enabled) { onFilterChange(candidate) }
                    .heightIn(min = FoliumSpacing.touchTarget)
                    .wrapContentHeight()
                    .padding(horizontal = FoliumSpacing.m)
                    .testTag(candidate.tag)
            )
        }
    }
}

/** A rule, a count and the sort. The rule is what separates sections; nothing is boxed. */
@Composable
private fun SectionRule(count: Int) {
    Column(Modifier.fillMaxWidth()) {
        FoliumDivider.Horizontal(thickness = 1.dp, color = MaterialTheme.colorScheme.onSurface)

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = FoliumSpacing.s),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.library_section_shelf, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.library_section_recent).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A cover with its title under it and nothing else.
 *
 * The progress lives on the cover's bottom edge rather than as a bar below it, which is what lets
 * the cell be exactly two things — an image and a title — and lets a row of them read as a shelf.
 * The position line the cell used to carry said in words what the edge already says, and cost a
 * third text measure per cell on every frame of a fling.
 *
 * A tap opens the book — the whole cell, because a cell that opened one thing from its image and
 * another from its title splits a single object into two invisible halves. Everything else a reader
 * can do to a book lives behind a long press, where a menu names each one. That is also what makes
 * removing safe to reach: it is a named line in a list rather than the outcome of holding the wrong
 * thing, and it still asks before it does anything.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCell(
    entry: ShelfEntry,
    thumbnail: Bitmap?,
    enabled: Boolean,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onShowDetail: () -> Unit,
    onRemoveRequested: () -> Unit
) {
    val started = entry.pageIndex > 0
    val accent = if (started) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    val title = entry.book.title
    val openLabel = stringResource(R.string.library_open_book, title)
    val actionsLabel = stringResource(R.string.library_book_actions, title)
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                selected = isSelected
                onClick(label = openLabel, action = null)
                onLongClick(label = actionsLabel, action = null)
            }
            .combinedClickable(
                enabled = enabled,
                onClick = onOpen,
                onLongClick = { menuOpen = true }
            )
            .testTag(LibraryTestTags.gridBook(entry.book.id))
    ) {
        Box(Modifier.fillMaxWidth().selectionMarker(isSelected)) {
            BookCover(thumbnail = thumbnail, imageTag = LibraryTestTags.bookThumbnail(entry.book.id))

            CoverEdgeProgress(
                fraction = entry.fraction,
                color = accent,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .testTag(LibraryTestTags.bookProgress(entry.book.id))
            )
        }

        Spacer(Modifier.height(FoliumSpacing.xs))

        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        BookActionsMenu(
            expanded = menuOpen,
            entry = entry,
            onDismiss = { menuOpen = false },
            onShowDetail = { menuOpen = false; onShowDetail() },
            onRemoveRequested = { menuOpen = false; onRemoveRequested() }
        )
    }
}

/**
 * Everything a reader can do to a book without opening it, each named.
 *
 * The system draws this panel's own rows at 44dp with 14dp of side padding and a 1px line between
 * one row and the next — never on the first row, which the panel's own border already closes off —
 * and sets their text a size below the field and button text around them, unbolded.
 */
@Composable
private fun BookActionsMenu(
    expanded: Boolean,
    entry: ShelfEntry,
    onDismiss: () -> Unit,
    onShowDetail: () -> Unit,
    onRemoveRequested: () -> Unit
) {
    FoliumMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(LibraryTestTags.bookMenu(entry.book.id))
    ) {
        MenuActionItem(
            text = stringResource(R.string.library_book_open_detail),
            onClick = onShowDetail,
            testTag = LibraryTestTags.bookDetail(entry.book.id)
        )

        FoliumDivider.Horizontal(color = MaterialTheme.colorScheme.outlineVariant)

        MenuActionItem(
            text = stringResource(R.string.library_book_remove),
            color = MaterialTheme.colorScheme.error,
            onClick = onRemoveRequested,
            testTag = LibraryTestTags.removeBook(entry.book.id)
        )
    }
}

@Composable
internal fun MenuActionItem(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    color: Color = Color.Unspecified
) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                style = FoliumType.BodyMid,
                color = color
            )
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = MenuItemHorizontalPadding),
        modifier = Modifier.heightIn(min = FoliumSpacing.touchTarget).testTag(testTag)
    )
}

/**
 * The read part of the cover's bottom edge. Drawn rather than composed for the same reason the
 * shelf's bar is, and square for the same reason everything else is.
 */
@Composable
private fun CoverEdgeProgress(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .height(CoverEdgeThickness)
            .drawBehind {
                val read = fraction.coerceIn(0f, 1f) * size.width
                if (read > 0f) drawRect(color = color, size = Size(read, size.height))
            }
    )
}

/**
 * The grid's hero: the page shape a portrait document actually has, cropped to it, so a wall of
 * covers lines up. The system draws a cover as a flat, unbordered rectangle; a book whose thumbnail
 * is missing or would not decode keeps the same field-toned slot rather than collapsing the cell.
 *
 * [aspectRatio] defaults to the system's own cover ratio ([FoliumGrid.COVER_ASPECT]), which the
 * grid cell, the detail hero and the two-pane hero all draw at; the continue-reading hero is the
 * one caller that passes its own [ContinueReadingCoverAspectRatio] instead.
 */
@Composable
internal fun BookCover(thumbnail: Bitmap?, imageTag: String, aspectRatio: Float = FoliumGrid.COVER_ASPECT) {
    val frame = Modifier
        .fillMaxWidth()
        .aspectRatio(aspectRatio)
        .clip(MaterialTheme.shapes.medium)
        .background(coverBackgroundColor(MaterialTheme.colorScheme))

    if (thumbnail == null) {
        Box(frame)
    } else {
        Image(
            bitmap = remember(thumbnail) { thumbnail.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = frame.testTag(imageTag)
        )
    }
}

/**
 * A book on the shelf's dense list, without a card or a fill: the system separates a row from the
 * one below it with a 1px line, the same way it separates a section from its label. A book already
 * begun takes the accent on its bar, one still at its first page stays neutral, so the shelf shows
 * what is under way without ranking it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    entry: ShelfEntry,
    thumbnail: Bitmap?,
    enabled: Boolean,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onShowDetail: () -> Unit,
    onRemoveRequested: () -> Unit
) {
    val started = entry.pageIndex > 0
    val accent = if (started) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    val title = entry.book.title
    val openLabel = stringResource(R.string.library_open_book, title)
    val actionsLabel = stringResource(R.string.library_book_actions, title)
    var menuOpen by remember { mutableStateOf(false) }
    val progressText = stringResource(
        R.string.library_book_progress,
        entry.displayPage,
        entry.pageCount,
        (entry.fraction * 100).roundToInt()
    )

    FoliumDivider.Horizontal(thickness = 1.dp, color = rowDividerColor(MaterialTheme.colorScheme))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .semantics {
                selected = isSelected
                onClick(label = openLabel, action = null)
                onLongClick(label = actionsLabel, action = null)
            }
            .combinedClickable(
                enabled = enabled,
                onClick = onOpen,
                onLongClick = { menuOpen = true }
            )
            .testTag(LibraryTestTags.book(entry.book.id))
            .padding(start = 14.dp, end = 4.dp, top = 14.dp, bottom = 14.dp)
    ) {
        Box(Modifier.selectionMarker(isSelected)) {
            BookThumbnail(thumbnail = thumbnail, imageTag = LibraryTestTags.bookThumbnail(entry.book.id))
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            if (entry.book.titleDeclared == false) {
                Text(
                    text = stringResource(R.string.library_book_untitled),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(LibraryTestTags.untitled(entry.book.id))
                )

                Spacer(Modifier.height(4.dp))
            }

            Text(
                text = entry.book.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = progressText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))

            ProgressBar(
                fraction = entry.fraction,
                color = accent,
                modifier = Modifier.testTag(LibraryTestTags.bookProgress(entry.book.id))
            )
        }

        BookActionsMenu(
            expanded = menuOpen,
            entry = entry,
            onDismiss = { menuOpen = false },
            onShowDetail = { menuOpen = false; onShowDetail() },
            onRemoveRequested = { menuOpen = false; onRemoveRequested() }
        )
    }
}

/**
 * The bar is drawn rather than composed: a determinate `LinearProgressIndicator` would add a layout
 * node, a clipping layer and a progress semantics node to every shelf row, which measured as a fifth
 * of the shelf's per-frame cost while a fling is running. What it announced is not lost — the row
 * carries "Page X of Y · Z%" as text, which reads out with the row's own label.
 */
@Composable
private fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant

    Spacer(
        modifier
            .fillMaxWidth()
            .height(ProgressBarThickness)
            .drawBehind { drawProgressBar(fraction.coerceIn(0f, 1f), color, trackColor) }
    )
}

/**
 * Track and filled part as two abutting rectangles. They were round-capped strokes, which needed the
 * track to start a bar-thickness late so the caps met instead of overlapping, and which drew nothing
 * at all on a bar narrower than it was thick. Square corners remove both the offset and that guard.
 */
private fun DrawScope.drawProgressBar(fraction: Float, color: Color, trackColor: Color) {
    if (size.width <= 0f || size.height <= 0f) return

    drawBarSegment(fraction, 1f, trackColor)
    drawBarSegment(0f, fraction, color)
}

private fun DrawScope.drawBarSegment(startFraction: Float, endFraction: Float, color: Color) {
    if (endFraction <= startFraction) return

    drawRect(
        color = color,
        topLeft = Offset(startFraction * size.width, 0f),
        size = Size((endFraction - startFraction) * size.width, size.height)
    )
}

/**
 * A field-toned page stands in when the thumbnail is missing or would not decode: a book with no
 * cover still has to occupy the same slot, or the list loses its rhythm wherever a render failed.
 */
@Composable
private fun BookThumbnail(thumbnail: Bitmap?, imageTag: String) {
    val frame = Modifier
        .size(width = ThumbnailWidth, height = ThumbnailHeight)
        .clip(MaterialTheme.shapes.small)
        .background(coverBackgroundColor(MaterialTheme.colorScheme))

    if (thumbnail == null) {
        Box(frame)
    } else {
        Image(
            bitmap = remember(thumbnail) { thumbnail.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = frame.testTag(imageTag)
        )
    }
}

/**
 * A destructive action gets one gate and no undo, which is proportional for a copy of a file the
 * reader still owns wherever they added it from — and the dialog says exactly that.
 *
 * Handwriting on the book's pages lives with the book and goes with it, so the dialog says so
 * whenever [prompt] counts any inked page.
 *
 * The book's sheets are the reader's own work rather than a copy, so they are kept by default, on
 * the shelf. Deleting them too is a box to tick, unticked each time the dialog opens, and offered
 * only when [prompt] counts any.
 */
@Composable
private fun RemoveConfirmDialog(
    entry: ShelfEntry,
    prompt: RemoveBookPrompt,
    onDismiss: () -> Unit,
    onConfirm: (deleteSheets: Boolean) -> Unit
) {
    var deleteSheets by remember(entry.book.id) { mutableStateOf(false) }

    FoliumDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(LibraryTestTags.REMOVE_CONFIRM),
        title = { Text(stringResource(R.string.library_remove_confirm_title, entry.book.title)) },
        text = {
            Column {
                Text(stringResource(R.string.library_remove_confirm_body))

                if (prompt.mentionsPageInk) {
                    Spacer(Modifier.height(FoliumSpacing.s))

                    Text(
                        text = pluralStringResource(R.plurals.library_remove_page_ink, prompt.inkedPageCount, prompt.inkedPageCount),
                        modifier = Modifier.testTag(LibraryTestTags.REMOVE_PAGE_INK)
                    )
                }

                if (prompt.mentionsUncountedPageInk) {
                    Spacer(Modifier.height(FoliumSpacing.s))

                    Text(
                        text = stringResource(R.string.library_remove_page_ink_uncounted),
                        modifier = Modifier.testTag(LibraryTestTags.REMOVE_PAGE_INK)
                    )
                }

                if (prompt.countingPageInk) {
                    Spacer(Modifier.height(FoliumSpacing.s))

                    Text(
                        text = stringResource(R.string.library_remove_page_ink_counting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (prompt.offersSheetDeletion) {
                    Spacer(Modifier.height(FoliumSpacing.s))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = FoliumSpacing.touchTarget)
                            .toggleable(value = deleteSheets, role = Role.Checkbox, onValueChange = { deleteSheets = it })
                            .testTag(LibraryTestTags.REMOVE_DELETE_SHEETS),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = deleteSheets, onCheckedChange = null)

                        Spacer(Modifier.width(FoliumSpacing.xs))

                        Text(pluralStringResource(R.plurals.library_remove_delete_sheets, prompt.sheetCount, prompt.sheetCount))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteSheets) }, enabled = prompt.canConfirm, shape = MaterialTheme.shapes.small) {
                Text(
                    text = stringResource(R.string.library_remove_confirm_action),
                    color = if (prompt.canConfirm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.38f)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = MaterialTheme.shapes.small) {
                Text(stringResource(R.string.library_remove_cancel))
            }
        }
    )
}
