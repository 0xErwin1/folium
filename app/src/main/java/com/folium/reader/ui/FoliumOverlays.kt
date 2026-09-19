package com.folium.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Everything the app floats above the page.
 *
 * Material separates an overlay from what is under it with a tinted surface and a shadow. This
 * design system separates by rule instead, at a weight the system reserves per overlay: a menu
 * carries a 1px ink border, the same weight the system uses for every other panel edge, a dialog
 * carries the 2px rule the system otherwise reserves for a section header. Neither carries
 * elevation or a tint. That is not decoration — it is the property that lets the same screen
 * render on a backlit display and on electronic paper, where a soft shadow arrives as a grey smear
 * and a tonal container is indistinguishable from the page it sits on.
 *
 * They live here as two wrappers rather than as arguments repeated at seven call sites, because a
 * rule spelled out seven times is a rule that drifts.
 */
private val MenuBorder = 1.dp
private val DialogBorder = 2.dp

@Composable
internal fun FoliumMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(MenuBorder, MaterialTheme.colorScheme.onSurface),
        content = content
    )
}

/**
 * A dialog on the same terms. The scrim stays: an overlay that takes the whole screen's attention
 * should dim what it interrupts, and a scrim is a flat fill rather than a shadow.
 */
@Composable
internal fun FoliumDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        title = title,
        text = text,
        modifier = modifier.foliumOverlayBorder(),
        shape = MaterialTheme.shapes.small,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    )
}

/** AlertDialog has no border of its own, so the rule is drawn around whatever it was given. */
@Composable
private fun Modifier.foliumOverlayBorder(): Modifier =
    border(DialogBorder, MaterialTheme.colorScheme.onSurface)
