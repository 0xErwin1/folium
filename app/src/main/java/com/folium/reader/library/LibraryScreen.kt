package com.folium.reader.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folium.reader.R
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

    fun book(id: BookId): String = "library-book/${id.value}"
    fun gridBook(id: BookId): String = "library-book-grid/${id.value}"
    fun removeBook(id: BookId): String = "library-book-remove/${id.value}"
    fun bookThumbnail(id: BookId): String = "library-book-thumbnail/${id.value}"
    fun bookProgress(id: BookId): String = "library-book-progress/${id.value}"
}

private val MessageWidth = 480.dp
private val TouchTarget = 48.dp
private val ThumbnailWidth = 56.dp
internal val ThumbnailHeight = 76.dp
private val RowMinHeight = 96.dp
private val ProgressBarThickness = 4.dp

/**
 * The narrowest a cover may be before the grid drops a column. At 150dp a 360dp-wide phone holds
 * two columns and a tablet fills its width with as many as fit, so the same rule serves both
 * without asking the screen how large it is.
 */
private val GridCellMinWidth = 150.dp
private const val CoverAspectRatio = 3f / 4f
private val CoverRemoveGlyphSize = 26.dp

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
    onRemoveBook: (BookId) -> Unit,
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
                    onRemoveBook = onRemoveBook,
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
    onRemoveBook: (BookId) -> Unit,
    onDismissReport: () -> Unit,
    onViewModeChange: (LibraryViewMode) -> Unit,
    onAppearanceModeChange: (AppearanceMode) -> Unit
) {
    var pendingRemoval by remember { mutableStateOf<ShelfEntry?>(null) }
    val importing = state.importing

    Column(Modifier.fillMaxSize()) {
        LibraryHeader(
            bookCount = state.entries.size,
            importing = importing != null,
            viewMode = viewMode,
            appearanceMode = appearanceMode,
            onAddBooks = onAddBooks,
            onViewModeChange = onViewModeChange,
            onAppearanceModeChange = onAppearanceModeChange
        )

        importing?.let { ImportingStrip(it) }

        state.report?.let { ImportReportBanner(it, onDismissReport) }

        when {
            state.entries.isEmpty() -> EmptyScene(onAddBooks)

            viewMode == LibraryViewMode.GRID -> BookGrid(
                entries = state.entries,
                thumbnails = thumbnails,
                enabled = importing == null,
                onOpenBook = onOpenBook,
                onRemoveRequested = { pendingRemoval = it }
            )

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
 * Carries the app's identity rather than a bare screen title: the wordmark sets the tone once, and
 * the count under it says how large the shelf is without spending a row on it.
 */
@Composable
private fun LibraryHeader(
    bookCount: Int,
    importing: Boolean,
    viewMode: LibraryViewMode,
    appearanceMode: AppearanceMode,
    onAddBooks: () -> Unit,
    onViewModeChange: (LibraryViewMode) -> Unit,
    onAppearanceModeChange: (AppearanceMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 24.dp, bottom = 16.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.library_wordmark),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.tertiary
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.library_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            if (bookCount > 0) {
                Spacer(Modifier.height(4.dp))

                Text(
                    text = pluralStringResource(R.plurals.library_book_count, bookCount, bookCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        Button(
            onClick = onAddBooks,
            enabled = !importing,
            modifier = Modifier.heightIn(min = TouchTarget).testTag(LibraryTestTags.ADD)
        ) {
            Text(stringResource(R.string.library_add_books))
        }

        LibraryOptionsMenu(
            viewMode = viewMode,
            appearanceMode = appearanceMode,
            enabled = !importing,
            onViewModeChange = onViewModeChange,
            onAppearanceModeChange = onAppearanceModeChange
        )
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
        TextButton(
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

        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
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
    onOpenBook: (BookId) -> Unit,
    onRemoveRequested: (ShelfEntry) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = GridCellMinWidth),
        modifier = Modifier.fillMaxSize().testTag(LibraryTestTags.BOOKS_GRID),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(entries, key = { it.book.id.value }) { entry ->
            BookCell(
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
 * A cover with its title under it, the position line kept to one line and the same drawn bar the
 * rows use. The title is held at two lines whether it needs them or not, so every bar in a row of
 * cells sits at the same height and the grid reads as a shelf rather than a ragged mosaic.
 */
@Composable
private fun BookCell(
    entry: ShelfEntry,
    thumbnail: Bitmap?,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRemoveRequested: () -> Unit
) {
    val started = entry.pageIndex > 0
    val accent = if (started) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    val context = LocalContext.current
    val title = entry.book.title
    val progressText = stringResource(
        R.string.library_book_progress,
        entry.displayPage,
        entry.book.pageCount,
        (entry.fraction * 100).roundToInt()
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { onClick(label = context.getString(R.string.library_open_book, title), action = null) }
            .clickable(enabled = enabled, onClick = onOpen)
            .testTag(LibraryTestTags.gridBook(entry.book.id))
            .padding(10.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            BookCover(thumbnail = thumbnail, imageTag = LibraryTestTags.bookThumbnail(entry.book.id))

            CoverRemoveButton(
                entry = entry,
                onClick = onRemoveRequested,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = entry.book.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = progressText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(8.dp))

        ProgressBar(
            fraction = entry.fraction,
            color = accent,
            modifier = Modifier.testTag(LibraryTestTags.bookProgress(entry.book.id))
        )
    }
}

/**
 * The grid's hero: the page shape a portrait document actually has, cropped to it, so a wall of
 * covers lines up. A book whose thumbnail is missing or would not decode keeps the same outlined
 * slot rather than collapsing the cell.
 */
@Composable
private fun BookCover(thumbnail: Bitmap?, imageTag: String) {
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
@Composable
private fun CoverRemoveButton(entry: ShelfEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val title = entry.book.title

    TextButton(
        onClick = onClick,
        modifier = modifier
            .size(TouchTarget)
            .semantics { contentDescription = context.getString(R.string.library_remove_book, title) }
            .testTag(LibraryTestTags.removeBook(entry.book.id)),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .size(CoverRemoveGlyphSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "×",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

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
    val context = LocalContext.current
    val title = entry.book.title
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
            .semantics { onClick(label = context.getString(R.string.library_open_book, title), action = null) }
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
                style = MaterialTheme.typography.labelMedium,
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
 * Track and filled part as two round-capped strokes that meet: the filled part runs from the left
 * edge to [fraction], and the track picks up one bar-thickness later so the two caps abut instead of
 * overlapping. Both stay inside the bar's box, so the pill needs no clipping layer of its own.
 */
private fun DrawScope.drawProgressBar(fraction: Float, color: Color, trackColor: Color) {
    if (size.width <= size.height || size.height <= 0f) return

    val trackStart = fraction + minOf(fraction, size.height / size.width)

    if (trackStart <= 1f) drawBarSegment(trackStart, 1f, trackColor)
    drawBarSegment(0f, fraction, color)
}

private fun DrawScope.drawBarSegment(startFraction: Float, endFraction: Float, color: Color) {
    if (endFraction <= startFraction) return

    val capRadius = size.height / 2
    val drawable = capRadius..(size.width - capRadius)

    drawLine(
        color = color,
        start = Offset((startFraction * size.width).coerceIn(drawable), capRadius),
        end = Offset((endFraction * size.width).coerceIn(drawable), capRadius),
        strokeWidth = size.height,
        cap = StrokeCap.Round
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
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(LibraryTestTags.REMOVE_CONFIRM),
        title = { Text(stringResource(R.string.library_remove_confirm_title, entry.book.title)) },
        text = { Text(stringResource(R.string.library_remove_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.library_remove_confirm_action),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_remove_cancel)) }
        }
    )
}
