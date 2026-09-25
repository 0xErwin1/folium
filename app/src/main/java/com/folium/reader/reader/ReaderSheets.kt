package com.folium.reader.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.folium.reader.R
import com.folium.reader.core.ink.OpenSheet
import com.folium.reader.core.ink.SheetId
import com.folium.reader.ink.PenSettings
import com.folium.reader.ink.SheetPane
import com.folium.reader.ink.SheetPaneHistory
import com.folium.reader.ui.FoliumSpacing
import com.folium.reader.ui.FoliumType
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

/**
 * What [ReaderHost] needs from the activity to put a book's sheets on screen live: the activity's
 * own sheet store, so the reader never opens a second writer beside the sheet screen's, and where
 * each sheet's thumbnail lives. Every one of the three blocks, and runs only on the serial worker
 * [SheetWriterLease] is given.
 */
class ReaderSheetAccess(
    val open: (SheetId) -> OpenSheet,
    val writeThumbnail: (OpenSheet) -> Unit,
    val readThumbnail: (SheetId) -> Bitmap?
)

/** Test tags a UI test finds a reader sheet cell's body by. */
object ReaderSheetTestTags {
    const val THUMBNAIL = "reader-sheet-thumbnail"
    const val OPEN_ELSEWHERE = "reader-sheet-open-elsewhere"
}

/**
 * The body of one sheet's cell in the reader: the live, embedded [SheetPane] when [id] is the sheet
 * on the current unit and [leaseState] holds its writer open, and otherwise that sheet's thumbnail —
 * a neighbour composed off screen, a sheet still opening, or one another writer holds, which is also
 * marked "open elsewhere".
 *
 * The pane is keyed on its [OpenSheet], with the release effect declared before the pane itself.
 * Compose forgets a removed group's remembered objects in the reverse of the order it remembered
 * them, so the pane's own disposal — which flushes and closes its drawing surface — always runs
 * before [SheetWriterLease.release] queues the thumbnail and the close.
 */
@Composable
internal fun ReaderSheetBody(
    id: SheetId,
    isCurrentUnit: Boolean,
    leaseState: SheetLeaseState,
    lease: SheetWriterLease,
    history: SheetPaneHistory,
    readThumbnail: (SheetId) -> Bitmap?,
    work: Executor,
    penSettings: PenSettings,
    onPenSettingsChange: (PenSettings) -> Unit
) {
    val live = (leaseState as? SheetLeaseState.Open)?.takeIf { isCurrentUnit && it.id == id }?.sheet

    if (live != null) {
        key(live) {
            DisposableEffect(Unit) {
                lease.attach(live)
                onDispose { lease.release(live) }
            }

            SheetPane(
                openSheet = live,
                onBack = {},
                penSettings = penSettings,
                onPenSettingsChange = onPenSettingsChange,
                history = history,
                embedded = true,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        val openElsewhere = isCurrentUnit && leaseState == SheetLeaseState.Unavailable(id, openElsewhere = true)

        SheetThumbnail(id, readThumbnail, work, openElsewhere)
    }
}

/**
 * [id]'s saved thumbnail across the cell's width from its top, the same region of the sheet the
 * thumbnail was rendered from; blank paper while it decodes, or when the sheet has none. Decoded on
 * [work], so a thumbnail a release has just queued is written before it is read back here.
 */
@Composable
private fun SheetThumbnail(id: SheetId, readThumbnail: (SheetId) -> Bitmap?, work: Executor, openElsewhere: Boolean) {
    val thumbnail by produceState<Bitmap?>(null, id) {
        value = withContext(work.asCoroutineDispatcher()) { runCatching { readThumbnail(id) }.getOrNull() }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        thumbnail?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).testTag(ReaderSheetTestTags.THUMBNAIL)
            )
        }

        if (openElsewhere) {
            Text(
                text = stringResource(R.string.reader_sheet_open_elsewhere).uppercase(),
                style = FoliumType.CaptionEmphasis,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(FoliumSpacing.s)
                    .testTag(ReaderSheetTestTags.OPEN_ELSEWHERE)
            )
        }
    }
}
