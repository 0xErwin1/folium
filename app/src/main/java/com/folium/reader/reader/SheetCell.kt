package com.folium.reader.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folium.reader.R
import com.folium.reader.core.ink.SheetId
import com.folium.reader.core.sequence.SequenceLabel
import com.folium.reader.ui.FoliumSpacing

private val SheetHeaderHeight = 32.dp

private val SheetHeaderStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)

/**
 * One sheet's cell in the reader's pager, read between the book's pages: a 32dp header over a paper
 * body. The header carries a hairline of ink along its top, the kind of cell at its start and the
 * sheet's place in the book — "19 · SHEET 1" — at its end, in the muted outline colour so it reads
 * as a label rather than as content.
 *
 * [content] fills the body, keyed on [sheet] so whatever it holds starts afresh for every sheet the
 * cell is reused for. The body is drawn in the theme's surface, the same paper
 * [com.folium.reader.ink.SheetPane] writes on, so a pane placed there sits on the paper it expects.
 */
@Composable
internal fun SheetCell(
    sheet: SheetId,
    label: SequenceLabel,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {}
) {
    val muted = MaterialTheme.colorScheme.outline
    val paper = MaterialTheme.colorScheme.surface

    Column(modifier.fillMaxSize().background(paper).testTag(ReaderTestTags.SHEET_CELL)) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.onSurface)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SheetHeaderHeight - 1.dp)
                .padding(horizontal = FoliumSpacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.reader_sheet_kind).uppercase(),
                style = SheetHeaderStyle,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = sheetLabelText(label),
                style = SheetHeaderStyle,
                color = muted,
                maxLines = 1
            )
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            key(sheet) { content() }
        }
    }
}

/** "19 · SHEET 1": a sheet's page, 1-based, and its ordinal among that page's sheets. */
@Composable
private fun sheetLabelText(label: SequenceLabel): String =
    stringResource(R.string.reader_sheet_label, label.pageNumber, label.sheetOrdinal ?: 1).uppercase()
