package com.folium.reader.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.core.text.SelectionEndpoint
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSelection
import com.folium.reader.core.text.TextSelectionPolicy
import com.folium.reader.R
import kotlin.math.roundToInt

private val HandleTouchTarget = 48.dp
private val HandleRadius = 7.dp
private val HandleOutlineWidth = 1.5.dp

@Composable
internal fun ReaderSelectionOverlay(
    textPage: TextPage,
    layout: ViewportLayout,
    selection: TextSelection?,
    onSelectionChanged: (TextSelection?) -> Unit,
    modifier: Modifier = Modifier
) {
    val policy = remember(textPage) { TextSelectionPolicy(textPage) }
    val selected = remember(policy, selection) { selection?.let(policy::selected) }
    val clipboard = LocalClipboardManager.current
    val copyLabel = stringResource(R.string.reader_selection_copy)
    val copy = {
        selected?.text?.let { clipboard.setText(AnnotatedString(it)) }
        Unit
    }

    Box(
        modifier
            .fillMaxSize()
            .testTag(ReaderTestTags.SELECTION_OVERLAY)
            .semantics {
                if (selected != null) {
                    customActions = listOf(CustomAccessibilityAction(copyLabel) { copy(); true })
                }
            }
            .selectionLongPress(layout, policy, onSelectionChanged)
            .clearSelectionTap(layout, selected?.boxes.orEmpty(), selection != null, onSelectionChanged)
    ) {
        if (selected != null && selection != null) {
            val highlight = MaterialTheme.colorScheme.primary.copy(alpha = .28f)
            val handle = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxSize().testTag(ReaderTestTags.SELECTION_HIGHLIGHT)) {
                selected.boxes.forEach { box ->
                    val rect = ReaderGeometry.destination(layout, box)
                    drawRect(highlight, Offset(rect.left, rect.top), androidx.compose.ui.geometry.Size(rect.width, rect.height))
                }
            }

            SelectionHandle(policy, layout, selection, SelectionEndpoint.ANCHOR, handle, onSelectionChanged)
            SelectionHandle(policy, layout, selection, SelectionEndpoint.FOCUS, handle, onSelectionChanged)

            val first = selected.boxes.first()
            val topLeft = ReaderGeometry.pageToViewport(layout, PageSpacePoint(first.left, first.top))
            TextButton(
                onClick = copy,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            topLeft.x.roundToInt().coerceIn(0, (layout.viewport.widthPx - 48.dp.toPx()).roundToInt()),
                            (topLeft.y - 52.dp.toPx()).roundToInt().coerceAtLeast(0)
                        )
                    }
                    .testTag(ReaderTestTags.SELECTION_COPY)
            ) {
                Text(copyLabel)
            }
        }
    }
}

@Composable
private fun SelectionHandle(
    policy: TextSelectionPolicy,
    layout: ViewportLayout,
    selection: TextSelection,
    endpoint: SelectionEndpoint,
    color: Color,
    onSelectionChanged: (TextSelection?) -> Unit
) {
    val currentSelection by rememberUpdatedState(selection)
    val box = policy.endpointBox(selection, endpoint) ?: return
    val isLeading = when (endpoint) {
        SelectionEndpoint.ANCHOR -> selection.anchorWord <= selection.focusWord
        SelectionEndpoint.FOCUS -> selection.focusWord < selection.anchorWord
    }
    val pagePoint = PageSpacePoint(if (isLeading) box.left else box.right, box.bottom)
    val point = ReaderGeometry.pageToViewport(layout, pagePoint)
    val currentPoint by rememberUpdatedState(point)
    val handleOutline = MaterialTheme.colorScheme.surface
    var dragViewportPoint = Offset(point.x, point.y)

    Box(
        Modifier
            .offset {
                val half = HandleTouchTarget.toPx() / 2f
                IntOffset((point.x - half).roundToInt(), (point.y - half).roundToInt())
            }
            .size(HandleTouchTarget)
            .testTag(
                if (endpoint == SelectionEndpoint.ANCHOR) ReaderTestTags.SELECTION_ANCHOR
                else ReaderTestTags.SELECTION_FOCUS
            )
            .pointerInput(policy, layout, endpoint) {
                detectDragGestures(
                    onDragStart = { position ->
                        val startPoint = currentPoint
                        dragViewportPoint = Offset(
                            startPoint.x - size.width / 2f + position.x,
                            startPoint.y - size.height / 2f + position.y
                        )
                    },
                    onDrag = { change, dragAmount ->
                        dragViewportPoint += dragAmount
                        val page = ReaderGeometry.viewportToPage(
                            layout,
                            ViewportPoint(dragViewportPoint.x, dragViewportPoint.y),
                            clampToPage = true
                        ) ?: return@detectDragGestures
                        policy.nearest(page)?.let { word ->
                            onSelectionChanged(currentSelection.withActiveEndpoint(endpoint).moveActiveTo(word))
                        }
                        change.consume()
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = HandleRadius.toPx()
            drawCircle(color, radius, center)
            drawCircle(handleOutline, radius, center, style = Stroke(HandleOutlineWidth.toPx()))
        }
    }
}

private fun Modifier.selectionLongPress(
    layout: ViewportLayout,
    policy: TextSelectionPolicy,
    onSelectionChanged: (TextSelection?) -> Unit
): Modifier = pointerInput(layout, policy) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        val pagePoint = ReaderGeometry.viewportToPage(
            layout,
            ViewportPoint(longPress.position.x, longPress.position.y)
        )
        onSelectionChanged(pagePoint?.let(policy::selectWord))

        do {
            val event = awaitPointerEvent()
            event.changes.forEach(PointerInputChange::consume)
        } while (event.changes.any { it.pressed })
    }
}

private fun Modifier.clearSelectionTap(
    layout: ViewportLayout,
    boxes: List<com.folium.reader.core.pdf.PageSpaceRect>,
    enabled: Boolean,
    onSelectionChanged: (TextSelection?) -> Unit
): Modifier = if (!enabled) this else pointerInput(layout, boxes) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var descendantConsumed = down.isConsumed
        var moved = false
        var up: PointerInputChange? = null
        do {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            descendantConsumed = descendantConsumed || event.changes.any { it.isConsumed }
            moved = moved || (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            if (!change.pressed) up = change
            if (event.changes.count { it.pressed } > 1) moved = true
        } while (up == null && event.changes.any { it.pressed })

        val release = up ?: return@awaitEachGesture
        val pagePoint = ReaderGeometry.viewportToPage(layout, ViewportPoint(release.position.x, release.position.y))
        val inside = pagePoint != null && boxes.any {
            pagePoint.x in it.left..it.right && pagePoint.y in it.top..it.bottom
        }
        if (!descendantConsumed && !moved && !inside) {
            release.consume()
            onSelectionChanged(null)
        }
    }
}
