package com.folium.reader.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folium.reader.R
import com.folium.reader.core.library.DocumentProbeFailure
import com.folium.reader.core.library.LibraryDocumentCandidate
import com.folium.reader.core.library.LibraryState
import com.folium.reader.core.library.ProviderDocumentIdentity
import com.folium.reader.core.library.RecoveryAction
import com.folium.reader.core.library.RecoveryState

object LibraryTestTags {
    const val LOADING = "library-loading"
    const val FIRST_SELECTION = "library-first-selection"
    const val EMPTY = "library-empty"
    const val DOCUMENTS = "library-documents"
    const val RECOVERY = "library-recovery"
    const val SKIPPED = "library-skipped"
    const val PRIMARY_ACTION = "library-primary-action"
    const val CHANGE_ROOT = "library-change-root"

    fun document(identity: ProviderDocumentIdentity): String =
        "library-document/${identity.providerAuthority}/${identity.documentId}"
}

private val MessageWidth = 480.dp
private val TouchTarget = 48.dp
private val ExpandedWidth = 600.dp
private val LargeWidth = 1000.dp

/**
 * One column on a phone, more as width allows, so a wide screen does not show a single stretched
 * row per file.
 */
fun libraryColumns(availableWidth: Dp): Int = when {
    availableWidth >= LargeWidth -> 3
    availableWidth >= ExpandedWidth -> 2
    else -> 1
}

/**
 * The whole library surface. Stateless by design: every outcome it can render arrives as a
 * [LibraryState], which is what lets each state be exercised directly.
 */
@Composable
fun LibraryScreen(
    state: LibraryState,
    onSelectRoot: () -> Unit,
    onRetry: () -> Unit,
    onOpenDocument: (LibraryDocumentCandidate) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (state) {
                is LibraryState.Loading -> LoadingScene()

                is LibraryState.Content -> DocumentsScene(
                    documents = state.documents,
                    skipped = state.skipped,
                    onSelectRoot = onSelectRoot,
                    onOpenDocument = onOpenDocument
                )

                is LibraryState.Empty -> EmptyScene(state.skipped, onSelectRoot)

                is LibraryState.PermissionLost -> RecoveryScene(state.recovery, onSelectRoot, onRetry)

                is LibraryState.Error -> RecoveryScene(state.recovery, onSelectRoot, onRetry)
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
                text = stringResource(R.string.library_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

/**
 * Renders both the untouched first run and every root-level failure.
 *
 * They share a layout because they are the same shape of message, but never the same wording: the
 * first run is an invitation, the rest name what broke and offer the typed remedy.
 */
@Composable
private fun RecoveryScene(
    recovery: RecoveryState,
    onSelectRoot: () -> Unit,
    onRetry: () -> Unit
) {
    val firstSelection = LibraryCopy.isFirstSelection(recovery.reason)
    val action = rootLevelAction(recovery.action)

    CenteredMessage(
        tag = if (firstSelection) LibraryTestTags.FIRST_SELECTION else LibraryTestTags.RECOVERY,
        title = stringResource(LibraryCopy.rootTitle(recovery.reason)),
        body = stringResource(LibraryCopy.rootBody(recovery.reason))
    ) {
        Button(
            onClick = if (action == RecoveryAction.Retry) onRetry else onSelectRoot,
            modifier = Modifier.heightIn(min = TouchTarget).testTag(LibraryTestTags.PRIMARY_ACTION)
        ) {
            Text(stringResource(LibraryCopy.actionLabel(action)))
        }

        if (!firstSelection) {
            TextButton(
                onClick = onSelectRoot,
                modifier = Modifier.heightIn(min = TouchTarget).testTag(LibraryTestTags.CHANGE_ROOT)
            ) {
                Text(stringResource(R.string.library_action_choose_another))
            }
        }
    }
}

/**
 * Skipping is a per-document remedy; when the whole root failed the equivalent offer is to retry.
 */
private fun rootLevelAction(action: RecoveryAction): RecoveryAction =
    if (action == RecoveryAction.SkipDocument) RecoveryAction.Retry else action

@Composable
private fun EmptyScene(skipped: List<DocumentProbeFailure>, onSelectRoot: () -> Unit) {
    ScrollableCenteredColumn {
        Column(
            modifier = Modifier.widthIn(max = MessageWidth).testTag(LibraryTestTags.EMPTY),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MessageText(
                title = stringResource(R.string.library_empty_title),
                body = stringResource(R.string.library_empty_body)
            )

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = onSelectRoot,
                modifier = Modifier.heightIn(min = TouchTarget).testTag(LibraryTestTags.CHANGE_ROOT)
            ) {
                Text(stringResource(R.string.library_action_choose_another))
            }
        }

        if (skipped.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))

            Box(Modifier.widthIn(max = MessageWidth)) { SkippedPanel(skipped) }
        }
    }
}

@Composable
private fun DocumentsScene(
    documents: List<LibraryDocumentCandidate>,
    skipped: List<DocumentProbeFailure>,
    onSelectRoot: () -> Unit,
    onOpenDocument: (LibraryDocumentCandidate) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        LibraryHeader(onSelectRoot)

        BoxWithAvailableWidth { availableWidth ->
            LazyVerticalGrid(
                columns = GridCells.Fixed(libraryColumns(availableWidth)),
                modifier = Modifier.fillMaxSize().testTag(LibraryTestTags.DOCUMENTS),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (skipped.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SkippedPanel(skipped) }
                }

                items(documents, key = { "${it.identity.providerAuthority}\u0000${it.identity.documentId}" }) { document ->
                    DocumentRow(document, onOpenDocument)
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(onSelectRoot: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.library_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )

        TextButton(
            onClick = onSelectRoot,
            modifier = Modifier.heightIn(min = TouchTarget).testTag(LibraryTestTags.CHANGE_ROOT)
        ) {
            Text(stringResource(R.string.library_change_folder))
        }
    }
}

@Composable
private fun DocumentRow(
    document: LibraryDocumentCandidate,
    onOpenDocument: (LibraryDocumentCandidate) -> Unit
) {
    val openLabel = stringResource(R.string.library_open_document, document.displayName)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(onClickLabel = openLabel) { onOpenDocument(document) }
            .testTag(LibraryTestTags.document(document.identity))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = document.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.library_document_kind),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Documents that could not be listed. Kept on screen next to the ones that loaded, because a
 * silently shorter list is indistinguishable from a folder that simply holds fewer files.
 */
@Composable
private fun SkippedPanel(skipped: List<DocumentProbeFailure>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LibraryTestTags.SKIPPED)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.library_skipped_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )

        Text(
            text = stringResource(R.string.library_skipped_count, skipped.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )

        skipped.forEach { failure -> SkippedRow(failure) }
    }
}

@Composable
private fun SkippedRow(failure: DocumentProbeFailure) {
    Column {
        Text(
            text = failure.identity?.documentId ?: stringResource(R.string.library_skipped_unnamed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = stringResource(LibraryCopy.skipExplanation(failure.recovery.reason)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun CenteredMessage(
    tag: String,
    title: String,
    body: String,
    actions: @Composable () -> Unit
) {
    ScrollableCenteredColumn {
        Column(
            modifier = Modifier.widthIn(max = MessageWidth).testTag(tag),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MessageText(title, body)

            Spacer(Modifier.height(32.dp))

            actions()
        }
    }
}

@Composable
private fun MessageText(title: String, body: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center
    )

    Spacer(Modifier.height(12.dp))

    Text(
        text = body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

/** Centres its content when it fits, and scrolls instead of clipping when it does not. */
@Composable
private fun ScrollableCenteredColumn(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            content()
        }
    }
}

@Composable
private fun BoxWithAvailableWidth(content: @Composable (Dp) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        content(maxWidth)
    }
}
