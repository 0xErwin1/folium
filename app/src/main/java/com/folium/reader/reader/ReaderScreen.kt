package com.folium.reader.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.folium.reader.R
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.MIN_ZOOM_SCALE
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageFitMode
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.flattenOutline
import com.folium.reader.core.pdf.normalizeFlatNumberedChapters
import kotlin.math.roundToInt

object ReaderTestTags {
    const val SCREEN = "reader-screen"
    const val PAGER = "reader-pager"
    const val CHROME_TOP = "reader-chrome-top"
    const val CHROME_BOTTOM = "reader-chrome-bottom"
    const val BACK = "reader-back"
    const val PREVIOUS = "reader-previous"
    const val NEXT = "reader-next"
    const val OVERFLOW = "reader-overflow"
    const val FIT_WIDTH = "reader-fit-width"
    const val FIT_PAGE = "reader-fit-page"
    const val ZOOM = "reader-zoom"
    const val POSITION = "reader-position"
    const val JUMP_DIALOG = "reader-jump-dialog"
    const val JUMP_INPUT = "reader-jump-input"
    const val JUMP_CONFIRM = "reader-jump-confirm"
    const val CONTENTS = "reader-contents"
    const val CONTENTS_SHEET = "reader-contents-sheet"
    const val CONTENTS_CLOSE = "reader-contents-close"

    fun page(pageIndex: Int): String = "reader-page/$pageIndex"
    fun pageContent(pageIndex: Int): String = "reader-page-content/$pageIndex"
    fun pageFailure(pageIndex: Int): String = "reader-page-failure/$pageIndex"
    fun contentsRow(index: Int): String = "reader-contents-row/$index"
    fun contentsTitle(index: Int): String = "reader-contents-title/$index"
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
 *
 * Both ways of going somewhere by name — a page number, and the document's own contents — leave
 * through the same door as an ordinary page turn: they dispatch [GestureIntent.FlingToPage] and let
 * the state that comes back move the pager. Neither surface holds a page of its own, so neither can
 * disagree with where the reader actually is. An [outline] that is empty is a document with no table
 * of contents, and the menu item for it is simply absent.
 */
@Composable
fun ReaderScreen(
    title: String,
    state: ReaderUiState<BorrowedPage>,
    pageAspect: (Int) -> Float,
    onIntent: (GestureIntent) -> Unit,
    onViewportChanged: (ReaderViewport?) -> Unit,
    onBack: () -> Unit,
    outline: List<OutlineEntry> = emptyList(),
    modifier: Modifier = Modifier
) {
    var jumpOpen by remember { mutableStateOf(false) }
    var contentsOpen by remember { mutableStateOf(false) }
    val contentsRows = remember(outline) { flattenOutline(normalizeFlatNumberedChapters(outline)) }

    Surface(
        modifier = modifier.fillMaxSize().testTag(ReaderTestTags.SCREEN),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        ImmersiveSystemBars(hidden = !state.state.chromeVisible)

        Box(Modifier.fillMaxSize()) {
            PageSurface(state, pageAspect, onIntent, onViewportChanged)

            if (state.state.chromeVisible) {
                TopChrome(
                    title = title,
                    zoomScale = state.state.zoom.scale,
                    fitMode = state.state.fitMode,
                    contentsAvailable = contentsRows.isNotEmpty(),
                    onIntent = onIntent,
                    onContentsRequested = { contentsOpen = true },
                    onBack = onBack,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                BottomChrome(
                    currentPage = state.state.currentPage,
                    pageCount = state.state.pageCount,
                    onIntent = onIntent,
                    onJumpRequested = { jumpOpen = true },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            if (jumpOpen) {
                JumpToPageDialog(
                    pageCount = state.state.pageCount,
                    currentPage = state.state.currentPage,
                    onDismiss = { jumpOpen = false },
                    onJump = { pageIndex ->
                        jumpOpen = false
                        onIntent(GestureIntent.FlingToPage(pageIndex))
                    }
                )
            }

            if (contentsOpen) {
                ContentsSheet(
                    rows = contentsRows,
                    currentPage = state.state.currentPage,
                    onSelect = { pageIndex ->
                        contentsOpen = false
                        onIntent(GestureIntent.FlingToPage(pageIndex))
                    },
                    onDismiss = { contentsOpen = false }
                )
            }
        }
    }
}

/**
 * Hiding the reader's own bars while leaving the system's in place would not be immersive at all,
 * so both go together. The bars stay gone until the reader asks for them back rather than
 * reappearing at the end of a gesture, which is the whole point of hiding them to read; a swipe
 * from an edge still summons them transiently, since that is how a reader gets out of an app whose
 * chrome they cannot see.
 */
@Composable
private fun ImmersiveSystemBars(hidden: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return

    val window = remember(view) { view.context.activity()?.window } ?: return

    DisposableEffect(window, view, hidden) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())

        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
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
 * One detector for the whole gesture, whatever the page does under it.
 *
 * This deliberately does not key its [pointerInput] on whether the page is zoomed. A pinch that
 * starts at the fitted scale crosses into being zoomed part-way through, and keying on that would
 * tear the detector down and rebuild it mid-pinch — which reaches the reader as the gesture dying
 * under their fingers, to be started again from whatever scale it had already reached. The zoom
 * state is therefore read through [rememberUpdatedState] instead, so it can change without
 * interrupting anything, and a single pinch scales continuously from wherever it began.
 *
 * While the page is fitted the pager owns horizontal dragging, so nothing is consumed until a
 * second finger is down. Once a second finger has been down the gesture stays this detector's for
 * the rest of its life, even if that finger is lifted, so trailing movement refines the zoom the
 * reader just made rather than being handed back to the pager as a page turn.
 */
@Composable
private fun Modifier.transformGestures(zoomed: Boolean, onIntent: (GestureIntent) -> Unit): Modifier {
    val isZoomed by rememberUpdatedState(zoomed)
    val intent by rememberUpdatedState(onIntent)

    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)

            var transforming = false
            var dragging = false
            var slop = 0f

            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.pressed }) break

                if (event.changes.count { it.pressed } >= 2) transforming = true

                val pan = event.calculatePan()

                if (transforming) {
                    val gestureZoom = event.calculateZoom()
                    val centroid = event.calculateCentroid(useCurrent = true)

                    if (gestureZoom != 1f && centroid != Offset.Unspecified) {
                        intent(zoomIntent(centroid, gestureZoom))
                    }
                    if (pan != Offset.Zero) intent(panIntent(pan))

                    event.changes.forEach { if (it.pressed) it.consume() }
                    continue
                }

                if (!isZoomed) continue

                if (!dragging) {
                    slop += pan.getDistance()
                    dragging = slop > viewConfiguration.touchSlop
                }

                if (dragging && pan != Offset.Zero) {
                    intent(panIntent(pan))
                    event.changes.forEach { if (it.pressed) it.consume() }
                }
            }
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

private fun PointerInputScope.panIntent(pan: Offset) =
    GestureIntent.PanBy(pan.x / size.width, pan.y / size.height)

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
 *
 * The low-resolution base tier, when there is one, is drawn first and covers the whole page: a pan
 * or a zoom that reaches beyond whatever the detail raster's own region covers still lands on the
 * soft base raster underneath instead of on nothing. The detail raster is then drawn on top of it,
 * wherever it covers. Before either tier has ever landed for a page — the moment right after it
 * enters the reading window — there is nothing to draw yet and the loading placeholder is shown, as
 * before; once the base tier lands it replaces that placeholder, soft, ahead of the sharper detail
 * raster arriving.
 *
 * A page in [ReaderUiState.failedPages] is flagged regardless of whether it also has a raster on
 * screen: a page can keep whatever it last rendered successfully — see [ReaderPresenter]'s own doc
 * on refinement — while its *next* render, the one the reader is actually waiting on right now, is
 * the one that failed. Masking that behind the stale raster is what let this exact failure reach a
 * reader as merely soft or slow instead of visibly broken; the failure banner is drawn over whatever
 * raster is already there instead of replacing it, so the reader keeps the most recent thing that
 * did render while being told plainly that this page is not caught up.
 *
 * A page is clipped to its own slot because it is routinely asked to draw outside it: a raster cut
 * for an earlier, smaller layout covers the whole page, and placing it under a zoomed one puts most
 * of it past both edges — over the neighbouring pages the pager keeps laid out either side.
 */
@Composable
private fun PageContent(
    pageIndex: Int,
    state: ReaderUiState<BorrowedPage>,
    pageAspect: (Int) -> Float
) {
    val page = state.pages[pageIndex]
    val basePage = state.basePages[pageIndex]
    val image = remember(page) { page?.bitmap?.asImageBitmap() }
    val baseImage = remember(basePage) { basePage?.bitmap?.asImageBitmap() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag(ReaderTestTags.page(pageIndex)),
        contentAlignment = Alignment.Center
    ) {
        val failed = pageIndex in state.failedPages

        when {
            image != null || baseImage != null -> Canvas(
                Modifier.fillMaxSize().testTag(ReaderTestTags.pageContent(pageIndex))
            ) {
                val viewport = ReaderViewport.of(size.width.roundToInt(), size.height.roundToInt())
                    ?: return@Canvas
                val layout = ReaderGeometry.layout(
                    viewport,
                    pageAspect(pageIndex),
                    state.state.zoom,
                    state.state.fitMode
                )

                if (basePage != null && baseImage != null) {
                    drawTile(layout, basePage.region, baseImage, FilterQuality.Low)
                }
                if (page != null && image != null) {
                    drawTile(layout, page.region, image, FilterQuality.Medium)
                }
            }

            !failed -> Text(
                text = stringResource(R.string.reader_page_loading, pageIndex + 1),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (failed) {
            Text(
                text = stringResource(R.string.reader_page_failed, pageIndex + 1),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                    .padding(8.dp)
                    .testTag(ReaderTestTags.pageFailure(pageIndex))
            )
        }
    }
}

private fun DrawScope.drawTile(
    layout: ViewportLayout,
    region: PageSpaceRect,
    image: ImageBitmap,
    filterQuality: FilterQuality
) {
    val destination = ReaderGeometry.destination(layout, region)
    drawImage(
        image = image,
        dstOffset = IntOffset(destination.left.roundToInt(), destination.top.roundToInt()),
        dstSize = IntSize(
            destination.width.roundToInt().coerceAtLeast(1),
            destination.height.roundToInt().coerceAtLeast(1)
        ),
        filterQuality = filterQuality
    )
}

/**
 * Where the document is: the way back to the library, what is being read, and everything that is
 * not paging, folded into one menu so the bar stays a caption rather than a toolbar. The zoom
 * reading only appears once there is a zoom to report, and doubles as the way back to a fitted page.
 *
 * It takes the few readings it shows rather than the whole [ReaderUiState] so that it is skipped
 * outright while a pan is under way: the state is republished on every pointer sample, and a bar
 * that depended on all of it would re-run its string formatting on each one.
 */
@Composable
private fun TopChrome(
    title: String,
    zoomScale: Float,
    fitMode: PageFitMode,
    contentsAvailable: Boolean,
    onIntent: (GestureIntent) -> Unit,
    onContentsRequested: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier
) {
    val zoomed = zoomScale > MIN_ZOOM_SCALE
    val zoomLabel = stringResource(R.string.reader_zoom_level, (zoomScale * 100).roundToInt())

    ChromeBar(
        modifier = modifier.testTag(ReaderTestTags.CHROME_TOP),
        insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        dividerBelow = true
    ) {
        GlyphButton(
            glyph = "‹",
            description = stringResource(R.string.reader_back),
            onClick = onBack,
            testTag = ReaderTestTags.BACK
        )

        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )

        if (zoomed) {
            TextButton(
                onClick = { onIntent(GestureIntent.ResetZoom) },
                modifier = Modifier
                    .sizeIn(minHeight = TouchTarget)
                    .semantics { contentDescription = zoomLabel }
                    .testTag(ReaderTestTags.ZOOM)
            ) {
                Text(zoomLabel, style = MaterialTheme.typography.labelMedium)
            }
        }

        OverflowMenu(fitMode, contentsAvailable, onIntent, onContentsRequested)
    }
}

/**
 * Everything that is not paging. Contents appears only for a document that has one: an absent item
 * is how a document without a table of contents says so, which is quieter and more honest than an
 * item that opens an empty list.
 */
@Composable
private fun OverflowMenu(
    fitMode: PageFitMode,
    contentsAvailable: Boolean,
    onIntent: (GestureIntent) -> Unit,
    onContentsRequested: () -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Box {
        GlyphButton(
            glyph = "⋮",
            description = stringResource(R.string.reader_menu),
            onClick = { open = true },
            testTag = ReaderTestTags.OVERFLOW
        )

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (contentsAvailable) {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.reader_contents), style = MaterialTheme.typography.bodyMedium)
                    },
                    onClick = {
                        open = false
                        onContentsRequested()
                    },
                    modifier = Modifier.sizeIn(minHeight = TouchTarget).testTag(ReaderTestTags.CONTENTS)
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            FitModeItem(R.string.reader_fit_width, ReaderTestTags.FIT_WIDTH, PageFitMode.WIDTH, fitMode) {
                open = false
                onIntent(it)
            }
            FitModeItem(R.string.reader_fit_page, ReaderTestTags.FIT_PAGE, PageFitMode.PAGE, fitMode) {
                open = false
                onIntent(it)
            }
        }
    }
}

/**
 * Choosing the fit a page is already at is not a no-op: it is also how a reader who has zoomed in
 * gets back to that fit, so the zoom is always given up as well.
 */
@Composable
private fun FitModeItem(
    label: Int,
    testTag: String,
    mode: PageFitMode,
    active: PageFitMode,
    onIntent: (GestureIntent) -> Unit
) {
    DropdownMenuItem(
        text = { Text(stringResource(label), style = MaterialTheme.typography.bodyMedium) },
        trailingIcon = if (mode != active) null else {
            { Text("✓", style = MaterialTheme.typography.bodyMedium) }
        },
        onClick = {
            onIntent(GestureIntent.SetFitMode(mode))
            onIntent(GestureIntent.ResetZoom)
        },
        modifier = Modifier.sizeIn(minHeight = TouchTarget).testTag(testTag)
    )
}

/**
 * Paging, and nothing else: where in the document the reader is, and one page either way.
 *
 * The position is also the way to a page by number. Naming where you are is the natural place to
 * ask to be somewhere else, and putting it there keeps the bar a caption rather than growing it
 * another control; it stays a plain reading of the position, not a button, so the bar does not
 * change shape for a reader who never taps it.
 *
 * Nothing here depends on the viewport transform, so taking the position alone rather than the whole
 * [ReaderUiState] keeps a pinch or a pan from recomposing the bar at all.
 */
@Composable
private fun BottomChrome(
    currentPage: Int,
    pageCount: Int,
    onIntent: (GestureIntent) -> Unit,
    onJumpRequested: () -> Unit,
    modifier: Modifier
) {
    val spoken = stringResource(R.string.reader_page_position, currentPage + 1, pageCount)
    val jumpLabel = stringResource(R.string.reader_jump_action)

    ChromeBar(
        modifier = modifier.testTag(ReaderTestTags.CHROME_BOTTOM),
        insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        dividerBelow = false,
        arrangement = Arrangement.Center
    ) {
        GlyphButton(
            glyph = "‹",
            description = stringResource(R.string.reader_previous_page),
            onClick = { onIntent(GestureIntent.PageBack) },
            testTag = ReaderTestTags.PREVIOUS,
            enabled = currentPage > 0
        )

        Box(
            modifier = Modifier
                .sizeIn(minWidth = TouchTarget, minHeight = TouchTarget)
                .clickable(onClickLabel = jumpLabel, onClick = onJumpRequested)
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = spoken }
                .testTag(ReaderTestTags.POSITION),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.reader_page_indicator, currentPage + 1, pageCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        GlyphButton(
            glyph = "›",
            description = stringResource(R.string.reader_next_page),
            onClick = { onIntent(GestureIntent.PageForward) },
            testTag = ReaderTestTags.NEXT,
            enabled = currentPage < pageCount - 1
        )
    }
}

/**
 * A control the size of a touch target that reads as a single mark. The glyph carries no meaning to
 * anything that cannot see it, so the label it stands for is always attached as its description.
 */
@Composable
private fun GlyphButton(
    glyph: String,
    description: String,
    onClick: () -> Unit,
    testTag: String,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .sizeIn(minWidth = TouchTarget, minHeight = TouchTarget)
            .semantics { contentDescription = description }
            .testTag(testTag)
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge)
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
    arrangement: Arrangement.Horizontal = Arrangement.SpaceBetween,
    content: @Composable RowScope.() -> Unit
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column {
            if (!dividerBelow) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(insets)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .heightIn(min = TouchTarget),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = arrangement,
                content = content
            )

            if (dividerBelow) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
