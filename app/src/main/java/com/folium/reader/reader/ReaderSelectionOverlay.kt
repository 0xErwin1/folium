package com.folium.reader.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.folium.reader.R
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.text.SelectionEndpoint
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSelection
import com.folium.reader.core.text.TextSelectionPolicy
import kotlin.math.roundToInt

private val HandleTouchTarget = 48.dp
private val HandleRadius = 6.dp
private val CopyTouchTarget = 48.dp
private val CopyVisualSize = 40.dp
private val CopyIconSize = 24.dp
private val CopyGap = 8.dp
private val ViewportMargin = 8.dp

private val SelectionBlue = Color(0xFF1976D2)
private val SelectionFill = SelectionBlue.copy(alpha = .4f)

@Composable
internal fun ReaderSelectionOverlay(
    textPage: TextPage,
    layout: ViewportLayout,
    selection: TextSelection?,
    topOcclusionPx: Float?,
    onSelectionChanged: (TextSelection?) -> Unit,
    modifier: Modifier = Modifier
) {
    val policy = remember(textPage) { TextSelectionPolicy(textPage) }
    val selected = remember(policy, selection) { selection?.let(policy::selected) }
    var selectionGestureActive by remember { mutableStateOf(false) }
    var overlayTopInRootPx by remember { mutableStateOf(0f) }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayTopInRootPx = it.boundsInRoot().top }
            .testTag(ReaderTestTags.SELECTION_OVERLAY)
            .selectionLongPress(layout, policy, onSelectionChanged) { selectionGestureActive = it }
            .clearSelectionTap(layout, selected?.boxes.orEmpty(), selection != null, onSelectionChanged)
    ) {
        if (selected != null && selection != null) {
            val bands = remember(textPage, selection) { selectionBands(textPage, selection) }
            Canvas(Modifier.fillMaxSize().testTag(ReaderTestTags.SELECTION_HIGHLIGHT)) {
                bands.forEach { box ->
                    val rect = ReaderGeometry.destination(layout, box)
                    drawRect(
                        SelectionFill,
                        Offset(rect.left, rect.top),
                        Size(rect.width, rect.height)
                    )
                }
            }

            SelectionHandle(
                policy,
                layout,
                selection,
                SelectionEndpoint.ANCHOR,
                onSelectionChanged
            ) { selectionGestureActive = it }
            SelectionHandle(
                policy,
                layout,
                selection,
                SelectionEndpoint.FOCUS,
                onSelectionChanged
            ) { selectionGestureActive = it }

            if (!selectionGestureActive && topOcclusionPx != null) {
                SelectionCopyToolbar(
                    policy,
                    layout,
                    selection,
                    selected.text,
                    bands,
                    topOcclusionPx = (topOcclusionPx - overlayTopInRootPx).coerceAtLeast(0f)
                )
            }
        }
    }
}

internal data class SelectionToolbarPlacement(val left: Float, val top: Float)

/** Places the contextual action near the active endpoint without covering selected content. */
internal fun selectionToolbarPlacement(
    viewportWidth: Float,
    viewportHeight: Float,
    anchor: ViewportPoint,
    activeBand: ViewportRect,
    selectedBands: List<ViewportRect>,
    toolbarSize: Float,
    gap: Float,
    margin: Float,
    minimumTop: Float = margin
): SelectionToolbarPlacement? {
    val maxLeft = viewportWidth - margin - toolbarSize
    val maxTop = viewportHeight - margin - toolbarSize
    val minTop = maxOf(margin, minimumTop)
    if (maxLeft < margin || maxTop < minTop) return null

    val preferredLeft = (anchor.x - toolbarSize / 2f).coerceIn(margin, maxLeft)
    val above = activeBand.top - gap - toolbarSize
    val below = activeBand.top + activeBand.height + gap
    val preferredVerticalCandidates = listOf(above, below).filter { it in minTop..maxTop }
    val fallbackVerticalCandidates = listOf(above, below) + selectedBands.flatMap {
        listOf(it.top - gap - toolbarSize, it.top + it.height + gap)
    } + listOf(minTop, maxTop)
    val verticalCandidates = preferredVerticalCandidates + fallbackVerticalCandidates
    val horizontalCandidates = listOf(preferredLeft) + selectedBands.flatMap {
        listOf(it.left - gap - toolbarSize, it.left + it.width + gap)
    } + listOf(margin, maxLeft)

    verticalCandidates.distinct().forEach { candidateTop ->
        val top = candidateTop.coerceIn(minTop, maxTop)
        horizontalCandidates.distinct().forEach { candidateLeft ->
            val left = candidateLeft.coerceIn(margin, maxLeft)
            val intersectsSelection = selectedBands.any {
                rectanglesIntersect(left, top, toolbarSize, toolbarSize, it)
            }
            if (!intersectsSelection) return SelectionToolbarPlacement(left, top)
        }
    }

    return null
}

private fun rectanglesIntersect(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    other: ViewportRect
): Boolean = left < other.left + other.width &&
    left + width > other.left &&
    top < other.top + other.height &&
    top + height > other.top

@Composable
private fun SelectionCopyToolbar(
    policy: TextSelectionPolicy,
    layout: ViewportLayout,
    selection: TextSelection,
    selectedText: String,
    bands: List<PageSpaceRect>,
    topOcclusionPx: Float
) {
    val endpoint = selection.activeEndpoint
    val endpointBox = policy.endpointBox(selection, endpoint) ?: return
    val isLeading = when (endpoint) {
        SelectionEndpoint.ANCHOR -> selection.anchorWord <= selection.focusWord
        SelectionEndpoint.FOCUS -> selection.focusWord < selection.anchorWord
    }
    val anchor = ReaderGeometry.pageToViewport(
        layout,
        PageSpacePoint(if (isLeading) endpointBox.left else endpointBox.right, endpointBox.bottom)
    )
    val activeBand = ReaderGeometry.destination(layout, endpointBox)
    val selectedViewportBands = bands.map { ReaderGeometry.destination(layout, it) }
    val clipboard = LocalClipboardManager.current
    val description = stringResource(R.string.reader_selection_copy)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val placement = with(density) {
        val gapPx = CopyGap.toPx()
        val marginPx = ViewportMargin.toPx()
        selectionToolbarPlacement(
            viewportWidth = layout.viewport.widthPx.toFloat(),
            viewportHeight = layout.viewport.heightPx.toFloat(),
            anchor = anchor,
            activeBand = activeBand,
            selectedBands = selectedViewportBands,
            toolbarSize = CopyTouchTarget.toPx(),
            gap = gapPx,
            margin = marginPx,
            minimumTop = maxOf(marginPx, topOcclusionPx + gapPx)
        )
    } ?: return
    val copy = { clipboard.setText(AnnotatedString(selectedText)) }
    val containerColor = MaterialTheme.colorScheme.primary
    val iconColor = MaterialTheme.colorScheme.onPrimary

    Box(
        Modifier
            .offset { IntOffset(placement.left.roundToInt(), placement.top.roundToInt()) }
            .size(CopyTouchTarget)
            .semantics {
                contentDescription = description
                role = Role.Button
                customActions = listOf(CustomAccessibilityAction(description) { copy(); true })
            }
            .clickable(onClick = copy)
            .testTag(ReaderTestTags.SELECTION_COPY),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Canvas(
            Modifier
                .size(CopyVisualSize)
                .background(containerColor, CircleShape)
        ) {
            val iconSize = CopyIconSize.toPx()
            val iconOrigin = Offset((size.width - iconSize) / 2f, (size.height - iconSize) / 2f)
            val sheetSize = Size(iconSize * .62f, iconSize * .72f)
            val stroke = Stroke(2.2.dp.toPx())

            drawRoundRect(
                color = iconColor,
                topLeft = iconOrigin + Offset(iconSize * .25f, iconSize * .08f),
                size = sheetSize,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                style = stroke
            )
            drawRoundRect(
                color = iconColor,
                topLeft = iconOrigin + Offset(iconSize * .08f, iconSize * .25f),
                size = sheetSize,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                style = stroke
            )
        }
    }
}

/** Produces one continuous selected band per original text line. */
internal fun selectionBands(
    textPage: TextPage,
    selection: TextSelection
): List<PageSpaceRect> {
    data class LocatedBox(val block: Int, val line: Int, val box: PageSpaceRect)

    val boxes = textPage.blocks.flatMapIndexed { blockIndex, block ->
        block.lines.flatMapIndexed { lineIndex, line ->
            line.words.map { LocatedBox(blockIndex, lineIndex, it.box) }
        }
    }
    if (selection.firstWord !in boxes.indices || selection.lastWord !in boxes.indices) return emptyList()

    val bands = mutableListOf<PageSpaceRect>()
    var currentLocation: LocatedBox? = null
    boxes.subList(selection.firstWord, selection.lastWord + 1).forEach { next ->
        val current = currentLocation
        val currentBand = bands.lastOrNull()
        val sameLine = current != null && current.block == next.block && current.line == next.line

        if (sameLine) {
            bands[bands.lastIndex] = PageSpaceRect(
                left = minOf(currentBand!!.left, next.box.left),
                top = minOf(currentBand.top, next.box.top),
                right = maxOf(currentBand.right, next.box.right),
                bottom = maxOf(currentBand.bottom, next.box.bottom)
            )
        } else {
            bands += next.box
        }
        currentLocation = next
    }
    return bands
}

@Composable
private fun SelectionHandle(
    policy: TextSelectionPolicy,
    layout: ViewportLayout,
    selection: TextSelection,
    endpoint: SelectionEndpoint,
    onSelectionChanged: (TextSelection?) -> Unit,
    onGestureActive: (Boolean) -> Unit = {}
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
                        onGestureActive(true)
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
                    },
                    onDragEnd = { onGestureActive(false) },
                    onDragCancel = { onGestureActive(false) }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(SelectionBlue, HandleRadius.toPx(), center)
        }
    }
}

private fun Modifier.selectionLongPress(
    layout: ViewportLayout,
    policy: TextSelectionPolicy,
    onSelectionChanged: (TextSelection?) -> Unit,
    onGestureActive: (Boolean) -> Unit
): Modifier = pointerInput(layout, policy) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        val pagePoint = ReaderGeometry.viewportToPage(
            layout,
            ViewportPoint(longPress.position.x, longPress.position.y)
        )
        val initialSelection = pagePoint?.let(policy::selectWord)
        onSelectionChanged(initialSelection)
        longPress.consume()

        if (initialSelection != null) onGestureActive(true)
        try {
            do {
                val event = awaitPointerEvent()
                val originalPointer = event.changes.firstOrNull { it.id == down.id }
                if (initialSelection != null && originalPointer?.pressed == true) {
                    val movedPagePoint = ReaderGeometry.viewportToPage(
                        layout,
                        ViewportPoint(originalPointer.position.x, originalPointer.position.y),
                        clampToPage = true
                    )
                    movedPagePoint?.let(policy::nearest)?.let { word ->
                        onSelectionChanged(
                            initialSelection
                                .withActiveEndpoint(SelectionEndpoint.FOCUS)
                                .moveActiveTo(word)
                        )
                    }
                }
                event.changes.forEach(PointerInputChange::consume)
            } while (event.changes.any { it.pressed })
        } finally {
            if (initialSelection != null) onGestureActive(false)
        }
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
