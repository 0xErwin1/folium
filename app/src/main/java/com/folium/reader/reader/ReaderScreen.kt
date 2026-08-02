package com.folium.reader.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.folium.reader.R
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.MIN_ZOOM_SCALE
import com.folium.reader.core.pdf.PageSpacePoint
import kotlin.math.roundToInt

object ReaderTestTags {
    const val SCREEN = "reader-screen"
    const val PAGER = "reader-pager"
    const val CHROME_TOP = "reader-chrome-top"
    const val CHROME_BOTTOM = "reader-chrome-bottom"
    const val BACK = "reader-back"
    const val PREVIOUS = "reader-previous"
    const val NEXT = "reader-next"
    const val RESET_ZOOM = "reader-reset-zoom"
    const val POSITION = "reader-position"

    fun page(pageIndex: Int): String = "reader-page/$pageIndex"
    fun pageContent(pageIndex: Int): String = "reader-page-content/$pageIndex"
    fun pageFailure(pageIndex: Int): String = "reader-page-failure/$pageIndex"
}

private val TouchTarget = 48.dp
private const val EDGE_TAP_FRACTION = 0.25f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * The horizontal reading surface.
 *
 * Stateless by design, exactly like the library screen: everything it shows arrives as a
 * [ReaderUiState], so every state it can reach — a page still rendering, a page that refused to
 * render, a page mid-refinement — can be exercised directly.
 *
 * Pages are drawn full bleed and the chrome floats over them, so showing the controls never changes
 * how much of a page is on screen. The presentation is deliberately flat and still: outlines rather
 * than shadows, no crossfades, and no movement the reader did not ask for by dragging something,
 * which is what keeps it legible on an e-ink panel as well as on a backlit one.
 */
@Composable
fun ReaderScreen(
    title: String,
    state: ReaderUiState<BorrowedPage>,
    pageAspect: (Int) -> Float,
    onIntent: (GestureIntent) -> Unit,
    onViewportChanged: (ReaderViewport?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize().testTag(ReaderTestTags.SCREEN),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(Modifier.fillMaxSize()) {
            PageSurface(state, pageAspect, onIntent, onViewportChanged)

            if (state.state.chromeVisible) {
                TopChrome(title, onBack, Modifier.align(Alignment.TopCenter))
                BottomChrome(state, onIntent, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun PageSurface(
    state: ReaderUiState<BorrowedPage>,
    pageAspect: (Int) -> Float,
    onIntent: (GestureIntent) -> Unit,
    onViewportChanged: (ReaderViewport?) -> Unit
) {
    val pager = rememberPagerState(initialPage = state.state.currentPage) { state.state.pageCount }
    val zoomed = state.state.zoom.scale > MIN_ZOOM_SCALE
    val currentPage = state.state.currentPage

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { onIntent(GestureIntent.FlingToPage(it)) }
    }
    LaunchedEffect(currentPage) {
        if (pager.currentPage != currentPage) pager.scrollToPage(currentPage)
    }

    HorizontalPager(
        state = pager,
        userScrollEnabled = !zoomed,
        beyondViewportPageCount = 1,
        modifier = Modifier
            .fillMaxSize()
            .testTag(ReaderTestTags.PAGER)
            .onSizeChanged { onViewportChanged(ReaderViewport.of(it.width, it.height)) }
            .transformGestures(zoomed, onIntent)
            .tapGestures(zoomed, onIntent)
    ) { pageIndex ->
        PageContent(pageIndex, state, pageAspect)
    }
}

/**
 * While the page is unzoomed the pager owns horizontal dragging, so this must not take a
 * single-finger drag away from it: it stays out of the way until a second finger is down, and only
 * then starts consuming. Once zoomed the pager is not scrolling at all, so the ordinary transform
 * detector takes over and handles dragging the page under the viewport as well as pinching.
 */
private fun Modifier.transformGestures(zoomed: Boolean, onIntent: (GestureIntent) -> Unit): Modifier =
    pointerInput(zoomed) {
        if (zoomed) {
            detectTransformGestures(panZoomLock = true) { centroid, pan, gestureZoom, _ ->
                if (gestureZoom != 1f) onIntent(zoomIntent(centroid, gestureZoom))
                else onIntent(GestureIntent.PanBy(pan.x / size.width, pan.y / size.height))
            }
        } else {
            detectPinchOnly { centroid, gestureZoom -> onIntent(zoomIntent(centroid, gestureZoom)) }
        }
    }

private suspend fun PointerInputScope.detectPinchOnly(onZoom: (Offset, Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var pinching = false

        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break

            if (event.changes.count { it.pressed } >= 2) pinching = true
            if (!pinching) continue

            val gestureZoom = event.calculateZoom()
            val centroid = event.calculateCentroid(useCurrent = true)
            if (gestureZoom != 1f && centroid != Offset.Unspecified) onZoom(centroid, gestureZoom)
            event.changes.forEach { if (it.pressed) it.consume() }
        }
    }
}

private fun PointerInputScope.zoomIntent(centroid: Offset, gestureZoom: Float) = GestureIntent.ZoomBy(
    factor = gestureZoom,
    focal = PageSpacePoint(
        (centroid.x / size.width).coerceIn(0f, 1f),
        (centroid.y / size.height).coerceIn(0f, 1f)
    )
)

/**
 * Tapping the outer quarter of either edge turns the page and tapping the middle shows or hides the
 * chrome, so navigation stays reachable one-handed without any control being on screen. While
 * zoomed the edges lose that meaning, since a tap there is far more likely to be aimed at the page.
 */
private fun Modifier.tapGestures(zoomed: Boolean, onIntent: (GestureIntent) -> Unit): Modifier =
    pointerInput(zoomed) {
        detectTapGestures(
            onDoubleTap = { position ->
                if (zoomed) onIntent(GestureIntent.ResetZoom)
                else onIntent(zoomIntent(position, DOUBLE_TAP_ZOOM))
            },
            onTap = { position ->
                val horizontal = position.x / size.width
                when {
                    zoomed -> onIntent(GestureIntent.ToggleChrome)
                    horizontal < EDGE_TAP_FRACTION -> onIntent(GestureIntent.PageBack)
                    horizontal > 1f - EDGE_TAP_FRACTION -> onIntent(GestureIntent.PageForward)
                    else -> onIntent(GestureIntent.ToggleChrome)
                }
            }
        )
    }

/**
 * Draws whatever raster this page currently has, placed by the region it covers rather than by the
 * viewport it was requested for. A raster from before a zoom therefore stays exactly over the
 * content it belongs to, merely soft, until the sharper one for the same page replaces it in place.
 */
@Composable
private fun PageContent(
    pageIndex: Int,
    state: ReaderUiState<BorrowedPage>,
    pageAspect: (Int) -> Float
) {
    val page = state.pages[pageIndex]
    val image = remember(page) { page?.bitmap?.asImageBitmap() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag(ReaderTestTags.page(pageIndex)),
        contentAlignment = Alignment.Center
    ) {
        when {
            page != null && image != null -> Canvas(
                Modifier.fillMaxSize().testTag(ReaderTestTags.pageContent(pageIndex))
            ) {
                val viewport = ReaderViewport.of(size.width.roundToInt(), size.height.roundToInt())
                    ?: return@Canvas
                val layout = ReaderGeometry.layout(viewport, pageAspect(pageIndex), state.state.zoom)
                val destination = ReaderGeometry.destination(layout, page.region)

                drawImage(
                    image = image,
                    dstOffset = IntOffset(destination.left.roundToInt(), destination.top.roundToInt()),
                    dstSize = IntSize(
                        destination.width.roundToInt().coerceAtLeast(1),
                        destination.height.roundToInt().coerceAtLeast(1)
                    ),
                    filterQuality = FilterQuality.Medium
                )
            }

            pageIndex in state.failedPages -> Text(
                text = stringResource(R.string.reader_page_failed, pageIndex + 1),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(ReaderTestTags.pageFailure(pageIndex))
            )

            else -> Text(
                text = stringResource(R.string.reader_page_loading, pageIndex + 1),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TopChrome(title: String, onBack: () -> Unit, modifier: Modifier) {
    ChromeBar(
        modifier = modifier.testTag(ReaderTestTags.CHROME_TOP),
        insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        dividerBelow = true
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.sizeIn(minHeight = TouchTarget).testTag(ReaderTestTags.BACK)
        ) {
            Text(stringResource(R.string.reader_back))
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp, end = 16.dp)
        )
    }
}

@Composable
private fun BottomChrome(
    state: ReaderUiState<BorrowedPage>,
    onIntent: (GestureIntent) -> Unit,
    modifier: Modifier
) {
    val position = state.state
    val zoomLabel = stringResource(R.string.reader_zoom_level, (position.zoom.scale * 100).roundToInt())

    ChromeBar(
        modifier = modifier.testTag(ReaderTestTags.CHROME_BOTTOM),
        insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        dividerBelow = false
    ) {
        TextButton(
            onClick = { onIntent(GestureIntent.PageBack) },
            enabled = position.currentPage > 0,
            modifier = Modifier.sizeIn(minHeight = TouchTarget).testTag(ReaderTestTags.PREVIOUS)
        ) {
            Text(stringResource(R.string.reader_previous_page))
        }

        Text(
            text = stringResource(R.string.reader_page_position, position.currentPage + 1, position.pageCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(ReaderTestTags.POSITION)
        )

        TextButton(
            onClick = { onIntent(GestureIntent.PageForward) },
            enabled = position.currentPage < position.pageCount - 1,
            modifier = Modifier.sizeIn(minHeight = TouchTarget).testTag(ReaderTestTags.NEXT)
        ) {
            Text(stringResource(R.string.reader_next_page))
        }

        TextButton(
            onClick = { onIntent(GestureIntent.ResetZoom) },
            enabled = position.zoom.scale > MIN_ZOOM_SCALE,
            modifier = Modifier
                .sizeIn(minHeight = TouchTarget)
                .semantics { contentDescription = zoomLabel }
                .testTag(ReaderTestTags.RESET_ZOOM)
        ) {
            Text(stringResource(R.string.reader_reset_zoom))
        }
    }
}

/**
 * Controls sit against the top and bottom edges so both ends stay within one-handed reach on a
 * phone, and are separated from the page by a hairline rather than by elevation.
 */
@Composable
private fun ChromeBar(
    modifier: Modifier,
    insets: WindowInsets,
    dividerBelow: Boolean,
    content: @Composable RowScope.() -> Unit
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column {
            if (!dividerBelow) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(insets)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .heightIn(min = TouchTarget),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                content = content
            )

            if (dividerBelow) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
