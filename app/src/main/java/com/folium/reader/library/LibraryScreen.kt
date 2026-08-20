package com.folium.reader.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folium.reader.R
import com.folium.reader.ui.FoliumSpacing
import com.folium.reader.ui.FoliumWidthClass
import com.folium.reader.ui.FoliumGrid
import com.folium.reader.ui.FoliumDialog
import com.folium.reader.ui.FoliumMenu
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
    fun bookDetail(id: BookId): String = "library-book-detail/${id.value}"
    fun bookMenu(id: BookId): String = "library-book-menu/${id.value}"
}

private val MessageWidth = 480.dp
private val TouchTarget = 48.dp
private val ThumbnailWidth = 56.dp
internal val ThumbnailHeight = 76.dp
private val RowMinHeight = 96.dp
private val ProgressBarThickness = 4.dp

/**
 * Columns are derived from the system's cover floor rather than a size of their own. The design
 * asks for four columns, which a 412dp phone gets exactly; a 360dp one gets three, because four
 * would put the cover at 71dp and a cover stops being recognizable below eighty.
 */
private val GridCellMinWidth = FoliumGrid.minCover
private const val CoverAspectRatio = 3f / 4f
private val CoverEdgeThickness = 4.dp

/** Eight of twelve modules to the shelf, four to the book: the split the design draws. */
private const val SHELF_PANE_WEIGHT = 8f
private const val DETAIL_PANE_WEIGHT = 4f

/**
 * The library home, and the surface the app opens on.
 *
 * Stateless by design: every state it can render arrives as a [LibraryHomeState] and every
 * thumbnail in [thumbnails] rather than being decoded or looked up here, which is what lets each
 * state be exercised directly. The one thing it owns is which book a removal is currently asking
 * about, which is transient UI rather than library state.
 */
@Composable
fun LibraryScreen(
    state: LibraryHomeState,
    thumbnails: Map<BookId, Bitmap?>,
    viewMode: LibraryViewMode,
    appearanceMode: AppearanceMode,
    onAddBooks: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    onShowDetail: (BookId) -> Unit,
    onRemoveBook: (BookId) -> Unit,
    sidePane: (@Composable () -> Unit)? = null,
    onDismissReport: () -> Unit,
    onViewModeChange: (LibraryViewMode) -> Unit,
    onAppearanceModeChange: (AppearanceMode) -> Unit,
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
                    onOpenBook = onOpenBook,
                    onShowDetail = onShowDetail,
                    onRemoveBook = onRemoveBook,
                    sidePane = sidePane,
                    onDismissReport = onDismissReport,
                    onViewModeChange = onViewModeChange,
                    onAppearanceModeChange = onAppearanceModeChange
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
    onOpenBook: (BookId) -> Unit,
    onShowDetail: (BookId) -> Unit,
    onRemoveBook: (BookId) -> Unit,
    sidePane: (@Composable () -> Unit)? = null,
    onDismissReport: () -> Unit,
    onViewModeChange: (LibraryViewMode) -> Unit,
    onAppearanceModeChange: (AppearanceMode) -> Unit
) {
    var pendingRemoval by remember { mutableStateOf<ShelfEntry?>(null) }
    var filter by rememberSaveable { mutableStateOf(ShelfFilter.ALL) }
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    val importing = state.importing

    Column(Modifier.fillMaxSize()) {
        LibraryHeader(
            query = query,
            onQueryChange = { query = it },
            importing = importing != null,
            viewMode = viewMode,
            appearanceMode = appearanceMode,
            onAddBooks = onAddBooks,
            onSearch = { query = "" },
            onViewModeChange = onViewModeChange,
            onAppearanceModeChange = onAppearanceModeChange
        )

        importing?.let { ImportingStrip(it) }

        state.report?.let { ImportReportBanner(it, onDismissReport) }

        when {
            state.entries.isEmpty() -> EmptyScene(onAddBooks)

            viewMode == LibraryViewMode.GRID -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val widthClass = FoliumWidthClass.of(maxWidth)
                val grid = @Composable { modifier: Modifier ->
                    BookGrid(
                        entries = state.entries,
                        thumbnails = thumbnails,
                        enabled = importing == null,
                        filter = filter,
                        query = query,
                        widthClass = widthClass,
                        onFilterChange = { filter = it },
                        onOpenBook = onOpenBook,
                        onShowDetail = onShowDetail,
                        onRemoveRequested = { pendingRemoval = it },
                        modifier = modifier
                    )
                }

                if (widthClass.showsTwoPanes && sidePane != null) {
                    Row(Modifier.fillMaxSize()) {
                        grid(Modifier.weight(SHELF_PANE_WEIGHT))
                        VerticalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Box(Modifier.weight(DETAIL_PANE_WEIGHT).testTag(LibraryTestTags.DETAIL_PANE)) {
                            sidePane()
                        }
                    }
                } else {
                    grid(Modifier.fillMaxSize())
                }
            }

            else -> BookList(
                entries = state.entries,
                thumbnails = thumbnails,
                enabled = importing == null,
                onOpenBook = onOpenBook,
                onRemoveRequested = { pendingRemoval = it }
            )
        }
    }

    pendingRemoval?.let { entry ->
        RemoveConfirmDialog(
            entry = entry,
            onDismiss = { pendingRemoval = null },
            onConfirm = {
                pendingRemoval = null
                onRemoveBook(entry.book.id)
            }
        )
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
    onSearch: () -> Unit,
    onViewModeChange: (LibraryViewMode) -> Unit,
    onAppearanceModeChange: (AppearanceMode) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = FoliumGrid.compactMargin)) {
        if (query != null) {
            LibrarySearchField(query = query, onQueryChange = onQueryChange)
            Spacer(Modifier.height(FoliumSpacing.xs))
            HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface)
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = FoliumSpacing.xl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.library_wordmark),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f)
            )

            HeaderIcon(
                onClick = onSearch,
                enabled = !importing,
                description = stringResource(R.string.library_search),
                testTag = LibraryTestTags.SEARCH,
                filled = false
            ) { tint ->
                drawCircle(color = tint, radius = 5.8f.dp.toPx(), center = center.copy(x = center.x - 1.4f.dp.toPx(), y = center.y - 1.4f.dp.toPx()), style = Stroke(width = 1.6f.dp.toPx()))
                drawLine(
                    color = tint,
                    start = center.copy(x = center.x + 2.6f.dp.toPx(), y = center.y + 2.6f.dp.toPx()),
                    end = center.copy(x = center.x + 7.5f.dp.toPx(), y = center.y + 7.5f.dp.toPx()),
                    strokeWidth = 1.6f.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            HeaderIcon(
                onClick = onAddBooks,
                enabled = !importing,
                description = stringResource(R.string.library_add_books),
                testTag = LibraryTestTags.ADD,
                filled = true
            ) { tint ->
                val arm = 6f.dp.toPx()
                drawLine(tint, center.copy(y = center.y - arm), center.copy(y = center.y + arm), 1.6f.dp.toPx(), StrokeCap.Round)
                drawLine(tint, center.copy(x = center.x - arm), center.copy(x = center.x + arm), 1.6f.dp.toPx(), StrokeCap.Round)
            }

            LibraryOptionsMenu(
                viewMode = viewMode,
                appearanceMode = appearanceMode,
                enabled = !importing,
                onViewModeChange = onViewModeChange,
                onAppearanceModeChange = onAppearanceModeChange
            )
        }

        Spacer(Modifier.height(FoliumSpacing.xs))

        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Filters the shelf by title while it is open, and gives the shelf back untouched when closed. */
@Composable
private fun LibrarySearchField(query: String, onQueryChange: (String?) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = FoliumSpacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
            modifier = Modifier
                .weight(1f)
                .height(FoliumSpacing.touchTarget)
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .padding(horizontal = FoliumSpacing.s)
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
        TextButton(
            shape = MaterialTheme.shapes.small,
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier
                .sizeIn(minWidth = TouchTarget, minHeight = TouchTarget)
                .semantics { contentDescription = description }
                .testTag(LibraryTestTags.VIEW_MENU),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("⋮", style = MaterialTheme.typography.titleLarge)
        }

        FoliumMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            MenuSectionLabel(R.string.library_layout)
            ViewModeItem(R.string.library_view_list, LibraryTestTags.VIEW_LIST, LibraryViewMode.LIST, viewMode) {
                open = false
                onViewModeChange(it)
            }
            ViewModeItem(R.string.library_view_grid, LibraryTestTags.VIEW_GRID, LibraryViewMode.GRID, viewMode) {
                open = false
                onViewModeChange(it)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
            AppearanceModeItem(
                R.string.library_appearance_light,
                LibraryTestTags.APPEARANCE_LIGHT,
                AppearanceMode.LIGHT,
                appearanceMode
            ) {
                open = false
                onAppearanceModeChange(it)
            }
            AppearanceModeItem(
                R.string.library_appearance_dark,
                LibraryTestTags.APPEARANCE_DARK,
                AppearanceMode.DARK,
                appearanceMode
            ) {
                open = false
                onAppearanceModeChange(it)
            }
            AppearanceModeItem(
                R.string.library_appearance_e_ink_light,
                LibraryTestTags.APPEARANCE_E_INK_LIGHT,
                AppearanceMode.E_INK_LIGHT,
                appearanceMode
            ) {
                open = false
                onAppearanceModeChange(it)
            }
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

@Composable
private fun MenuSectionLabel(label: Int) {
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() }
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
        text = { Text(stringResource(label), style = MaterialTheme.typography.bodyMedium) },
        trailingIcon = if (mode != active) null else {
            { Text("✓", style = MaterialTheme.typography.bodyMedium) }
        },
        onClick = { onChosen(mode) },
        modifier = Modifier
            .sizeIn(minHeight = TouchTarget)
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
        text = { Text(stringResource(label), style = MaterialTheme.typography.bodyMedium) },
        trailingIcon = if (mode != active) null else {
            { Text("✓", style = MaterialTheme.typography.bodyMedium) }
        },
        onClick = { onChosen(mode) },
        modifier = Modifier
            .sizeIn(minHeight = TouchTarget)
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
                .heightIn(min = TouchTarget)
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
                modifier = Modifier.heightIn(min = TouchTarget).testTag(LibraryTestTags.EMPTY_ADD)
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
    onOpenBook: (BookId) -> Unit,
    onRemoveRequested: (ShelfEntry) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(LibraryTestTags.BOOKS),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(entries, key = { it.book.id.value }) { entry ->
            BookRow(
                entry = entry,
                thumbnail = thumbnails[entry.book.id],
                enabled = enabled,
                onOpen = { onOpenBook(entry.book.id) },
                onRemoveRequested = { onRemoveRequested(entry) }
            )
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
    onFilterChange: (ShelfFilter) -> Unit,
    onOpenBook: (BookId) -> Unit,
    onShowDetail: (BookId) -> Unit,
    onRemoveRequested: (ShelfEntry) -> Unit,
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

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = GridCellMinWidth * widthClass.coverSpan),
        modifier = modifier.testTag(LibraryTestTags.BOOKS_GRID),
        contentPadding = PaddingValues(
            start = widthClass.margin,
            end = widthClass.margin,
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
                    onOpen = { onOpenBook(entry.book.id) },
                    onShowDetail = { onShowDetail(entry.book.id) }
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
            ShelfFilters(filter = filter, enabled = enabled, onFilterChange = onFilterChange)
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "section") {
            SectionRule(count = shelf.size)
        }

        items(shelf, key = { it.book.id.value }) { entry ->
            BookCell(
                entry = entry,
                thumbnail = thumbnails[entry.book.id],
                enabled = enabled,
                onOpen = { onOpenBook(entry.book.id) },
                onShowDetail = { onShowDetail(entry.book.id) },
                onRemoveRequested = { onRemoveRequested(entry) }
            )
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
    onOpen: () -> Unit,
    onShowDetail: () -> Unit
) {
    val title = entry.book.title
    val openLabel = stringResource(R.string.library_open_book, title)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FoliumSpacing.m, bottom = FoliumSpacing.l)
            .semantics { onClick(label = openLabel, action = null) }
            .clickable(enabled = enabled, onClick = onOpen)
            .testTag(LibraryTestTags.CONTINUE)
    ) {
        Box(Modifier.width(FoliumGrid.maxCover)) {
            BookCover(thumbnail = thumbnail, imageTag = LibraryTestTags.bookThumbnail(entry.book.id))

            CoverEdgeProgress(
                fraction = entry.fraction,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .testTag(LibraryTestTags.bookProgress(entry.book.id))
            )
        }

        Spacer(Modifier.width(FoliumGrid.compactGutter))

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
                Spacer(Modifier.height(FoliumSpacing.xxs))
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
                    entry.book.pageCount,
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
        modifier = Modifier.fillMaxWidth().padding(bottom = FoliumSpacing.m),
        horizontalArrangement = Arrangement.spacedBy(FoliumSpacing.xs)
    ) {
        ShelfFilter.entries.forEach { candidate ->
            val selected = candidate == filter
            Text(
                text = stringResource(candidate.label).uppercase(),
                style = MaterialTheme.typography.labelMedium,
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
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
    Column(Modifier.fillMaxWidth().padding(bottom = FoliumSpacing.s)) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.onSurface)

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
        Box(Modifier.fillMaxWidth()) {
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
            style = MaterialTheme.typography.labelSmall,
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

/** Everything a reader can do to a book without opening it, each named. */
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
        DropdownMenuItem(
            text = { Text(stringResource(R.string.library_book_open_detail)) },
            onClick = onShowDetail,
            modifier = Modifier.testTag(LibraryTestTags.bookDetail(entry.book.id))
        )
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(R.string.library_book_remove),
                    color = MaterialTheme.colorScheme.error
                )
            },
            onClick = onRemoveRequested,
            modifier = Modifier.testTag(LibraryTestTags.removeBook(entry.book.id))
        )
    }
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
 * covers lines up. A book whose thumbnail is missing or would not decode keeps the same outlined
 * slot rather than collapsing the cell.
 */
@Composable
internal fun BookCover(thumbnail: Bitmap?, imageTag: String) {
    val frame = Modifier
        .fillMaxWidth()
        .aspectRatio(CoverAspectRatio)
        .clip(MaterialTheme.shapes.medium)
        .background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)

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
 * Removal stays a visible affordance in the grid instead of becoming a long press: a gesture with
 * nothing on screen to announce it is not discoverable, and the destructive action is the last one
 * to hide. It sits on the cover's corner over a disc of its own so it stays legible whatever the
 * page underneath it looks like, and opens the same confirmation the rows do.
 */
/**
 * A book reads as one tonal block rather than a bordered box: the thumbnail carries recognition and
 * the bar under the title carries position. A book already begun takes the accent on its bar, one
 * still at its first page stays neutral, so the shelf shows what is under way without ranking it.
 */
@Composable
private fun BookRow(
    entry: ShelfEntry,
    thumbnail: Bitmap?,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRemoveRequested: () -> Unit
) {
    val started = entry.pageIndex > 0
    val accent = if (started) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    val title = entry.book.title
    val openLabel = stringResource(R.string.library_open_book, title)
    val progressText = stringResource(
        R.string.library_book_progress,
        entry.displayPage,
        entry.book.pageCount,
        (entry.fraction * 100).roundToInt()
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { onClick(label = openLabel, action = null) }
            .clickable(enabled = enabled, onClick = onOpen)
            .testTag(LibraryTestTags.book(entry.book.id))
            .padding(start = 14.dp, end = 4.dp, top = 14.dp, bottom = 14.dp)
    ) {
        BookThumbnail(thumbnail = thumbnail, imageTag = LibraryTestTags.bookThumbnail(entry.book.id))

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f).padding(top = 2.dp)) {
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

        RemoveButton(entry, onRemoveRequested)
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
 * An outlined empty page stands in when the thumbnail is missing or would not decode: a book with
 * no cover still has to occupy the same slot, or the list loses its rhythm wherever a render failed.
 */
@Composable
private fun BookThumbnail(thumbnail: Bitmap?, imageTag: String) {
    val frame = Modifier
        .size(width = ThumbnailWidth, height = ThumbnailHeight)
        .clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)

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

@Composable
private fun RemoveButton(entry: ShelfEntry, onClick: () -> Unit) {
    val context = LocalContext.current
    val title = entry.book.title

    TextButton(

        shape = MaterialTheme.shapes.small,
        onClick = onClick,
        modifier = Modifier
            .size(TouchTarget)
            .semantics { contentDescription = context.getString(R.string.library_remove_book, title) }
            .testTag(LibraryTestTags.removeBook(entry.book.id)),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(
            text = "×",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A destructive action gets one gate and no undo, which is proportional for a copy of a file the
 * reader still owns wherever they added it from — and the dialog says exactly that.
 */
@Composable
private fun RemoveConfirmDialog(entry: ShelfEntry, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    FoliumDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(LibraryTestTags.REMOVE_CONFIRM),
        title = { Text(stringResource(R.string.library_remove_confirm_title, entry.book.title)) },
        text = { Text(stringResource(R.string.library_remove_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, shape = MaterialTheme.shapes.small) {
                Text(
                    text = stringResource(R.string.library_remove_confirm_action),
                    color = MaterialTheme.colorScheme.error
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
