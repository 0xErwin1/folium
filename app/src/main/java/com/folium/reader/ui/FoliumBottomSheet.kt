@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.folium.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.folium.reader.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The three resting positions a [FoliumBottomSheet] can settle at. */
enum class FoliumSheetAnchor { HIDDEN, HALF, EXPANDED }

/**
 * Decides which anchor a released drag settles at, from the anchor the drag started at, the offset
 * it ended on and the velocity it ended with.
 *
 * The anchors are always considered in their natural top-to-bottom order —
 * [FoliumSheetAnchor.EXPANDED], [FoliumSheetAnchor.HALF], [FoliumSheetAnchor.HIDDEN] — and a single
 * release only ever settles on the anchor the drag started at or one of its immediate neighbours.
 * That is what keeps a long, slow drag away from [FoliumSheetAnchor.EXPANDED] from skipping past
 * [FoliumSheetAnchor.HALF] in one release: dismissing an expanded sheet always takes a release at
 * [FoliumSheetAnchor.HALF] first, then a second one.
 *
 * A release fast enough to clear [velocityThresholdPxPerSecond] moves toward whichever neighbour its
 * direction points at regardless of how little distance the drag covered, the same way a fling wins
 * over a hover in any other gesture. E-ink or reduced-motion settings never change the anchor this
 * function returns, only how the caller reaches it.
 */
object FoliumSheetSettle {

    private val ORDER = listOf(FoliumSheetAnchor.EXPANDED, FoliumSheetAnchor.HALF, FoliumSheetAnchor.HIDDEN)

    fun target(
        current: FoliumSheetAnchor,
        anchors: Map<FoliumSheetAnchor, Float>,
        offset: Float,
        velocity: Float,
        velocityThresholdPxPerSecond: Float
    ): FoliumSheetAnchor {
        val order = ORDER.filter { anchors.containsKey(it) }
        val currentIndex = order.indexOf(current)
        if (currentIndex < 0) return current

        val above = order.getOrNull(currentIndex - 1)
        val below = order.getOrNull(currentIndex + 1)

        if (abs(velocity) >= velocityThresholdPxPerSecond) {
            return if (velocity > 0f) below ?: current else above ?: current
        }

        val candidates = listOfNotNull(above, current, below)
        return candidates.minByOrNull { anchor -> abs(anchors.getValue(anchor) - offset) } ?: current
    }
}

private val VelocityThreshold = 500.dp
private val ScrimColor = Color.Black.copy(alpha = 0.32f)
private val HandleWidth = 44.dp
private val HandleHeight = 3.dp
private val TopBorderWidth = 2.dp
private val HandleTopPadding = 10.dp
private val HandleToHeaderGap = 2.dp
private val ContentHorizontalPadding = FoliumSpacing.xxl
private val FooterBottomPadding = FoliumSpacing.l

/**
 * The bottom sheet every book-level control surface in the reader is built on: a draggable panel
 * with a handle pinned above a scrolling body and an optional pinned footer, following the finger
 * while a drag is active and settling on release through [FoliumSheetSettle] rather than through
 * [AnchoredDraggableState]'s own built-in settle, whose nearest-anchor search does not guarantee the
 * "never more than one anchor per release" rule this sheet depends on. The drag itself, its anchor
 * bookkeeping, and reaching a settled anchor still run on [AnchoredDraggableState] and its
 * [androidx.compose.foundation.gestures.animateTo]/[androidx.compose.foundation.gestures.snapTo].
 *
 * [reducedMotion] is for an e-ink appearance: the sheet still follows the finger during an active
 * drag, but every settle — a release, the handle's tap-to-toggle, entering and leaving — snaps
 * straight to its target anchor instead of animating, and the scrim sits at its resting alpha instead
 * of fading, because a slow redraw ghosts on every intermediate frame an electronic-paper display has
 * to settle.
 *
 * [paneTitle] names the sheet for TalkBack. The system back gesture always dismisses the sheet
 * outright rather than stepping it back to [FoliumSheetAnchor.HALF] first.
 *
 * The sheet is bordered only on its top edge — the edge that actually separates it from what is
 * behind it, since its other three sides already meet the window's own bounds — and holds square
 * corners like every other surface in the design system (see [FoliumShapes]). [header] renders
 * directly below the handle, pinned above the scrolling body, and is handed the sheet's own current
 * anchor and a toggle between [FoliumSheetAnchor.HALF] and [FoliumSheetAnchor.EXPANDED] so a caller
 * can draw its own expand/collapse affordance without reaching into the sheet's internal state.
 */
@Composable
fun FoliumBottomSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    paneTitle: String,
    handleContentDescription: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    reducedMotion: Boolean = false,
    initialAnchor: FoliumSheetAnchor = FoliumSheetAnchor.HALF,
    halfHeightFraction: Float = 0.52f,
    expandedHeightFraction: Float = 0.92f,
    header: (@Composable (anchor: FoliumSheetAnchor, onToggle: () -> Unit) -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var sheetHeightPx by remember { mutableFloatStateOf(0f) }
    var composed by remember { mutableStateOf(visible) }

    val hiddenOffset = sheetHeightPx
    val halfOffset = sheetHeightPx * (1f - halfHeightFraction / expandedHeightFraction)
    val expandedOffset = 0f

    val anchoredState = remember {
        AnchoredDraggableState(
            initialValue = FoliumSheetAnchor.HIDDEN,
            positionalThreshold = { distance -> distance * 0.5f },
            velocityThreshold = { with(density) { VelocityThreshold.toPx() } },
            snapAnimationSpec = tween(durationMillis = 220),
            decayAnimationSpec = splineBasedDecay(density)
        )
    }

    LaunchedEffect(sheetHeightPx) {
        if (sheetHeightPx <= 0f) return@LaunchedEffect
        anchoredState.updateAnchors(
            DraggableAnchors {
                FoliumSheetAnchor.EXPANDED at expandedOffset
                FoliumSheetAnchor.HALF at halfOffset
                FoliumSheetAnchor.HIDDEN at hiddenOffset
            }
        )
    }

    LaunchedEffect(visible, sheetHeightPx) {
        if (sheetHeightPx <= 0f) return@LaunchedEffect
        if (visible) {
            composed = true
            if (reducedMotion) anchoredState.snapTo(initialAnchor) else anchoredState.animateTo(initialAnchor)
        } else {
            if (reducedMotion) anchoredState.snapTo(FoliumSheetAnchor.HIDDEN) else anchoredState.animateTo(FoliumSheetAnchor.HIDDEN)
            composed = false
        }
    }

    if (!composed) return

    BackHandler { onDismissRequest() }

    val velocityThresholdPx = with(density) { VelocityThreshold.toPx() }

    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)

    /**
     * A sheet that a gesture has put away is only out of sight: whoever shows it still believes it
     * is open, so the scrim stays up over the screen and keeps taking every touch. Reaching the
     * hidden anchor from inside the sheet is therefore reported as a dismissal.
     */
    fun reportDismissalIfHidden() {
        if (anchoredState.currentValue == FoliumSheetAnchor.HIDDEN) currentOnDismissRequest()
    }

    fun settleTo(target: FoliumSheetAnchor) {
        scope.launch {
            if (reducedMotion) anchoredState.snapTo(target) else anchoredState.animateTo(target)
            reportDismissalIfHidden()
        }
    }

    fun settleFromDrag(velocity: Float) {
        scope.launch {
            settleAnchoredDraggableState(anchoredState, velocity, velocityThresholdPx, reducedMotion)
            reportDismissalIfHidden()
        }
    }

    val nestedScrollConnection = remember(anchoredState) {
        sheetNestedScrollConnection(anchoredState) { velocity -> settleFromDrag(velocity) }
    }

    Box(modifier.fillMaxSize()) {
        val progress = if (hiddenOffset > 0f) (1f - anchoredState.offsetOrZero() / hiddenOffset).coerceIn(0f, 1f) else 0f
        val scrimAlpha = if (reducedMotion) 1f else progress
        val onSurfaceColor = MaterialTheme.colorScheme.onSurface
        val topBorderPx = with(density) { TopBorderWidth.toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .background(ScrimColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest
                )
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(expandedHeightFraction)
                .offset { IntOffset(0, anchoredState.offsetOrZero().roundToInt()) }
                .onSizeChanged { sheetHeightPx = it.height.toFloat() }
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .semantics { this.paneTitle = paneTitle }
                .drawBehind {
                    drawLine(
                        color = onSurfaceColor,
                        start = Offset.Zero,
                        end = Offset(size.width, 0f),
                        strokeWidth = topBorderPx
                    )
                },
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier
                    .visibleHeightOnly { anchoredState.offsetOrZero() }
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            ) {
                Column(Modifier.padding(top = HandleTopPadding), verticalArrangement = Arrangement.spacedBy(HandleToHeaderGap)) {
                    SheetHandle(
                        contentDescription = handleContentDescription,
                        onToggle = {
                            settleTo(if (anchoredState.currentValue == FoliumSheetAnchor.EXPANDED) FoliumSheetAnchor.HALF else FoliumSheetAnchor.EXPANDED)
                        },
                        onExpand = { settleTo(FoliumSheetAnchor.EXPANDED) },
                        onCollapse = { settleTo(FoliumSheetAnchor.HALF) },
                        onDismiss = onDismissRequest,
                        onDrag = { delta -> anchoredState.dispatchRawDelta(delta) },
                        onDragStopped = ::settleFromDrag
                    )

                    if (header != null) {
                        Box(Modifier.padding(horizontal = ContentHorizontalPadding)) {
                            header(anchoredState.currentValue) {
                                settleTo(if (anchoredState.currentValue == FoliumSheetAnchor.EXPANDED) FoliumSheetAnchor.HALF else FoliumSheetAnchor.EXPANDED)
                            }
                        }
                    }
                }

                Box(Modifier.weight(1f, fill = false).nestedScroll(nestedScrollConnection)) {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = ContentHorizontalPadding)
                    ) {
                        content()
                        Spacer(Modifier.height(FoliumSpacing.m))
                    }
                }

                if (footer != null) {
                    Column(
                        Modifier
                            .padding(horizontal = ContentHorizontalPadding)
                            .drawBehind {
                                drawLine(
                                    color = onSurfaceColor,
                                    start = Offset.Zero,
                                    end = Offset(size.width, 0f),
                                    strokeWidth = with(density) { 1.dp.toPx() }
                                )
                            }
                            .padding(top = FoliumSpacing.s, bottom = FooterBottomPadding)
                    ) {
                        footer()
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHandle(
    contentDescription: String,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragStopped: (Float) -> Unit
) {
    val expandDescription = stringResource(R.string.foliumsheet_action_expand)
    val collapseDescription = stringResource(R.string.foliumsheet_action_collapse)
    val dismissDescription = stringResource(R.string.foliumsheet_action_dismiss)

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = FoliumSpacing.touchTarget)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> onDrag(delta) },
                onDragStopped = { velocity -> onDragStopped(velocity) }
            )
            .clickable(onClick = onToggle)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                customActions = listOf(
                    CustomAccessibilityAction(expandDescription) { onExpand(); true },
                    CustomAccessibilityAction(collapseDescription) { onCollapse(); true },
                    CustomAccessibilityAction(dismissDescription) { onDismiss(); true }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Spacer(
            Modifier
                .size(width = HandleWidth, height = HandleHeight)
                .background(MaterialTheme.colorScheme.onSurface)
        )
    }
}

/**
 * Lays the sheet's content out in the part of the sheet that is on screen. The sheet is as tall as
 * its expanded anchor and slides down by [offsetPx] for the others, so content laid out at the full
 * height keeps its pinned footer below the bottom of the screen at every anchor but the expanded
 * one. The offset is read during layout, so following the finger does not recompose the content.
 */
private fun Modifier.visibleHeightOnly(offsetPx: () -> Float): Modifier = layout { measurable, constraints ->
    val visibleHeight = (constraints.maxHeight - offsetPx().roundToInt()).coerceIn(0, constraints.maxHeight)
    val placeable = measurable.measure(
        constraints.copy(minHeight = minOf(constraints.minHeight, visibleHeight), maxHeight = visibleHeight)
    )

    layout(placeable.width, constraints.maxHeight) { placeable.place(0, 0) }
}

/** [AnchoredDraggableState.offset] is `NaN` until the first anchor set lands; zero reads as "at rest". */
private fun AnchoredDraggableState<FoliumSheetAnchor>.offsetOrZero(): Float =
    if (offset.isNaN()) 0f else offset

/**
 * Runs [FoliumSheetSettle] against [state]'s live offset and velocity, then reaches the winning
 * anchor by animation, or immediately when [reducedMotion] asks for it.
 */
private suspend fun settleAnchoredDraggableState(
    state: AnchoredDraggableState<FoliumSheetAnchor>,
    velocity: Float,
    velocityThresholdPx: Float,
    reducedMotion: Boolean
) {
    val anchors = FoliumSheetAnchor.entries.filter { state.anchors.hasAnchorFor(it) }
        .associateWith { state.anchors.positionOf(it) }
    val target = FoliumSheetSettle.target(state.currentValue, anchors, state.offsetOrZero(), velocity, velocityThresholdPx)

    if (reducedMotion) state.snapTo(target) else state.animateTo(target)
}

/**
 * Hands a downward drag on content already scrolled to its top to the sheet instead of dropping it,
 * and an upward drag on content while the sheet is not fully expanded to the sheet first. A fling
 * that starts while the sheet is anywhere but [FoliumSheetAnchor.EXPANDED] settles the sheet instead
 * of flinging the list beneath it.
 */
private fun sheetNestedScrollConnection(
    state: AnchoredDraggableState<FoliumSheetAnchor>,
    settle: (Float) -> Unit
): NestedScrollConnection = object : NestedScrollConnection {

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        val minOffset = if (state.anchors.size > 0) state.anchors.minAnchor() else 0f
        return if (delta < 0f && state.offsetOrZero() > minOffset) {
            Offset(0f, state.dispatchRawDelta(delta))
        } else {
            Offset.Zero
        }
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        val maxOffset = if (state.anchors.size > 0) state.anchors.maxAnchor() else 0f
        return if (delta > 0f && state.offsetOrZero() < maxOffset) {
            Offset(0f, state.dispatchRawDelta(delta))
        } else {
            Offset.Zero
        }
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (state.currentValue == FoliumSheetAnchor.EXPANDED && available.y < 0f) return Velocity.Zero
        settle(available.y)
        return available
    }
}
