package com.folium.reader.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.folium.reader.R
import com.folium.reader.ui.FoliumDialog
import com.folium.reader.ui.FoliumWidthClass
import com.folium.reader.ui.foliumBorder
import com.folium.reader.core.pdf.OutlineRow
import kotlin.math.min

/** How far a nested outline row is allowed to keep indenting before the indent stops growing. */
private const val MAX_INDENT_DEPTH = 4

private val IndentStep = 16.dp
private val RowPadding = 16.dp
private val TouchTarget = 48.dp

private val GridPadding = 16.dp
private val GridGutter = 12.dp
private val CellBorder = 1.dp
private val CurrentPageBorder = 2.dp

/** A generic portrait shape a thumbnail is fit inside of, regardless of the document's own page aspect. */
private const val ThumbnailCellAspectRatio = 0.72f

/**
 * How many thumbnails the page grid lays out per row at each width tier — a plain column count
 * rather than [FoliumWidthClass.columns]' module spans, since a page thumbnail is a single small
 * cell with nothing to span: the library's cover grid stays legible by growing the cover at a fixed
 * column count, while this grid stays legible by growing the column count itself as the sheet
 * widens.
 */
internal fun pageThumbnailColumns(widthClass: FoliumWidthClass): Int = when (widthClass) {
    FoliumWidthClass.COMPACT -> 3
    FoliumWidthClass.MEDIUM -> 4
    FoliumWidthClass.EXPANDED -> 6
}

/**
 * The page indices worth having a thumbnail rendered for right now: the grid's own visible range,
 * widened by [prefetch] pages on either side so a small scroll lands on cells already rendering
 * rather than empty ones, and clamped into the document either way.
 */
internal fun wantedThumbnailPages(
    firstVisible: Int,
    lastVisible: Int,
    pageCount: Int,
    prefetch: Int = 0
): List<Int> {
    if (pageCount <= 0) return emptyList()

    val from = (firstVisible - prefetch).coerceIn(0, pageCount - 1)
    val to = (lastVisible + prefetch).coerceIn(0, pageCount - 1)
    if (from > to) return emptyList()

    return (from..to).toList()
}

/**
 * The page a typed entry names, as a zero-based index, or `null` when the entry names no page in
 * this document.
 *
 * Rejecting rather than clamping is deliberate: the entry field is the only place the reader states
 * a page, so silently turning `9999` into the last page would report success for something they did
 * not ask for. A rejected entry simply leaves the confirmation unavailable.
 */
internal fun jumpTargetPage(entry: String, pageCount: Int): Int? {
    val entered = entry.trim().toIntOrNull() ?: return null

    return if (entered in 1..pageCount) entered - 1 else null
}

/**
 * Keeps a typed entry to digits within the width of the document's largest page number. A numeric
 * keyboard is a hint rather than a guarantee — a paste or a hardware keyboard can put anything in
 * the field — so the entry is constrained where it is stored rather than where it is read.
 *
 * Leading zeros are stripped before the width cap is applied: capping by character count alone
 * would let a leading zero eat part of the budget, so "0500" on a 500-page book kept only "050"
 * (page 50) instead of the intended page 500.
 */
internal fun sanitizeJumpEntry(raw: String, pageCount: Int): String {
    val digits = raw.filter(Char::isDigit)
    val significant = digits.trimStart('0').ifEmpty { if (digits.isEmpty()) "" else "0" }

    return significant.take(pageCount.coerceAtLeast(1).toString().length)
}

/**
 * What an outline row shows as its title. An entry whose title the document left empty keeps its
 * place with a placeholder instead of being dropped, because dropping it would take its children —
 * which may well be resolvable — out of the contents with it.
 */
internal fun contentsRowTitle(title: String, placeholder: String): String =
    title.trim().ifEmpty { placeholder }

/**
 * The outline row that owns [currentPage]. Outline order is authoritative: unresolved rows are
 * ignored, and every navigable row at or before the current page replaces the previous candidate.
 * This makes the later row win when destinations are duplicated and also supports outlines whose
 * destinations are not sorted by page number.
 */
internal fun activeContentsRowIndex(rows: List<OutlineRow>, currentPage: Int): Int? {
    var activeIndex: Int? = null

    rows.forEachIndexed { index, row ->
        val pageIndex = row.pageIndex
        if (pageIndex != null && pageIndex <= currentPage) activeIndex = index
    }

    return activeIndex
}

internal data class ContentsTreeRow(
    val depth: Int,
    val ancestorContinuations: List<Boolean>,
    val isLastSibling: Boolean,
    val hasChildren: Boolean
)

/** Derives the capped visual tree topology in two linear passes over the rows. */
internal fun contentsTreeRows(
    rows: List<OutlineRow>,
    maxDepth: Int = MAX_INDENT_DEPTH
): List<ContentsTreeRow> {
    require(maxDepth >= 0)
    if (rows.isEmpty()) return emptyList()

    val depths = IntArray(rows.size) { min(rows[it].depth, maxDepth) }
    val isLastSibling = BooleanArray(rows.size) { true }
    val latestAtDepth = IntArray(maxDepth + 1) { -1 }

    depths.forEachIndexed { index, depth ->
        for (deeper in depth + 1..maxDepth) latestAtDepth[deeper] = -1

        val previousSibling = latestAtDepth[depth]
        if (previousSibling >= 0) isLastSibling[previousSibling] = false
        latestAtDepth[depth] = index
    }

    latestAtDepth.fill(-1)
    return rows.indices.map { index ->
        val depth = depths[index]
        for (deeper in depth + 1..maxDepth) latestAtDepth[deeper] = -1

        val continuations = List((depth - 1).coerceAtLeast(0)) { laneDepth ->
            val ancestorIndex = latestAtDepth[laneDepth + 1]
            ancestorIndex >= 0 && !isLastSibling[ancestorIndex]
        }
        val hasChildren = index + 1 < rows.size && depths[index + 1] > depth

        latestAtDepth[depth] = index
        ContentsTreeRow(depth, continuations, isLastSibling[index], hasChildren)
    }
}

/**
 * Asks for a page by number.
 *
 * The field starts empty with the current page as its hint: the reader opened this to go somewhere
 * else, so pre-filling where they already are would only be something to clear first. Confirmation
 * stays unavailable until the entry names a real page, which is what makes an out-of-range or
 * unparseable entry a dead end rather than a navigation.
 */
@Composable
internal fun JumpToPageDialog(
    pageCount: Int,
    currentPage: Int,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit
) {
    var entry by remember { mutableStateOf("") }
    val target = jumpTargetPage(entry, pageCount)

    FoliumDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ReaderTestTags.JUMP_DIALOG),
        title = { Text(stringResource(R.string.reader_jump_title)) },
        text = {
            OutlinedTextField(
                value = entry,
                onValueChange = { entry = sanitizeJumpEntry(it, pageCount) },
                label = { Text(stringResource(R.string.reader_jump_label, pageCount)) },
                placeholder = { Text((currentPage + 1).toString()) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag(ReaderTestTags.JUMP_INPUT)
            )
        },
        confirmButton = {
            TextButton(
                onClick = { target?.let(onJump) },
                enabled = target != null,
                modifier = Modifier.testTag(ReaderTestTags.JUMP_CONFIRM)
            ) {
                Text(stringResource(R.string.reader_jump_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_jump_cancel)) }
        }
    )
}

private enum class NavigationTab { CONTENTS, PAGES }

/**
 * Navigates the document either by its own table of contents or by looking at its pages, as a
 * full-screen surface.
 *
 * A contents list is as long as the document made it, and a page grid covers the whole document, so
 * this is given the whole screen rather than a dialog-sized window — a reader scrolling to chapter
 * forty, or to page four hundred, should not be doing it through a letterbox. It is a plain [Dialog]
 * rather than a bottom sheet because the sheet is still an opt-in experimental API at the pinned
 * Material version, which is not something to put on a core reading screen.
 *
 * The Contents tab is hidden, rather than shown with an empty-state explanation, when [rows] is
 * empty: a document with no outline still has every page for the Pages tab to show, so there is
 * nothing this sheet cannot do for it, and a tab that would only ever say "no contents" is the one
 * with nothing to add. [PAGES] becomes the sheet's only tab in that case, opened directly rather
 * than behind a one-item tab strip.
 */
@Composable
internal fun NavigationSheet(
    rows: List<OutlineRow>,
    pageCount: Int,
    currentPage: Int,
    thumbnails: ThumbnailGridState<BorrowedThumbnail>,
    onThumbnailsWanted: (List<Int>) -> Unit,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val hasContents = rows.isNotEmpty()
    var tab by remember(hasContents) { mutableStateOf(if (hasContents) NavigationTab.CONTENTS else NavigationTab.PAGES) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag(ReaderTestTags.CONTENTS_SHEET),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                NavigationHeader(hasContents, tab, onDismiss) { tab = it }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                when (tab) {
                    NavigationTab.CONTENTS -> ContentsList(rows, currentPage, onSelect)
                    NavigationTab.PAGES -> PagesGrid(pageCount, currentPage, thumbnails, onThumbnailsWanted, onSelect)
                }
            }
        }
    }
}

@Composable
private fun ContentsList(rows: List<OutlineRow>, currentPage: Int, onSelect: (Int) -> Unit) {
    val activeIndex = remember(rows, currentPage) { activeContentsRowIndex(rows, currentPage) }
    val treeRows = remember(rows) { contentsTreeRows(rows) }

    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(rows) { index, row ->
            ContentsRow(index, row, treeRows[index], index == activeIndex, onSelect)
        }
    }
}

@Composable
private fun NavigationHeader(
    hasContents: Boolean,
    tab: NavigationTab,
    onDismiss: () -> Unit,
    onTabSelected: (NavigationTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .padding(start = RowPadding, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (hasContents) {
            Row {
                NavigationTabButton(
                    label = stringResource(R.string.reader_contents),
                    selected = tab == NavigationTab.CONTENTS,
                    testTag = ReaderTestTags.CONTENTS_TAB,
                    onClick = { onTabSelected(NavigationTab.CONTENTS) }
                )
                NavigationTabButton(
                    label = stringResource(R.string.reader_pages),
                    selected = tab == NavigationTab.PAGES,
                    testTag = ReaderTestTags.PAGES_TAB,
                    onClick = { onTabSelected(NavigationTab.PAGES) }
                )
            }
        } else {
            Text(
                text = stringResource(R.string.reader_pages),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        TextButton(
            onClick = onDismiss,
            modifier = Modifier.heightIn(min = TouchTarget).testTag(ReaderTestTags.CONTENTS_CLOSE)
        ) {
            Text(stringResource(R.string.reader_contents_close))
        }
    }
}

@Composable
private fun NavigationTabButton(label: String, selected: Boolean, testTag: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = TouchTarget)
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .testTag(testTag)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else null,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A scrollable grid of every page in the document, one thumbnail per cell, opened scrolled to
 * [currentPage].
 *
 * [thumbnails] and [failed thumbnails][ThumbnailGridState.failed] arrive from whatever is generating
 * them lazily for the cells actually on screen — see [ThumbnailPipeline] — so a cell with neither is
 * simply still loading. [onThumbnailsWanted] is told the current visible range, widened by a page or
 * two of prefetch, on every scroll; nothing here decides what to do with a page once it scrolls away,
 * that is [ThumbnailPipeline.setWanted]'s job on the other end of this callback.
 */
@Composable
private fun PagesGrid(
    pageCount: Int,
    currentPage: Int,
    thumbnails: ThumbnailGridState<BorrowedThumbnail>,
    onThumbnailsWanted: (List<Int>) -> Unit,
    onSelect: (Int) -> Unit
) {
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    val columns = remember(widthPx) {
        pageThumbnailColumns(FoliumWidthClass.of(with(density) { widthPx.toDp() }))
    }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))

    LaunchedEffect(gridState, pageCount, columns) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.map { it.index } }.collect { visible ->
            if (visible.isEmpty()) return@collect
            onThumbnailsWanted(wantedThumbnailPages(visible.first(), visible.last(), pageCount, prefetch = columns))
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceAtLeast(1)),
        state = gridState,
        contentPadding = PaddingValues(GridPadding),
        horizontalArrangement = Arrangement.spacedBy(GridGutter),
        verticalArrangement = Arrangement.spacedBy(GridGutter),
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width }
            .testTag(ReaderTestTags.PAGES_GRID)
    ) {
        items(pageCount, key = { it }) { pageIndex ->
            PageThumbnailCell(
                pageIndex = pageIndex,
                isCurrent = pageIndex == currentPage,
                thumbnail = thumbnails.thumbnails[pageIndex],
                failed = pageIndex in thumbnails.failed,
                onSelect = onSelect
            )
        }
    }
}

/**
 * One page's cell: its thumbnail once it has one, its page number always, and a 2px ink border —
 * never color alone — plus the [selected][androidx.compose.ui.semantics.SemanticsPropertyReceiver.selected]
 * semantics state when it is the current page.
 *
 * A cell with neither a thumbnail nor a recorded failure is still loading and is left blank but for
 * its border and number; a failed render is shown the same way, deliberately: this is a navigation
 * aid, not somewhere a render failure is worth reporting as an error — see [ThumbnailRenderer]'s own
 * doc for why a failure here never surfaces as more than an empty slot.
 */
@Composable
private fun PageThumbnailCell(
    pageIndex: Int,
    isCurrent: Boolean,
    thumbnail: BorrowedThumbnail?,
    failed: Boolean,
    onSelect: (Int) -> Unit
) {
    val image = remember(thumbnail) { thumbnail?.bitmap?.asImageBitmap() }
    val borderColor = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (isCurrent) CurrentPageBorder else CellBorder
    val label = (pageIndex + 1).toString()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(pageIndex) }
            .semantics(mergeDescendants = true) { selected = isCurrent }
            .testTag(ReaderTestTags.pageThumbnail(pageIndex)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ThumbnailCellAspectRatio)
                .foliumBorder(borderWidth, borderColor)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * One outline entry. An entry the document points at no page — a part title, or a destination the
 * engine could not resolve — is shown as a heading: it keeps its place and its indent so the tree
 * still reads as a tree, but it carries no click and is styled to say so, while the entries nested
 * under it stay reachable.
 */
@Composable
private fun ContentsRow(
    index: Int,
    row: OutlineRow,
    tree: ContentsTreeRow,
    isActive: Boolean,
    onSelect: (Int) -> Unit
) {
    val pageIndex = row.pageIndex
    val guideColor = MaterialTheme.colorScheme.outlineVariant
    val nodeColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    val base = Modifier
        .fillMaxWidth()
        .testTag(ReaderTestTags.contentsRow(index))
        .semantics(mergeDescendants = true) {
            if (isActive) selected = true
            if (pageIndex == null) heading()
        }
    val slot = if (pageIndex == null) base else base.clickable { onSelect(pageIndex) }
    val background = if (isActive) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = slot
            .background(background)
            .heightIn(min = TouchTarget)
            .drawBehind {
                val step = IndentStep.toPx()
                val rowStart = RowPadding.toPx()
                val centerY = size.height / 2f
                val nodeX = rowStart + step * (tree.depth + 0.5f)
                val strokeWidth = 1.dp.toPx()

                tree.ancestorContinuations.forEachIndexed { depth, continues ->
                    if (continues) {
                        val x = rowStart + step * (depth + 0.5f)
                        drawLine(guideColor, Offset(x, 0f), Offset(x, size.height), strokeWidth)
                    }
                }

                if (tree.depth > 0) {
                    val parentX = nodeX - step
                    val branchBottom = if (tree.isLastSibling) centerY else size.height
                    drawLine(guideColor, Offset(parentX, 0f), Offset(parentX, branchBottom), strokeWidth)
                    drawLine(guideColor, Offset(parentX, centerY), Offset(nodeX, centerY), strokeWidth)
                }

                if (tree.hasChildren) {
                    drawLine(guideColor, Offset(nodeX, centerY), Offset(nodeX, size.height), strokeWidth)
                }

                if (pageIndex != null) {
                    drawCircle(nodeColor, if (isActive) 4.dp.toPx() else 3.dp.toPx(), Offset(nodeX, centerY))
                } else {
                    drawCircle(nodeColor, 3.dp.toPx(), Offset(nodeX, centerY), style = Stroke(strokeWidth))
                }
            }
            .padding(start = RowPadding, end = RowPadding, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(IndentStep * (tree.depth + 1)))

        Text(
            text = contentsRowTitle(row.title, stringResource(R.string.reader_contents_untitled)),
            style = if (row.depth == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
            fontWeight = if (pageIndex == null) FontWeight.Medium else null,
            color = if (pageIndex == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag(ReaderTestTags.contentsTitle(index))
        )

        if (pageIndex != null) {
            Text(
                text = (pageIndex + 1).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}
