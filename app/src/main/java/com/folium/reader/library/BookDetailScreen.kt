package com.folium.reader.library

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folium.reader.R
import com.folium.reader.core.library.ShelfEntry
import com.folium.reader.core.pdf.OutlineRow
import com.folium.reader.ui.FoliumSpacing
import com.folium.reader.ui.FoliumWidthClass
import androidx.compose.ui.unit.Dp
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

object BookDetailTestTags {
    const val SCREEN = "detail"
    const val BACK = "detail-back"
    const val CONTINUE = "detail-continue"
    const val CONTENTS = "detail-contents"
    const val REMOVE = "detail-remove"
    const val UNREADABLE = "detail-unreadable"
}

/**
 * One book, at length.
 *
 * The same content the wide layout keeps in a side panel, given the whole screen because on a phone
 * there is no room beside anything. It is also where removing a book lives: the shelf reaches it by
 * long press, which is discoverable by accident at best, and a destructive action deserves a place
 * where it is named in full rather than drawn as a cross.
 */
@Composable
fun BookDetailScreen(
    entry: ShelfEntry,
    detail: BookDetail,
    thumbnail: Bitmap?,
    onBack: () -> Unit,
    onOpen: () -> Unit,
    onOpenAt: (Int) -> Unit,
    onRemove: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BookDetailBody(
            entry = entry,
            detail = detail,
            thumbnail = thumbnail,
            onOpen = onOpen,
            onOpenAt = onOpenAt,
            onRemove = onRemove,
            modifier = Modifier.safeDrawingPadding(),
            header = { DetailHeader(onBack) }
        )
    }
}

/**
 * The detail without a screen around it.
 *
 * On a wide layout this is the right pane and the shelf keeps the left, so it carries no way back:
 * there is nothing to go back to when both are visible at once.
 */
@Composable
internal fun BookDetailBody(
    entry: ShelfEntry,
    detail: BookDetail,
    thumbnail: Bitmap?,
    onOpen: () -> Unit,
    onOpenAt: (Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthClass = FoliumWidthClass.of(maxWidth)

        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(BookDetailTestTags.SCREEN),
            contentPadding = PaddingValues(
                start = widthClass.margin,
                end = widthClass.margin,
                bottom = FoliumSpacing.xxl
            )
        ) {
            header?.let { item { it() } }

            item {
                DetailIdentity(
                    entry = entry,
                    detail = detail,
                    thumbnail = thumbnail,
                    onOpen = onOpen,
                    gutter = widthClass.gutter
                )
            }

            item { DetailFacts(entry) }

            if (detail.unreadable) {
                item {
                    Text(
                        text = stringResource(R.string.detail_unreadable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = FoliumSpacing.m).testTag(BookDetailTestTags.UNREADABLE)
                    )
                }
            }

            item { ContentsRule(detail) }

            itemsIndexed(detail.contents) { index, chapter ->
                ChapterRow(chapter = chapter, onOpenAt = onOpenAt, key = index)
            }

            if (detail.contents.isEmpty() && !detail.unreadable) {
                item { NoContents() }
            }

            item { RemoveAction(onRemove) }
        }
    }
}

@Composable
private fun DetailHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = FoliumSpacing.touchTarget)
            .clickable(onClick = onBack)
            .testTag(BookDetailTestTags.BACK),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "‹",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(FoliumSpacing.s))
        Text(
            text = stringResource(R.string.detail_back).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Cover beside name, on the same two-and-two split the shelf's continue block uses. */
@Composable
private fun DetailIdentity(
    entry: ShelfEntry,
    detail: BookDetail,
    thumbnail: Bitmap?,
    onOpen: () -> Unit,
    gutter: Dp
) {
    Column {
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface)

        Row(Modifier.fillMaxWidth().padding(top = FoliumSpacing.m)) {
            Box(Modifier.weight(1f)) {
                BookCover(thumbnail = thumbnail, imageTag = LibraryTestTags.bookThumbnail(entry.book.id))
            }

            Spacer(Modifier.width(gutter))

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Bottom) {
                Text(
                    text = entry.book.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )

                (detail.metadata.author ?: entry.book.author)?.let { author ->
                    Spacer(Modifier.height(FoliumSpacing.xxs))
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(FoliumSpacing.s))

                Button(
                    onClick = onOpen,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = FoliumSpacing.touchTarget)
                        .testTag(BookDetailTestTags.CONTINUE)
                ) {
                    Text(stringResource(R.string.library_continue_action, entry.displayPage))
                }
            }
        }
    }
}

@Composable
private fun DetailFacts(entry: ShelfEntry) {
    val started = entry.pageIndex > 0
    val read = if (started) {
        stringResource(
            R.string.library_book_progress,
            entry.displayPage,
            entry.pageCount,
            (entry.fraction * 100).roundToInt()
        )
    } else {
        stringResource(R.string.detail_unopened)
    }

    Column(Modifier.padding(top = FoliumSpacing.l)) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(FoliumSpacing.s))

        Fact(R.string.detail_pages, entry.pageCount.toString())
        Fact(R.string.detail_read, read)
        Fact(R.string.detail_added, added(entry.book.addedAtMillis))
    }
}

@Composable
private fun Fact(labelResource: Int, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = FoliumSpacing.xxs)) {
        Text(
            text = stringResource(labelResource).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(FactLabelWidth)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * What stands in for a table of contents when the document declares none.
 *
 * Most scans declare none, so this is not a rare error state — it is what the section usually
 * looks like. A bare sentence left the reader at a dead end under a heading promising chapters;
 * the outline gives the absence a shape of its own and names the two ways through the book that
 * do not depend on an index.
 */
@Composable
private fun NoContents() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FoliumSpacing.s)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(FoliumSpacing.m),
        verticalArrangement = Arrangement.spacedBy(FoliumSpacing.xxs)
    ) {
        Text(
            text = stringResource(R.string.detail_contents_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.detail_contents_none_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ContentsRule(detail: BookDetail) {
    Column(Modifier.padding(top = FoliumSpacing.l).testTag(BookDetailTestTags.CONTENTS)) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.onSurface)

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = FoliumSpacing.s, bottom = FoliumSpacing.xxs),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.detail_contents).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (detail.contents.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.detail_contents_count, detail.contents.size).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * A chapter, indented by its depth. An entry with no destination is a heading the producer wrote
 * without a target; it is shown because it structures the list, and does nothing when touched.
 */
@Composable
private fun ChapterRow(chapter: OutlineRow, onOpenAt: (Int) -> Unit, key: Int) {
    val target = chapter.pageIndex

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (target == null) Modifier else Modifier.clickable { onOpenAt(target) })
            .heightIn(min = FoliumSpacing.touchTarget)
            .wrapContentHeight()
            .padding(
                start = (chapter.depth * ChapterIndent.value).dp,
                top = FoliumSpacing.xs,
                bottom = FoliumSpacing.xs
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = chapter.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(FoliumSpacing.s))
        Text(
            text = target?.let { (it + 1).toString() }.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Named in full, alone, and last. It is the one thing on this screen that cannot be undone. */
@Composable
private fun RemoveAction(onRemove: () -> Unit) {
    Text(
        text = stringResource(R.string.detail_remove),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .padding(top = FoliumSpacing.xl)
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.error)
            .clickable(onClick = onRemove)
            .heightIn(min = FoliumSpacing.touchTarget)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = FoliumSpacing.m)
            .testTag(BookDetailTestTags.REMOVE)
    )
}

/** The reader's own locale and zone: when a book joined the shelf is a fact about their day. */
@Composable
private fun added(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM)
    .format(Date(millis))

private val FactLabelWidth = 104.dp
private val ChapterIndent = 16.dp
