package com.folium.reader.ink

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.folium.reader.R
import com.folium.reader.core.ink.InkStroke
import com.folium.reader.ui.FoliumSpacing
import com.folium.reader.ui.FoliumType
import com.folium.reader.ui.foliumBorder

/**
 * [surface]'s selection menu, drawn over a pane [paneWidthPx] by [paneHeightPx] view pixels whose
 * top-left corner is the surface's own, whenever [state] and [tools] say it is shown — see
 * [selectionMenuShown]. Its items come from [selectionMenuItems]: convert-to-text only when
 * [onConvertToText] is given, the text panel for a selection holding a text box, then copy and delete,
 * each acted on through [surface] or [tools].
 */
@Composable
internal fun InkSelectionMenu(
    state: InkSurfaceUiState,
    surface: InkDrawingSurface?,
    tools: SheetTools,
    paneWidthPx: Float?,
    paneHeightPx: Float?,
    onConvertToText: ((List<InkStroke>) -> Unit)? = null
) {
    val bounds = state.selectionBoundsViewPx
    val shown = selectionMenuShown(
        selectionSize = state.selectedStrokeIds.size,
        boundsViewPx = bounds,
        paneLaidOut = paneWidthPx != null && paneHeightPx != null,
        selectionEditing = state.selectionEditing,
        panelOpen = tools.selectorState.openPanel != null
    )

    if (!shown || bounds == null || paneWidthPx == null || paneHeightPx == null) return

    SelectionMenuOverlay(
        boundsViewPx = bounds,
        surfaceOriginInWindow = { surface?.originInWindow() ?: IntOffset.Zero },
        paneWidthPx = paneWidthPx,
        paneHeightPx = paneHeightPx,
        hasConvertToTextHandler = onConvertToText != null,
        hasTextBoxInSelection = state.selectionHasTextBoxes,
        onAction = { action ->
            when (action) {
                SelectionMenuAction.CONVERT_TO_TEXT -> onConvertToText?.invoke(surface?.selectedStrokesInZOrder().orEmpty())
                SelectionMenuAction.TEXT -> tools.reduce(SheetSelectorEvent.SelectionTextRequested)
                SelectionMenuAction.COPY -> surface?.copySelection()
                SelectionMenuAction.DELETE -> surface?.deleteSelection()
            }
        }
    )
}

/** A menu item's own horizontal padding (`rail-spec.md` 2.2: "padding: 0 14px"); its own min-height reuses [FoliumSpacing.touchTarget], the same 44dp the spec calls for. */
private val SelectionMenuItemHorizontalPadding = 14.dp

/** Where this view's own top-left corner sits in its window: a [Popup] is positioned in window pixels, the selection in this view's. */
internal fun View.originInWindow(): IntOffset {
    val location = IntArray(2)
    getLocationInWindow(location)

    return IntOffset(location[0], location[1])
}

/**
 * The selection menu: a box of items in a row, aligned with the selection's own left edge under it, or
 * above it once there is no room below. The design's leader tick is left out on purpose: the menu has
 * to stand clear of the corner handles, and a tick floating in that gap reads as a stray mark. Positioned through a [PopupPositionProvider] built from [selectionMenuPlacement]
 * rather than a fixed offset, since the box's own width depends on how many items [hasConvertToTextHandler]
 * puts in it and Compose only reports a [Popup]'s own content size once it has been measured.
 */
@Composable
internal fun SelectionMenuOverlay(
    boundsViewPx: ViewRect,
    surfaceOriginInWindow: () -> IntOffset,
    paneWidthPx: Float,
    paneHeightPx: Float,
    hasConvertToTextHandler: Boolean,
    hasTextBoxInSelection: Boolean = false,
    onAction: (SelectionMenuAction) -> Unit
) {
    val density = LocalDensity.current
    // The menu is its own window and takes every touch inside it, so it has to stay clear of the corner handles' hit areas.
    val handleClearancePx = with(density) { (FoliumSpacing.touchTarget / 2).roundToPx() }

    val positionProvider = remember(boundsViewPx, paneWidthPx, paneHeightPx, handleClearancePx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val placement = selectionMenuPlacement(
                    selectionLeftPx = boundsViewPx.left.toInt(),
                    selectionTopPx = boundsViewPx.top.toInt() - handleClearancePx,
                    selectionBottomPx = boundsViewPx.bottom.toInt() + handleClearancePx,
                    paneWidthPx = paneWidthPx.toInt(),
                    paneHeightPx = paneHeightPx.toInt(),
                    marginStartPx = 0,
                    contentWidthPx = popupContentSize.width,
                    contentHeightPx = popupContentSize.height
                )
                val origin = surfaceOriginInWindow()

                return IntOffset(origin.x + placement.leftPx, origin.y + placement.topPx)
            }
        }
    }

    Popup(popupPositionProvider = positionProvider) {
        SelectionMenuBox(hasConvertToTextHandler = hasConvertToTextHandler, hasTextBoxInSelection = hasTextBoxInSelection, onAction = onAction)
    }
}

/** The box itself: a 1dp ink border on a paper background, its items in a row separated by 1dp rules (`rail-spec.md` 2.2, ELEGIR panel's own menu anatomy). */
@Composable
private fun SelectionMenuBox(hasConvertToTextHandler: Boolean, hasTextBoxInSelection: Boolean, onAction: (SelectionMenuAction) -> Unit) {
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.surface)
            .foliumBorder(1.dp, MaterialTheme.colorScheme.onSurface)
            .testTag(SheetPaneTestTags.SELECTION_MENU)
    ) {
        val items = selectionMenuItems(hasConvertToTextHandler, hasTextBoxInSelection)
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .heightIn(min = FoliumSpacing.touchTarget)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            SelectionMenuItemButton(item = item, onClick = { onAction(item.action) })
        }
    }
}

@Composable
private fun SelectionMenuItemButton(item: SelectionMenuItem, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val alarm = MaterialTheme.colorScheme.error

    val textColor = when {
        item.isPrimary -> paper
        item.action == SelectionMenuAction.DELETE -> alarm
        else -> ink
    }
    val backgroundColor = if (item.isPrimary) ink else paper

    Box(
        Modifier
            .background(backgroundColor)
            .heightIn(min = FoliumSpacing.touchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = SelectionMenuItemHorizontalPadding)
            .testTag(item.action.testTag()),
        contentAlignment = Alignment.Center
    ) {
        Text(text = stringResource(item.action.labelRes()).uppercase(), style = FoliumType.CaptionEmphasis, color = textColor)
    }
}

private fun SelectionMenuAction.labelRes(): Int = when (this) {
    SelectionMenuAction.CONVERT_TO_TEXT -> R.string.sheet_selection_menu_convert_to_text
    SelectionMenuAction.TEXT -> R.string.sheet_selection_menu_text
    SelectionMenuAction.COPY -> R.string.sheet_selection_menu_copy
    SelectionMenuAction.DELETE -> R.string.sheet_selection_menu_delete
}

private fun SelectionMenuAction.testTag(): String = when (this) {
    SelectionMenuAction.CONVERT_TO_TEXT -> SheetPaneTestTags.SELECTION_MENU_CONVERT
    SelectionMenuAction.TEXT -> SheetPaneTestTags.SELECTION_MENU_TEXT
    SelectionMenuAction.COPY -> SheetPaneTestTags.SELECTION_MENU_COPY
    SelectionMenuAction.DELETE -> SheetPaneTestTags.SELECTION_MENU_DELETE
}
