package com.folium.reader.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import android.os.SystemClock
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.folium.reader.R
import com.folium.reader.ui.FoliumWidthClass
import com.folium.reader.ui.FoliumMenu
import com.folium.reader.ui.FoliumPaper
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.MIN_ZOOM_SCALE
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageFitMode
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.flattenOutline
import com.folium.reader.core.pdf.normalizeFlatNumberedChapters
import com.folium.reader.core.ocr.OcrPageState
import com.folium.reader.core.text.TextPage
import com.folium.reader.core.text.TextSelection
import com.folium.reader.core.text.TextSelectionPolicy
import com.folium.reader.core.text.TextSearchError
import com.folium.reader.core.text.TextSearchMode
import com.folium.reader.core.text.TextSearchSpec
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
    const val TYPOGRAPHY = "reader-typography"
    const val ZOOM = "reader-zoom"
    const val POSITION = "reader-position"
    const val POSITION_PAGE = "reader-position-page"
    const val JUMP_DIALOG = "reader-jump-dialog"
    const val JUMP_INPUT = "reader-jump-input"
    const val JUMP_CONFIRM = "reader-jump-confirm"
    const val CONTENTS = "reader-contents"
    const val CONTENTS_SHEET = "reader-contents-sheet"
    const val CONTENTS_CLOSE = "reader-contents-close"
    const val SELECTION_OVERLAY = "reader-selection-overlay"
    const val SELECTION_HIGHLIGHT = "reader-selection-highlight"
    const val SELECTION_ANCHOR = "reader-selection-anchor"
    const val SELECTION_FOCUS = "reader-selection-focus"
    const val SELECTION_COPY = "reader-selection-copy"
    const val SEARCH = "reader-search"
    const val SEARCH_FIELD = "reader-search-field"
    const val SEARCH_ROOT = "reader-search-root"
    const val PAGE_AREA = "reader-page-area"
    const val SEARCH_OPTIONS = "reader-search-options"
    const val SEARCH_PROGRESS = "reader-search-progress"
    const val SEARCH_SNIPPET = "reader-search-snippet"
    const val SEARCH_RESULTS = "reader-search-results"
    const val SEARCH_CLOSE = "reader-search-close"
    const val SEARCH_PREVIOUS = "reader-search-previous"
    const val SEARCH_NEXT = "reader-search-next"
    const val SEARCH_POSITION = "reader-search-position"
    const val SEARCH_COVERAGE = "reader-search-coverage"
    const val SEARCH_OCR_PAUSE = "reader-search-ocr-pause"
    const val SEARCH_OCR_RESUME = "reader-search-ocr-resume"
    const val SEARCH_LIMITED = "reader-search-limited"
    const val SEARCH_LITERAL = "reader-search-literal"
    const val SEARCH_REGEX = "reader-search-regex"
    const val SEARCH_CASE = "reader-search-case"
    const val SEARCH_WHOLE_WORD = "reader-search-whole-word"
    const val SEARCH_ERROR = "reader-search-error"
    const val SEARCH_HIGHLIGHTS = "reader-search-highlights"
    const val SEARCH_ACTIVE_HIGHLIGHT = "reader-search-active-highlight"

    fun page(pageIndex: Int): String = "reader-page/$pageIndex"
    fun pageContent(pageIndex: Int): String = "reader-page-content/$pageIndex"
    fun pagePlaceholder(pageIndex: Int): String = "reader-page-placeholder/$pageIndex"
    fun pageCarried(pageIndex: Int): String = "reader-page-carried/$pageIndex"
    fun pageFailure(pageIndex: Int): String = "reader-page-failure/$pageIndex"
    fun ocrStatus(pageIndex: Int): String = "reader-ocr-status/$pageIndex"
    fun ocrRetry(pageIndex: Int): String = "reader-ocr-retry/$pageIndex"
    fun contentsRow(index: Int): String = "reader-contents-row/$index"
    fun contentsTitle(index: Int): String = "reader-contents-title/$index"
}

private val CoverageBarThickness = 6.dp
private val SearchResultsMaxHeight = 260.dp

/**
 * How wide the search takes its own column on a screen with room for two panes.
 *
 * Over a phone-width page the results have nowhere to go but on top of the text; past the expanded
 * boundary there is room to set them beside it, and a reader can keep reading the passage that the
 * hit came from while stepping through the rest.
 */
private val SearchPaneWidth = 360.dp
private val SearchResultPageWidth = 44.dp
private val TouchTarget = 48.dp

/**
 * The query field is part of the overlay, so it separates the way every other overlay does: two
 * pixels of ink around a plain surface. Material's filled field arrived instead with a tonal
 * container, a rounded top and an indicator line — three separations this design system does not
 * use, and the one place left in the app still drawing them.
 */
private val SearchFieldBorder = 2.dp
private const val EDGE_TAP_FRACTION = 0.25f
private const val DOUBLE_TAP_ZOOM = 2.5f

internal data class PageTextSelection(
    val pageIndex: Int,
    val textPage: TextPage,
    val range: TextSelection
)

/** A range is valid only for the exact page and TextPage instance that produced its word indices. */
internal fun PageTextSelection?.rangeFor(pageIndex: Int, textPage: TextPage?): TextSelection? =
    this?.range?.takeIf { this.pageIndex == pageIndex && this.textPage === textPage }

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
    author: String? = null,
    state: ReaderUiState<BorrowedPage>,
    pageAspect: (Int) -> Float,
    onIntent: (GestureIntent) -> Unit,
    onViewportChanged: (ReaderViewport?) -> Unit,
    onBack: () -> Unit,
    outline: List<OutlineEntry> = emptyList(),
    textPage: TextPage? = null,
    ocr: ReaderOcrState? = null,
    search: ReaderSearchState? = null,
    onSearchOpen: () -> Unit = {},
    onSearch: (TextSearchSpec) -> Unit = {},
    onSearchClose: () -> Unit = {},
    onSearchPrevious: () -> Unit = {},
    onSearchNext: () -> Unit = {},
    onSearchSelect: (ReaderSearchMatchIdentity) -> Unit = {},
    onSearchOcrPause: () -> Unit = {},
    onSearchOcrResume: () -> Unit = {},
    onOcrRetry: () -> Unit = {},
    reflowable: Boolean = false,
    onTypographyRequested: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var jumpOpen by remember { mutableStateOf(false) }
    var contentsOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(search != null) }
    var topChromeBottomPx by remember { mutableStateOf(0f) }
    var bottomChromeHeightPx by remember { mutableStateOf<Float?>(null) }
    var screenWidthPx by remember { mutableIntStateOf(0) }
    val currentPage = state.state.currentPage
    var pageSelection by remember(currentPage) { mutableStateOf<PageTextSelection?>(null) }
    val currentSelection = pageSelection.rangeFor(currentPage, textPage)
    val contentsRows = remember(outline) { flattenOutline(normalizeFlatNumberedChapters(outline)) }

    LaunchedEffect(state.state.chromeVisible) {
        if (state.state.chromeVisible) {
            bottomChromeHeightPx = null
        } else {
            topChromeBottomPx = 0f
            bottomChromeHeightPx = 0f
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { screenWidthPx = it.width }
            .testTag(ReaderTestTags.SCREEN),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        ImmersiveSystemBars(hidden = !state.state.chromeVisible)

        val density = LocalDensity.current
        val searchPane = searchOpen &&
            FoliumWidthClass.of(with(density) { screenWidthPx.toDp() }).showsTwoPanes

        Row(Modifier.fillMaxSize()) {
            if (searchPane) {
                SearchSurface(
                    state = search,
                    onQuery = onSearch,
                    onPrevious = onSearchPrevious,
                    onNext = onSearchNext,
                    onSelect = onSearchSelect,
                    onOcrPause = onSearchOcrPause,
                    onOcrResume = onSearchOcrResume,
                    onClose = {
                        searchOpen = false
                        onSearchClose()
                    },
                    pane = true,
                    modifier = Modifier.fillMaxHeight()
                )
            }

        Box(Modifier.weight(1f).fillMaxHeight().testTag(ReaderTestTags.PAGE_AREA)) {
            PageSurface(
                state = state,
                pageAspect = pageAspect,
                onIntent = onIntent,
                onViewportChanged = onViewportChanged,
                textPage = textPage,
                selection = currentSelection,
                ocr = ocr,
                search = search,
                topOcclusionPx = when {
                    !state.state.chromeVisible -> 0f
                    topChromeBottomPx > 0f -> topChromeBottomPx
                    else -> null
                },
                bottomOcclusionPx = bottomChromeHeightPx,
                onSelectionChanged = { range ->
                    pageSelection = if (range == null || textPage == null) {
                        null
                    } else {
                        PageTextSelection(currentPage, textPage, range)
                    }
                },
                onOcrRetry = onOcrRetry
            )

            if (state.state.chromeVisible) {
                TopChrome(
                    title = title,
                    author = author,
                    zoomScale = state.state.zoom.scale,
                    fitMode = state.state.fitMode,
                    contentsAvailable = contentsRows.isNotEmpty(),
                    reflowable = reflowable,
                    onIntent = onIntent,
                    onContentsRequested = { contentsOpen = true },
                    onSearchRequested = {
                        searchOpen = true
                        onSearchOpen()
                    },
                    onTypographyRequested = onTypographyRequested,
                    onBack = onBack,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .onGloballyPositioned { topChromeBottomPx = it.boundsInRoot().bottom }
                )
            }
            if (state.state.chromeVisible) {
                BottomChrome(
                    currentPage = state.state.currentPage,
                    pageCount = state.state.pageCount,
                    onIntent = onIntent,
                    onJumpRequested = { jumpOpen = true },
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .onGloballyPositioned { bottomChromeHeightPx = it.boundsInRoot().height }
                )
            }

            if (searchOpen && !searchPane) {
                SearchSurface(
                    state = search,
                    onQuery = onSearch,
                    onPrevious = onSearchPrevious,
                    onNext = onSearchNext,
                    onSelect = onSearchSelect,
                    onOcrPause = onSearchOcrPause,
                    onOcrResume = onSearchOcrResume,
                    onClose = {
                        searchOpen = false
                        onSearchClose()
                    },
                    pane = false,
                    modifier = Modifier.align(Alignment.TopCenter)
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
    onViewportChanged: (ReaderViewport?) -> Unit,
    textPage: TextPage?,
    selection: TextSelection?,
    ocr: ReaderOcrState?,
    search: ReaderSearchState?,
    topOcclusionPx: Float?,
    bottomOcclusionPx: Float?,
    onSelectionChanged: (TextSelection?) -> Unit,
    onOcrRetry: () -> Unit
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
            .transformGestures(zoomed, currentPage, state, pageAspect, onIntent)
            .tapGestures(zoomed, currentPage, state, pageAspect, onIntent)
    ) { pageIndex ->
        PageContent(
            pageIndex,
            state,
            pageAspect,
            if (pageIndex == currentPage) textPage else null,
            if (pageIndex == currentPage) selection else null,
            if (pageIndex == currentPage) ocr else null,
            if (pageIndex == currentPage) search else null,
            topOcclusionPx,
            bottomOcclusionPx,
            onSelectionChanged,
            onOcrRetry
        )
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
private fun Modifier.transformGestures(
    zoomed: Boolean,
    currentPage: Int,
    state: ReaderUiState<BorrowedPage>,
    pageAspect: (Int) -> Float,
    onIntent: (GestureIntent) -> Unit
): Modifier {
    val isZoomed by rememberUpdatedState(zoomed)
    val intent by rememberUpdatedState(onIntent)
    val currentState by rememberUpdatedState(state)

    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)

            var transforming = false
            var dragging = false
            var slop = 0f

            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.pressed }) break
                if (event.changes.any { it.isConsumed }) continue

                if (event.changes.count { it.pressed } >= 2) transforming = true

                val pan = event.calculatePan()

                if (transforming) {
                    val gestureZoom = event.calculateZoom()
                    val centroid = event.calculateCentroid(useCurrent = true)

                    if (gestureZoom != 1f && centroid != Offset.Unspecified) {
                        intent(zoomIntent(centroid, gestureZoom, currentPage, currentState, pageAspect))
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

private fun PointerInputScope.zoomIntent(
    centroid: Offset,
    gestureZoom: Float,
    currentPage: Int,
    state: ReaderUiState<BorrowedPage>,
    pageAspect: (Int) -> Float
): GestureIntent.ZoomBy {
    val viewport = ReaderViewport.of(size.width, size.height)
    val focal = viewport?.let {
        val layout = ReaderGeometry.layout(it, pageAspect(currentPage), state.state.zoom, state.state.fitMode)
        ReaderGeometry.viewportToPage(layout, ViewportPoint(centroid.x, centroid.y), clampToPage = true)
    } ?: PageSpacePoint(.5f, .5f)
    return GestureIntent.ZoomBy(
    factor = gestureZoom,
    focal = focal
)
}

private fun PointerInputScope.panIntent(pan: Offset) =
    GestureIntent.PanBy(pan.x / size.width, pan.y / size.height)

/**
 * Tapping the outer quarter of either edge turns the page and tapping the middle shows or hides the
 * chrome, so navigation stays reachable one-handed without any control being on screen. While
 * zoomed the edges lose that meaning, since a tap there is far more likely to be aimed at the page.
 */
private fun Modifier.tapGestures(
    zoomed: Boolean,
    currentPage: Int,
    state: ReaderUiState<BorrowedPage>,
    pageAspect: (Int) -> Float,
    onIntent: (GestureIntent) -> Unit
): Modifier =
    pointerInput(zoomed) {
        detectTapGestures(
            onDoubleTap = { position ->
                if (zoomed) onIntent(GestureIntent.ResetZoom)
                else onIntent(zoomIntent(position, DOUBLE_TAP_ZOOM, currentPage, state, pageAspect))
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
    pageAspect: (Int) -> Float,
    textPage: TextPage?,
    selection: TextSelection?,
    ocr: ReaderOcrState?,
    search: ReaderSearchState?,
    topOcclusionPx: Float?,
    bottomOcclusionPx: Float?,
    onSelectionChanged: (TextSelection?) -> Unit,
    onOcrRetry: () -> Unit
) {
    val page = state.pages[pageIndex]
    val basePage = state.basePages[pageIndex]
    val carried = state.carriedPreview.takeIf { page == null && basePage == null }
    val carriedImage = remember(carried) { carried?.value?.bitmap?.asImageBitmap() }
    val loadingDescription = stringResource(R.string.reader_page_loading, pageIndex + 1)
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

            // A page the reader was looking at a moment ago, standing in for one that has not
            // arrived. Drawn at its own page's shape rather than at this one's, since a document
            // whose pages differ would otherwise show it stretched. The sentence for the page that
            // is actually being waited on is still read out.
            !failed && carried != null && carriedImage != null -> Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = loadingDescription }
                    .testTag(ReaderTestTags.pageCarried(pageIndex))
            ) {
                val viewport = ReaderViewport.of(size.width.roundToInt(), size.height.roundToInt())
                    ?: return@Canvas
                val layout = ReaderGeometry.layout(
                    viewport,
                    pageAspect(carried.pageIndex),
                    state.state.zoom,
                    state.state.fitMode
                )

                drawTile(layout, PageSpaceRect(0f, 0f, 1f, 1f), carriedImage, FilterQuality.Low)
            }

            // Nothing has ever been drawn for this document yet, so the page is drawn as the page it
            // will be: the sheet, in its place, at its proportions.
            !failed -> Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = loadingDescription }
                    .testTag(ReaderTestTags.pagePlaceholder(pageIndex))
            ) {
                val viewport = ReaderViewport.of(size.width.roundToInt(), size.height.roundToInt())
                    ?: return@Canvas
                val layout = ReaderGeometry.layout(
                    viewport,
                    pageAspect(pageIndex),
                    state.state.zoom,
                    state.state.fitMode
                )
                val sheet = ReaderGeometry.destination(layout, PageSpaceRect(0f, 0f, 1f, 1f))

                drawRect(
                    color = FoliumPaper,
                    topLeft = Offset(sheet.left, sheet.top),
                    size = Size(sheet.width, sheet.height)
                )
            }
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

        if (!failed && textPage != null && textPage.words.isNotEmpty()) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val measuredViewport = ReaderViewport.of(constraints.maxWidth, constraints.maxHeight)
                if (measuredViewport != null) {
                    ReaderSearchOverlay(
                        search = search,
                        pageIndex = pageIndex,
                        layout = ReaderGeometry.layout(
                            measuredViewport,
                            pageAspect(pageIndex),
                            state.state.zoom,
                            state.state.fitMode
                        )
                    )
                    ReaderSelectionOverlay(
                        textPage = textPage,
                        layout = ReaderGeometry.layout(
                            measuredViewport,
                            pageAspect(pageIndex),
                            state.state.zoom,
                            state.state.fitMode
                        ),
                        selection = selection,
                        topOcclusionPx = topOcclusionPx,
                        onSelectionChanged = onSelectionChanged
                    )
                }
            }
        }

        if (ocr?.visible == true && bottomOcclusionPx != null) {
            val bottomPadding = with(LocalDensity.current) { bottomOcclusionPx.toDp() } + 8.dp
            OcrPageFeedback(
                pageIndex = pageIndex,
                state = ocr,
                onRetry = onOcrRetry,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding)
            )
        }
    }
}

@Composable
private fun OcrPageFeedback(
    pageIndex: Int,
    state: ReaderOcrState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val message = when {
        state.retryFailed -> stringResource(R.string.reader_ocr_retry_failed)
        state.unavailable -> stringResource(R.string.reader_ocr_unavailable)
        state.retryPending -> stringResource(R.string.reader_ocr_retrying)
        state.status?.state == OcrPageState.QUEUED || state.status?.state == OcrPageState.RUNNING ->
            stringResource(R.string.reader_ocr_recognizing)
        state.status?.state == OcrPageState.FAILED ->
            stringResource(R.string.reader_ocr_failed)
        state.status?.state == OcrPageState.CANCELLED ->
            stringResource(R.string.reader_ocr_cancelled)
        else -> stringResource(R.string.reader_ocr_stale)
    }

    Surface(
        modifier = modifier.widthIn(max = 360.dp).testTag(ReaderTestTags.ocrStatus(pageIndex)),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.retryFailed || state.unavailable ||
                    state.status?.state == OcrPageState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (state.retryAvailable) {
                TextButton(
                    shape = MaterialTheme.shapes.small,
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = TouchTarget)
                        .testTag(ReaderTestTags.ocrRetry(pageIndex))
                ) {
                    Text(stringResource(R.string.reader_ocr_retry))
                }
            }
        }
    }
}

/** Paint-only search layer: Canvas installs no pointer input and therefore cannot consume gestures. */
@Composable
private fun ReaderSearchOverlay(search: ReaderSearchState?, pageIndex: Int, layout: ViewportLayout) {
    val pageMatches = search?.matches.orEmpty().filter { it.pageIndex == pageIndex }
    if (pageMatches.isEmpty()) return
    val active = search?.activeIdentity
    val normal = Color(0xFFFFC107).copy(alpha = .28f)
    val selected = Color(0xFFFF9800).copy(alpha = .58f)
    Canvas(Modifier.fillMaxSize().testTag(ReaderTestTags.SEARCH_HIGHLIGHTS)) {
        pageMatches.forEach { match ->
            match.boxes.forEach { box ->
                val rect = ReaderGeometry.destination(layout, box)
                drawRect(
                    color = if (match.identity == active) selected else normal,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height)
                )
            }
        }
    }
    search?.activeMatch?.takeIf { it.pageIndex == pageIndex }?.let {
        Canvas(Modifier.fillMaxSize().testTag(ReaderTestTags.SEARCH_ACTIVE_HIGHLIGHT)) {}
    }
}

@Composable
private fun SearchSurface(
    state: ReaderSearchState?,
    onQuery: (TextSearchSpec) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: (ReaderSearchMatchIdentity) -> Unit,
    onOcrPause: () -> Unit,
    onOcrResume: () -> Unit,
    onClose: () -> Unit,
    pane: Boolean,
    modifier: Modifier
) {
    var spec by remember { mutableStateOf(state?.spec ?: TextSearchSpec("")) }
    var optionsExpanded by remember { mutableStateOf(false) }
    val activeIndex = state?.activeIndex
    val coverage = state?.coverage
    val pending = state?.pending
    val position = if (activeIndex == null) {
        val completeCoverage = coverage?.let {
            pending == null && !it.running && !it.error && it.incompletePages == 0
        } == true
        if (completeCoverage) stringResource(R.string.reader_search_no_results)
        else stringResource(R.string.reader_search_no_results_yet)
    } else if (state.truncated) {
        stringResource(R.string.reader_search_position_limited)
    } else {
        stringResource(R.string.reader_search_position, activeIndex + 1, state.matches.size)
    }
    val coverageText = when {
        pending != null -> stringResource(R.string.reader_search_searching)
        coverage == null -> stringResource(R.string.reader_search_waiting)
        coverage.error -> stringResource(R.string.reader_search_coverage_error)
        coverage.totalPages == 0 -> stringResource(R.string.reader_search_coverage_empty)
        state?.ocrPlan?.searchActive == false && state.ocrPlan.canResume ->
            stringResource(R.string.reader_search_ocr_paused)
        coverage.running -> stringResource(
            R.string.reader_search_coverage_running,
            coverage.processedPages,
            coverage.totalPages,
            coverage.incompletePages,
            coverage.pendingPages,
            coverage.failedPages,
            coverage.cancelledPages
        )
        coverage.incompletePages > 0 -> stringResource(
            R.string.reader_search_coverage_incomplete,
            coverage.processedPages,
            coverage.totalPages,
            coverage.incompletePages,
            coverage.pendingPages,
            coverage.failedPages,
            coverage.cancelledPages
        )
        else -> stringResource(R.string.reader_search_coverage_complete, coverage.totalPages)
    }
    val progressVisible = pending != null || coverage?.running == true

    Box(modifier.safeDrawingPadding().padding(8.dp)) {
        Surface(
            modifier = if (pane) {
                Modifier.width(SearchPaneWidth).fillMaxHeight()
                    .testTag(ReaderTestTags.SEARCH_ROOT)
            } else {
                Modifier.widthIn(max = 720.dp).fillMaxWidth()
                    .testTag(ReaderTestTags.SEARCH_ROOT)
            },
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = spec.query,
                        onValueChange = { value -> spec = spec.copy(query = value); onQuery(spec) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge
                            .copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                        modifier = Modifier
                            .weight(1f)
                            .height(TouchTarget)
                            .border(SearchFieldBorder, MaterialTheme.colorScheme.onSurface)
                            .padding(horizontal = 12.dp)
                            .testTag(ReaderTestTags.SEARCH_FIELD),
                        decorationBox = { field ->
                            Box(
                                modifier = Modifier.fillMaxHeight(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (spec.query.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.reader_search),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                field()
                            }
                        }
                    )
                    Box {
                        GlyphButton(
                            glyph = "⋮",
                            description = stringResource(R.string.reader_search_options),
                            onClick = { optionsExpanded = true },
                            testTag = ReaderTestTags.SEARCH_OPTIONS
                        )
                        FoliumMenu(
                            expanded = optionsExpanded,
                            onDismissRequest = { optionsExpanded = false }
                        ) {
                            SearchOptionMenuItem(
                                selected = spec.mode == TextSearchMode.LITERAL,
                                label = stringResource(R.string.reader_search_literal),
                                tag = ReaderTestTags.SEARCH_LITERAL,
                                role = Role.RadioButton
                            ) { spec = spec.copy(mode = TextSearchMode.LITERAL); onQuery(spec) }
                            SearchOptionMenuItem(
                                selected = spec.mode == TextSearchMode.REGEX,
                                label = stringResource(R.string.reader_search_regex),
                                tag = ReaderTestTags.SEARCH_REGEX,
                                role = Role.RadioButton
                            ) { spec = spec.copy(mode = TextSearchMode.REGEX); onQuery(spec) }
                            HorizontalDivider()
                            Text(
                                text = stringResource(R.string.reader_search_matching),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp
                                )
                            )
                            SearchOptionMenuItem(
                                selected = spec.caseSensitive,
                                label = stringResource(R.string.reader_search_case),
                                tag = ReaderTestTags.SEARCH_CASE,
                                role = Role.Checkbox
                            ) { spec = spec.copy(caseSensitive = !spec.caseSensitive); onQuery(spec) }
                            SearchOptionMenuItem(
                                selected = spec.wholeWord,
                                label = stringResource(R.string.reader_search_whole_word),
                                tag = ReaderTestTags.SEARCH_WHOLE_WORD,
                                role = Role.Checkbox
                            ) { spec = spec.copy(wholeWord = !spec.wholeWord); onQuery(spec) }
                        }
                    }
                    GlyphButton(
                        glyph = "×",
                        description = stringResource(R.string.reader_search_close),
                        onClick = onClose,
                        testTag = ReaderTestTags.SEARCH_CLOSE
                    )
                }
                if (progressVisible) {
                    SearchCoverageBar(
                        coverage = coverage,
                        modifier = Modifier.fillMaxWidth().testTag(ReaderTestTags.SEARCH_PROGRESS)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        position,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp)
                            .testTag(ReaderTestTags.SEARCH_POSITION)
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    when {
                        state?.ocrPlan?.canResume == true ->
                            TextButton(
                                shape = MaterialTheme.shapes.small,
                                onClick = onOcrResume,
                                modifier = Modifier.heightIn(min = TouchTarget)
                                    .testTag(ReaderTestTags.SEARCH_OCR_RESUME)
                            ) { Text(stringResource(R.string.reader_search_ocr_resume)) }
                        state?.ocrPlan?.canPause == true -> TextButton(
                            onClick = onOcrPause,
                            modifier = Modifier.heightIn(min = TouchTarget)
                                .testTag(ReaderTestTags.SEARCH_OCR_PAUSE)
                        ) { Text(stringResource(R.string.reader_search_ocr_pause)) }
                    }
                    GlyphButton(
                        glyph = "‹",
                        description = stringResource(R.string.reader_search_previous),
                        onClick = onPrevious,
                        testTag = ReaderTestTags.SEARCH_PREVIOUS,
                        enabled = activeIndex != null && activeIndex > 0
                    )
                    GlyphButton(
                        glyph = "›",
                        description = stringResource(R.string.reader_search_next),
                        onClick = onNext,
                        testTag = ReaderTestTags.SEARCH_NEXT,
                        enabled = activeIndex != null && activeIndex < (state?.matches?.lastIndex ?: -1)
                    )
                }
                Text(
                    coverageText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (coverage?.error == true) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        .testTag(ReaderTestTags.SEARCH_COVERAGE)
                )
                state?.error?.let { error ->
                    Text(
                        text = stringResource(error.messageResource()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp)
                            .testTag(ReaderTestTags.SEARCH_ERROR)
                    )
                }
                state?.let {
                    SearchResults(
                        state = it,
                        onSelect = onSelect,
                        modifier = if (pane) {
                            Modifier.weight(1f, fill = false)
                        } else {
                            Modifier.heightIn(max = SearchResultsMaxHeight)
                        }
                    )
                }
                if (state?.truncated == true) {
                    Text(
                        stringResource(R.string.reader_search_results_limited),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp)
                            .testTag(ReaderTestTags.SEARCH_LIMITED)
                    )
                }
            }
        }
    }
}

/**
 * Every hit, in page order, with where it came from.
 *
 * The bar used to show one snippet at a time and step through them with a pair of arrows, which
 * makes finding the third of forty a matter of pressing next twice and reading fast. As a list the
 * reader picks. The arrows stay: stepping is still the right gesture once you are close.
 *
 * Each row says whether the text came from the document or from recognition, which the index has
 * always known and never showed — it is the difference between a quotation you can trust and one a
 * recognizer guessed at.
 */
/**
 * How much of the book the answer covers.
 *
 * It was an indeterminate bar, which says only that something is happening — on a six hundred page
 * scan, where recognition runs for minutes, that is the one thing the reader already knew. Drawn
 * against the real counts it says how far along the answer is, and therefore how much to trust a
 * result count that is still climbing. Failed pages are drawn apart from read ones: they are not
 * coming, and a bar that filled anyway would promise a completeness that never arrives.
 */
@Composable
private fun SearchCoverageBar(coverage: ReaderSearchCoverage?, modifier: Modifier = Modifier) {
    val total = coverage?.totalPages ?: 0
    if (coverage == null || total <= 0) {
        LinearProgressIndicator(modifier = modifier)
        return
    }

    val read = coverage.indexedPages.toFloat() / total
    val failed = coverage.failedPages.toFloat() / total
    val ink = MaterialTheme.colorScheme.onSurface
    val unread = MaterialTheme.colorScheme.outlineVariant
    val lost = MaterialTheme.colorScheme.error

    Spacer(
        modifier.height(CoverageBarThickness).drawBehind {
            drawRect(color = unread)
            val readWidth = (read.coerceIn(0f, 1f) * size.width)
            if (readWidth > 0f) drawRect(color = ink, size = Size(readWidth, size.height))
            val failedWidth = (failed.coerceIn(0f, 1f) * size.width)
            if (failedWidth > 0f) {
                drawRect(
                    color = lost,
                    topLeft = Offset(size.width - failedWidth, 0f),
                    size = Size(failedWidth, size.height)
                )
            }
        }
    )
}

@Composable
private fun SearchResults(
    state: ReaderSearchState,
    onSelect: (ReaderSearchMatchIdentity) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.matches.isEmpty()) return

    val active = state.activeIdentity

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ReaderTestTags.SEARCH_RESULTS)
    ) {
        items(state.matches, key = { it.identity.toString() }) { match ->
            val selected = match.identity == active
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                    )
                    .clickable { onSelect(match.identity) }
                    .heightIn(min = TouchTarget)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Column(Modifier.width(SearchResultPageWidth)) {
                    Text(
                        text = "${match.pageIndex + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    if (match.identity.source == com.folium.reader.core.text.TextSource.OCR) {
                        Text(
                            text = stringResource(R.string.reader_search_source_ocr),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                Text(
                    text = highlighted(
                        snippet = match.snippet,
                        query = state.spec.query,
                        accent = MaterialTheme.colorScheme.tertiary,
                        onAccent = MaterialTheme.colorScheme.onTertiary
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag(ReaderTestTags.SEARCH_SNIPPET)
                )
            }
        }
    }
}

/**
 * Marks the term inside a snippet, as a filled block rather than a change of ink.
 *
 * A hue change on the letters is the one mark that does not survive the trip: on an e-paper panel
 * the accent lands as a grey a shade off the text around it, and the reader is left rereading the
 * line to find what matched. A filled block keeps its edges whatever the panel does with colour.
 *
 * Matched on the plain string rather than by reusing the index's own spans: those are word ranges
 * on the page, and a snippet is a windowed, whitespace-collapsed copy of it, so the positions do
 * not survive the trip. A missed mark costs a highlight; a wrong one would point at the wrong word.
 */
private fun highlighted(
    snippet: String,
    query: String,
    accent: Color,
    onAccent: Color
): AnnotatedString {
    val term = query.trim()
    if (term.isEmpty()) return AnnotatedString(snippet)

    return buildAnnotatedString {
        var from = 0
        while (from <= snippet.length - term.length) {
            val at = snippet.indexOf(term, from, ignoreCase = true)
            if (at < 0) break
            append(snippet, from, at)
            withStyle(SpanStyle(background = accent, color = onAccent)) {
                append(snippet, at, at + term.length)
            }
            from = at + term.length
        }
        append(snippet, from, snippet.length)
    }
}

@Composable
private fun SearchOptionMenuItem(
    selected: Boolean,
    label: String,
    tag: String,
    role: Role,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = { if (selected) Text("✓") },
        modifier = Modifier.heightIn(min = TouchTarget).semantics {
            this.selected = selected
            this.role = role
        }.testTag(tag)
    )
}

private fun TextSearchError.messageResource(): Int = when (this) {
    TextSearchError.QueryTooLong -> R.string.reader_search_error_too_long
    TextSearchError.InvalidPattern -> R.string.reader_search_error_invalid_regex
    TextSearchError.ZeroLengthPattern -> R.string.reader_search_error_zero_length
    TextSearchError.UnsupportedPattern -> R.string.reader_search_error_unsupported_regex
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
    author: String?,
    zoomScale: Float,
    fitMode: PageFitMode,
    contentsAvailable: Boolean,
    reflowable: Boolean,
    onIntent: (GestureIntent) -> Unit,
    onContentsRequested: () -> Unit,
    onSearchRequested: () -> Unit,
    onTypographyRequested: () -> Unit,
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

        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            author?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (zoomed) {
            TextButton(
                shape = MaterialTheme.shapes.small,
                onClick = { onIntent(GestureIntent.ResetZoom) },
                modifier = Modifier
                    .sizeIn(minHeight = TouchTarget)
                    .semantics { contentDescription = zoomLabel }
                    .testTag(ReaderTestTags.ZOOM)
            ) {
                Text(zoomLabel, style = MaterialTheme.typography.bodyMedium)
            }
        }

        OverflowMenu(fitMode, contentsAvailable, reflowable, onIntent, onContentsRequested, onSearchRequested, onTypographyRequested)
    }
}

/**
 * Everything that is not paging. Contents appears only for a document that has one: an absent item
 * is how a document without a table of contents says so, which is quieter and more honest than an
 * item that opens an empty list. Typography is the mirror image, present only for a document the
 * engine can re-paginate — and the fit-mode items disappear there instead, since they answer how
 * much of an already-fixed page fits the viewport, a question a reflowable document does not have.
 */
@Composable
private fun OverflowMenu(
    fitMode: PageFitMode,
    contentsAvailable: Boolean,
    reflowable: Boolean,
    onIntent: (GestureIntent) -> Unit,
    onContentsRequested: () -> Unit,
    onSearchRequested: () -> Unit,
    onTypographyRequested: () -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Box {
        GlyphButton(
            glyph = "⋮",
            description = stringResource(R.string.reader_menu),
            onClick = { open = true },
            testTag = ReaderTestTags.OVERFLOW
        )

        FoliumMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reader_search), style = MaterialTheme.typography.bodyMedium) },
                onClick = { open = false; onSearchRequested() },
                modifier = Modifier.sizeIn(minHeight = TouchTarget).testTag(ReaderTestTags.SEARCH)
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

            if (reflowable) {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.reader_typography), style = MaterialTheme.typography.bodyMedium)
                    },
                    onClick = {
                        open = false
                        onTypographyRequested()
                    },
                    modifier = Modifier.sizeIn(minHeight = TouchTarget).testTag(ReaderTestTags.TYPOGRAPHY)
                )
            } else {
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

        PositionScrubber(
            currentPage = currentPage,
            pageCount = pageCount,
            spoken = spoken,
            jumpLabel = jumpLabel,
            onJumpRequested = onJumpRequested,
            onSeek = { page -> onIntent(GestureIntent.FlingToPage(page)) },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )

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
 * Where you are in the book, and the way to be somewhere else.
 *
 * The bar used to name the position and nothing more; reaching page 300 of 600 meant either six
 * hundred swipes or finding the jump dialog behind a tap on the number. Dragging it is the gesture
 * the shape already implies, and the number stays exactly where it was for anyone who only reads it
 * — including the jump dialog, which remains the way to name an exact page.
 *
 * The document follows the finger. A scrubber that only committed on release makes the reader drag
 * blind and check afterwards, which is two gestures to land on one page. Seeking is a direct jump
 * rather than a walk through the pages between, and the viewport scheduler drops a render the next
 * one supersedes, so a fast drag costs the pages actually dwelt on rather than every page crossed.
 *
 * The seek fires when the page changes, not when the finger moves: within one page a drag is
 * hundreds of events and none of them is a different page to draw.
 */
/**
 * How long a page stays under the finger before the document is told to move to the next one.
 *
 * A fast drag crosses a page every sixteen milliseconds. Seeking on each one meant the document
 * never held a page long enough for that page's own raster to arrive and still be wanted: the base
 * tier rendered it in about seven milliseconds and [ReaderPresenter] then dropped it, because by the
 * time it landed the window had already moved past. The reader watched a blank sheet for the whole
 * gesture and the pages it crossed were rendered and thrown away sixty times a second.
 *
 * Letting a page sit for this long instead is what turns that work into something visible. It costs
 * nothing in feedback, because the number above the track is drawn from where the finger is rather
 * than from where the document is.
 */
internal const val SEEK_INTERVAL_MILLIS = 120L

/** Whether a drag that has reached [page] should move the document there yet. */
internal fun seekWanted(page: Int, seekedPage: Int, millisSinceSeek: Long): Boolean =
    page != seekedPage && millisSinceSeek >= SEEK_INTERVAL_MILLIS

/**
 * The same question when the finger lifts. The interval delays a page, it never drops one: whatever
 * the drag ended on is asked for however recently the last one was.
 */
internal fun seekWantedOnRelease(page: Int, seekedPage: Int): Boolean = page != seekedPage

@Composable
private fun PositionScrubber(
    currentPage: Int,
    pageCount: Int,
    spoken: String,
    jumpLabel: String,
    onJumpRequested: () -> Unit,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableIntStateOf(0) }
    var seeked by remember { mutableIntStateOf(-1) }
    var seekedAt by remember { mutableLongStateOf(0L) }
    val shown = dragging?.let { pageAt(it, width, pageCount) } ?: currentPage
    val track = MaterialTheme.colorScheme.outlineVariant
    val filled = MaterialTheme.colorScheme.tertiary
    val handle = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier
            .sizeIn(minHeight = TouchTarget)
            .semantics { contentDescription = spoken }
            .testTag(ReaderTestTags.POSITION),
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(ScrubberHeight)
                .onSizeChanged { width = it.width }
                .pointerInput(pageCount) {
                    detectHorizontalDragGestures(
                        onDragStart = { start ->
                            dragging = start.x
                            seeked = currentPage
                            seekedAt = SystemClock.uptimeMillis()
                        },
                        onDragEnd = {
                            dragging?.let { at ->
                                val page = pageAt(at, width, pageCount)
                                if (seekWantedOnRelease(page, seeked)) onSeek(page)
                            }
                            dragging = null
                            seeked = -1
                        },
                        onDragCancel = {
                            dragging = null
                            seeked = -1
                        },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            val at = ((dragging ?: change.position.x) + delta).coerceIn(0f, width.toFloat())
                            dragging = at

                            val page = pageAt(at, width, pageCount)
                            val now = SystemClock.uptimeMillis()
                            if (seekWanted(page, seeked, now - seekedAt)) {
                                seeked = page
                                seekedAt = now
                                onSeek(page)
                            }
                        }
                    )
                }
                .drawBehind {
                    val mid = size.height / 2
                    drawRect(
                        color = track,
                        topLeft = Offset(0f, mid - TrackWeight.toPx() / 2),
                        size = Size(size.width, TrackWeight.toPx())
                    )
                    val at = if (pageCount <= 1) 0f else shown.toFloat() / (pageCount - 1) * size.width
                    drawRect(
                        color = filled,
                        topLeft = Offset(0f, mid - TrackWeight.toPx() / 2),
                        size = Size(at, TrackWeight.toPx())
                    )
                    drawRect(
                        color = handle,
                        topLeft = Offset(
                            (at - HandleWidth.toPx() / 2).coerceIn(0f, size.width - HandleWidth.toPx()),
                            mid - HandleHeight.toPx() / 2
                        ),
                        size = Size(HandleWidth.toPx(), HandleHeight.toPx())
                    )
                }
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = stringResource(R.string.reader_page_indicator, shown + 1, pageCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clickable(onClickLabel = jumpLabel, onClick = onJumpRequested)
                .padding(vertical = 2.dp)
                .testTag(ReaderTestTags.POSITION_PAGE)
        )
    }
}

private fun pageAt(x: Float, width: Int, pageCount: Int): Int {
    if (width <= 0 || pageCount <= 1) return 0
    return ((x / width) * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)
}

private val ScrubberHeight = 24.dp
private val TrackWeight = 4.dp
private val HandleWidth = 3.dp
private val HandleHeight = 14.dp

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
        shape = MaterialTheme.shapes.small,
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
