package com.folium.reader.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.folium.reader.R
import com.folium.reader.core.pdf.OutlineRow
import kotlin.math.min

/** How far a nested outline row is allowed to keep indenting before the indent stops growing. */
private const val MAX_INDENT_DEPTH = 4

private val IndentStep = 16.dp
private val RowPadding = 16.dp
private val TouchTarget = 48.dp

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

    AlertDialog(
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

/**
 * The document's own table of contents, as a full-screen surface.
 *
 * A contents list is as long as the document made it, so it is given the whole screen rather than a
 * dialog-sized window — a reader scrolling to chapter forty should not be doing it through a
 * letterbox. It is a plain [Dialog] rather than a bottom sheet because the sheet is still an
 * opt-in experimental API at the pinned Material version, which is not something to put on a core
 * reading screen.
 */
@Composable
internal fun ContentsSheet(
    rows: List<OutlineRow>,
    currentPage: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val activeIndex = remember(rows, currentPage) { activeContentsRowIndex(rows, currentPage) }
    val treeRows = remember(rows) { contentsTreeRows(rows) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag(ReaderTestTags.CONTENTS_SHEET),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                ContentsHeader(onDismiss)

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(rows) { index, row ->
                        ContentsRow(index, row, treeRows[index], index == activeIndex, onSelect)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentsHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .padding(start = RowPadding, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.reader_contents),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        TextButton(
            onClick = onDismiss,
            modifier = Modifier.heightIn(min = TouchTarget).testTag(ReaderTestTags.CONTENTS_CLOSE)
        ) {
            Text(stringResource(R.string.reader_contents_close))
        }
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
