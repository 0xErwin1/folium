package com.folium.reader.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folium.reader.R
import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.ink.SheetSummary
import com.folium.reader.ui.FoliumDialog
import com.folium.reader.ui.FoliumDivider
import com.folium.reader.ui.FoliumGrid
import com.folium.reader.ui.FoliumMenu
import com.folium.reader.ui.FoliumSpacing
import com.folium.reader.ui.FoliumType

internal object LibrarySheetTestTags {
    const val UNREADABLE = "library-sheets-unreadable"
    const val DELETE_CONFIRM = "library-sheet-delete-confirm"
    const val DELETE_CANCEL = "library-sheet-delete-cancel"

    fun sheet(id: SheetId): String = "library-sheet-${id.value}"
    fun menu(id: SheetId): String = "library-sheet-menu-${id.value}"
    fun delete(id: SheetId): String = "library-sheet-delete-${id.value}"
}

/**
 * Which of [sheets] the shelf should show, given the reading-progress [filter] the reader chose and
 * whatever [query] the search field carries.
 *
 * [ShelfFilter.STARTED] and [ShelfFilter.UNOPENED] describe reading progress that a handwritten
 * sheet does not have — there is no page count to be a fraction of — so both filters hide every
 * sheet rather than guess an answer neither one can give.
 */
internal fun visibleSheets(sheets: List<SheetSummary>, filter: ShelfFilter, query: String?): List<SheetSummary> {
    if (filter != ShelfFilter.ALL) return emptyList()

    return sheets
        .filter { query.isNullOrBlank() || it.title.contains(query, ignoreCase = true) }
        .sortedWith(compareByDescending<SheetSummary> { it.updatedAtEpochMillis }.thenBy { it.title })
}

/**
 * The blank-page look a sheet's thumbnail shares everywhere it appears: a field-toned box with a
 * paper rectangle inset inside it, standing in for the cover a sheet does not have.
 */
@Composable
private fun SheetThumbnail(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(FoliumSpacing.xs)
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
    }
}

/**
 * A handwritten sheet's own grid cell: the same geometry [BookCell] draws — a cover box, then a
 * title beneath it — with the blank-page thumbnail standing in for a cover and a "Sheet" caption
 * standing in for whatever a book cell would otherwise say about progress.
 *
 * A tap opens the sheet, and a long press names the one thing a reader can do to it besides that:
 * delete it, the same split [BookCell] draws between opening and its own menu of actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SheetCell(
    sheet: SheetSummary,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDeleteRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val actionsLabel = stringResource(R.string.library_sheet_actions, sheet.title)

    Column(
        modifier
            .fillMaxWidth()
            .semantics { onLongClick(label = actionsLabel, action = null) }
            .combinedClickable(enabled = enabled, onClick = onOpen, onLongClick = { menuOpen = true })
            .testTag(LibrarySheetTestTags.sheet(sheet.id))
    ) {
        SheetThumbnail(Modifier.fillMaxWidth().aspectRatio(FoliumGrid.COVER_ASPECT))

        Spacer(Modifier.height(FoliumSpacing.xs))

        Text(
            text = sheet.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(FoliumSpacing.xxs))

        Text(
            text = stringResource(R.string.library_sheet_label),
            style = FoliumType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SheetActionsMenu(
            expanded = menuOpen,
            sheetId = sheet.id,
            onDismiss = { menuOpen = false },
            onDeleteRequested = { menuOpen = false; onDeleteRequested() }
        )
    }
}

/**
 * A handwritten sheet's own row on the dense list, consistent with [BookRow]: a hairline rule above
 * it, a small thumbnail, a title, and a "Sheet" caption where a book row would show its progress.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SheetRow(
    sheet: SheetSummary,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDeleteRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val actionsLabel = stringResource(R.string.library_sheet_actions, sheet.title)

    FoliumDivider.Horizontal(thickness = 1.dp, color = rowDividerColor(MaterialTheme.colorScheme))

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .semantics { onLongClick(label = actionsLabel, action = null) }
            .combinedClickable(enabled = enabled, onClick = onOpen, onLongClick = { menuOpen = true })
            .testTag(LibrarySheetTestTags.sheet(sheet.id))
            .padding(start = 14.dp, end = 4.dp, top = 14.dp, bottom = 14.dp)
    ) {
        SheetThumbnail(Modifier.size(width = ThumbnailWidth, height = ThumbnailHeight))

        Spacer(Modifier.width(14.dp))

        Column(Modifier.padding(top = 2.dp)) {
            Text(
                text = sheet.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.library_sheet_label),
                style = FoliumType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SheetActionsMenu(
            expanded = menuOpen,
            sheetId = sheet.id,
            onDismiss = { menuOpen = false },
            onDeleteRequested = { menuOpen = false; onDeleteRequested() }
        )
    }
}

/**
 * The one action a reader can take on a sheet without opening it: deleting it. A single row rather
 * than [BookActionsMenu]'s two, since a sheet has no detail screen of its own to open from here.
 */
@Composable
private fun SheetActionsMenu(expanded: Boolean, sheetId: SheetId, onDismiss: () -> Unit, onDeleteRequested: () -> Unit) {
    FoliumMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(LibrarySheetTestTags.menu(sheetId))
    ) {
        MenuActionItem(
            text = stringResource(R.string.library_sheet_delete),
            color = MaterialTheme.colorScheme.error,
            onClick = onDeleteRequested,
            testTag = LibrarySheetTestTags.delete(sheetId)
        )
    }
}

/**
 * A destructive action with no undo, gated the same way [RemoveConfirmDialog] gates removing a book:
 * one confirmation naming exactly what is lost.
 */
@Composable
internal fun SheetDeleteConfirmDialog(sheet: SheetSummary, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    FoliumDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(LibrarySheetTestTags.DELETE_CONFIRM),
        title = { Text(stringResource(R.string.library_sheet_delete_confirm_title)) },
        text = { Text(stringResource(R.string.library_sheet_delete_confirm_body, sheet.title)) },
        confirmButton = {
            TextButton(onClick = onConfirm, shape = MaterialTheme.shapes.small) {
                Text(
                    text = stringResource(R.string.library_sheet_delete_confirm_action),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(LibrarySheetTestTags.DELETE_CANCEL)
            ) {
                Text(stringResource(R.string.library_sheet_delete_cancel))
            }
        }
    )
}

/**
 * A sheet whose metadata this build could not read. It is never hidden or discarded on its own
 * account — the stroke history behind it is still there on disk — so the shelf names how many
 * there are, in the error tone the rest of the library reserves for something that needs attention,
 * rather than pretending the count is zero.
 */
@Composable
internal fun UnreadableSheetsCell(count: Int, modifier: Modifier = Modifier) {
    Text(
        text = pluralStringResource(R.plurals.library_sheets_unreadable, count, count),
        style = FoliumType.Caption,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier
            .fillMaxWidth()
            .testTag(LibrarySheetTestTags.UNREADABLE)
    )
}

/** [UnreadableSheetsCell]'s own row, for the dense list. */
@Composable
internal fun UnreadableSheetsRow(count: Int, modifier: Modifier = Modifier) {
    FoliumDivider.Horizontal(thickness = 1.dp, color = rowDividerColor(MaterialTheme.colorScheme))

    Text(
        text = pluralStringResource(R.plurals.library_sheets_unreadable, count, count),
        style = FoliumType.Caption,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .padding(start = 14.dp, end = 4.dp, top = 14.dp, bottom = 14.dp)
            .testTag(LibrarySheetTestTags.UNREADABLE)
    )
}
