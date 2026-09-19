package com.folium.reader.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.PaddingValues
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
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.Constraints
import com.folium.reader.R
import com.folium.reader.ui.FoliumDivider
import com.folium.reader.ui.FoliumSpacing
import com.folium.reader.ui.FoliumWidthClass
import com.folium.reader.ui.FoliumMenu
import com.folium.reader.ui.FoliumPaper
import com.folium.reader.ui.FoliumType
import com.folium.reader.ui.LocalFoliumEInk
import com.folium.reader.ui.FoliumRuleEdge
import com.folium.reader.ui.foliumBorder
import com.folium.reader.ui.foliumRule
import com.folium.reader.core.pdf.GestureIntent
import com.folium.reader.core.pdf.HorizontalViewportReducer
import com.folium.reader.core.pdf.MIN_ZOOM_SCALE
import com.folium.reader.core.pdf.OutlineEntry
import com.folium.reader.core.pdf.PageSpacePoint
import com.folium.reader.core.pdf.PageSpaceRect
import com.folium.reader.core.pdf.ReflowPageColors
import com.folium.reader.core.preview.PagePreview
import com.folium.reader.ui.toBackgroundColor
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
    const val TYPOGRAPHY = "reader-typography"
    const val FIT_WIDTH = "reader-fit-width"
    const val FIT_PAGE = "reader-fit-page"
    const val BOOK_SETTINGS = "reader-book-settings"
    const val ZOOM = "reader-zoom"
    const val POSITION = "reader-position"
    const val POSITION_PAGE = "reader-position-page"
    const val JUMP_DIALOG = "reader-jump-dialog"
    const val JUMP_INPUT = "reader-jump-input"
    const val JUMP_CONFIRM = "reader-jump-confirm"
    const val CONTENTS = "reader-contents"
    const val TOP_BAR_CONTENTS = "reader-top-bar-contents"
    const val TOP_BAR_SEARCH = "reader-top-bar-search"
    const val CONTENTS_SHEET = "reader-contents-sheet"
    const val CONTENTS_CLOSE = "reader-contents-close"
    const val CONTENTS_TAB = "reader-contents-tab"
    const val PAGES_TAB = "reader-pages-tab"
    const val PAGES_GRID = "reader-pages-grid"
    const val SELECTION_OVERLAY = "reader-selection-overlay"
    const val SELECTION_HIGHLIGHT = "reader-selection-highlight"
    const val SELECTION_ANCHOR = "reader-selection-anchor"
    const val SELECTION_FOCUS = "reader-selection-focus"
    const val SELECTION_COPY = "reader-selection-copy"
    const val SEARCH = "reader-search"
    const val SEARCH_FIELD = "reader-search-field"
    const val SEARCH_ROOT = "reader-search-root"
    const val PAGE_AREA = "reader-page-area"
    const val SPREAD_ROW = "reader-spread-row"
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
    fun pagePreview(pageIndex: Int): String = "reader-page-preview/$pageIndex"
    fun pageFailure(pageIndex: Int): String = "reader-page-failure/$pageIndex"
    fun ocrStatus(pageIndex: Int): String = "reader-ocr-status/$pageIndex"
    fun ocrRetry(pageIndex: Int): String = "reader-ocr-retry/$pageIndex"
    fun contentsRow(index: Int): String = "reader-contents-row/$index"
    fun contentsTitle(index: Int): String = "reader-contents-title/$index"
    fun pageThumbnail(pageIndex: Int): String = "reader-page-thumbnail/$pageIndex"
    fun pageNumberCaption(pageIndex: Int): String = "reader-page-number/$pageIndex"
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

/** S-BusquedaTira.dc.html, T-Busqueda.dc.html: the result row's page-number column is 42dp wide. */
private val SearchResultPageWidth = 42.dp

/**
 * The search field's own height, taller than every other control's [FoliumSpacing.touchTarget]:
 * the design system gives the query field 48dp while every button around it keeps the system's
 * ordinary 44dp floor (S-BusquedaTira.dc.html, S-Componentes.dc.html "03 · CAMPO").
 */
private val SearchFieldHeight = 48.dp

/**
 * The query field is part of the overlay, so it separates the way every other overlay does: two
 * pixels of ink around a plain surface. Material's filled field arrived instead with a tonal
 * container, a rounded top and an indicator line — three separations this design system does not
 * use, and the one place left in the app still drawing them.
 */
private val SearchFieldBorder = 2.dp
private val SearchFieldGlyphSize = 18.dp
private val SearchFieldGlyphGap = 10.dp
private val GlyphIconSize = 20.dp
private val SearchCloseGlyphSize = 15.dp
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
 * of contents; [NavigationSheet] hides its Contents tab in that case rather than the menu item
 * itself, since the sheet's own Pages tab is worth reaching for every document.
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
    onOcrRetry: (Int) -> Unit = {},
    reflowable: Boolean = false,
    pageColors: ReflowPageColors? = null,
    onTypographyRequested: () -> Unit = {},
    thumbnails: ThumbnailGridState<BorrowedThumbnail> = ThumbnailGridState(),
    onThumbnailsWanted: (List<Int>) -> Unit = {},
    textPages: Map<Int, ReaderTextState> = emptyMap(),
    ocrPages: Map<Int, ReaderOcrState> = emptyMap(),
    onSpreadEligibilityChanged: (Boolean, Int) -> Unit = { _, _ -> },
    /** A blurred stand-in for a page nothing of its own has landed for yet — see [PageSlotContent.PREVIEW]. */
    previewFor: (Int) -> PagePreview? = { null },
    modifier: Modifier = Modifier
) {
    var jumpOpen by remember { mutableStateOf(false) }
    var contentsOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(search != null) }
    var topChromeBottomPx by remember { mutableStateOf(0f) }
    var bottomChromeHeightPx by remember { mutableStateOf<Float?>(null) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // Seeded from the configuration rather than zero: onSizeChanged only reports the true width
    // after the first layout pass, and a zero-width guess would classify an expanded window as
    // COMPACT for that frame, which flips a search pane already open on restore between the
    // compact overlay and the expanded side column.
    var screenWidthPx by remember {
        mutableIntStateOf(with(density) { configuration.screenWidthDp.dp.roundToPx() })
    }
    val currentPage = state.state.currentPage
    val pagesPerView = HorizontalViewportReducer.effectivePagesPerView(state.state)
    val rightPage = if (pagesPerView == 2) spreadRightPage(currentPage, state.state.pageCount) else null
    var pageSelection by remember(currentPage) { mutableStateOf<PageTextSelection?>(null) }
    val currentSelection = pageSelection.rangeFor(currentPage, textPage)
    val rightTextPage = rightPage?.let { textPages[it]?.selectablePage(it) }
    val rightSelection = rightPage?.let { pageSelection.rangeFor(it, rightTextPage) }
    val rightOcr = rightPage?.let { ocrPages[it] }
    val onPageSelectionChanged: (Int, TextPage?, TextSelection?) -> Unit = { pageIndex, page, range ->
        pageSelection = if (range == null || page == null) null else PageTextSelection(pageIndex, page, range)
    }
    val placeholderColor = remember(reflowable, pageColors) { resolvePlaceholderColor(reflowable, pageColors) }
    val contentsRows = remember(outline) { flattenOutline(normalizeFlatNumberedChapters(outline)) }
    val minSpreadWidthPx = remember(density) { with(density) { FoliumWidthClass.EXPANDED_FROM.roundToPx() } }
    val spreadGutterPx = remember(density) { with(density) { ReaderSpreadGutterWidth.roundToPx() } }
    val widthClass = FoliumWidthClass.of(with(density) { screenWidthPx.toDp() })

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

        val searchPane = searchOpen && widthClass.showsTwoPanes

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
                    widthClass = widthClass,
                    modifier = Modifier.fillMaxHeight()
                )
            }

        Box(
            Modifier.weight(1f).fillMaxHeight().testTag(ReaderTestTags.PAGE_AREA)
                .onSizeChanged {
                    onSpreadEligibilityChanged(spreadEligible(it.width, it.height, minSpreadWidthPx), spreadGutterPx)
                }
        ) {
            PageSurface(
                state = state,
                pageAspect = pageAspect,
                onIntent = onIntent,
                onViewportChanged = onViewportChanged,
                textPage = textPage,
                selection = currentSelection,
                ocr = ocr,
                search = search,
                rightPage = rightPage,
                rightTextPage = rightTextPage,
                rightSelection = rightSelection,
                rightOcr = rightOcr,
                gutterPx = spreadGutterPx,
                topOcclusionPx = when {
                    !state.state.chromeVisible -> 0f
                    topChromeBottomPx > 0f -> topChromeBottomPx
                    else -> null
                },
                bottomOcclusionPx = bottomChromeHeightPx,
                onSelectionChanged = onPageSelectionChanged,
                onOcrRetry = onOcrRetry,
                placeholderColor = placeholderColor,
                previewFor = previewFor
            )

            if (state.state.chromeVisible) {
                TopChrome(
                    title = title,
                    author = author,
                    zoomScale = state.state.zoom.scale,
                    widthClass = widthClass,
                    contentsOpen = contentsOpen,
                    searchOpen = searchOpen,
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
                    pagesPerView = pagesPerView,
                    widthClass = widthClass,
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
                    widthClass = widthClass,
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
                NavigationSheet(
                    rows = contentsRows,
                    pageCount = state.state.pageCount,
                    currentPage = state.state.currentPage,
                    thumbnails = thumbnails,
                    onThumbnailsWanted = onThumbnailsWanted,
                    onSelect = { pageIndex ->
                        contentsOpen = false
                        onIntent(GestureIntent.FlingToPage(pageIndex))
                    },
                    onDismiss = {
                        contentsOpen = false
                        onThumbnailsWanted(emptyList())
                    },
                    widthClass = widthClass
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
    rightPage: Int?,
    rightTextPage: TextPage?,
    rightSelection: TextSelection?,
    rightOcr: ReaderOcrState?,
    gutterPx: Int,
    topOcclusionPx: Float?,
    bottomOcclusionPx: Float?,
    onSelectionChanged: (Int, TextPage?, TextSelection?) -> Unit,
    onOcrRetry: (Int) -> Unit,
    placeholderColor: Color,
    previewFor: (Int) -> PagePreview? = { null }
) {
    val previewBitmaps = remember { PagePreviewBitmapCache() }
    val pagesPerView = HorizontalViewportReducer.effectivePagesPerView(state.state)
    val currentPage = state.state.currentPage
    val pageCount = state.state.pageCount
    val pagerPageCountValue = pagerPageCount(pageCount, pagesPerView)
    val pager = rememberPagerState(initialPage = pagerPageFor(currentPage, pagesPerView)) { pagerPageCountValue }
    val zoomed = state.state.zoom.scale > MIN_ZOOM_SCALE

    var pageAreaSize by remember { mutableStateOf<IntSize?>(null) }
    val slotWidthPx = if (pagesPerView != 2) null else pageAreaSize?.let {
        ReaderGeometry.slotViewport(ReaderViewport(it.width, it.height), 2, gutterPx).widthPx
    }

    // The pager counts spreads in one mode and pages in the other, and its index outlives the change.
    // It is moved to where the reader already is before anything is read back from it, and the index
    // it then reports is skipped: that one is the reader's own page, not a gesture. Read any earlier,
    // the old index would be taken for a page in the new mode and reported as the reader's position.
    LaunchedEffect(pager, pagesPerView) {
        pager.scrollToPage(pagerPageFor(currentPage, pagesPerView))

        snapshotFlow { pager.currentPage }
            .drop(1)
            .collect { onIntent(GestureIntent.FlingToPage(currentPageFor(it, pagesPerView))) }
    }
    LaunchedEffect(currentPage, pagesPerView) {
        val target = pagerPageFor(currentPage, pagesPerView)
        if (pager.currentPage != target) pager.scrollToPage(target)
    }

    HorizontalPager(
        state = pager,
        userScrollEnabled = !zoomed,
        beyondViewportPageCount = 1,
        modifier = Modifier
            .fillMaxSize()
            .testTag(ReaderTestTags.PAGER)
            .onSizeChanged {
                pageAreaSize = it
                onViewportChanged(ReaderViewport.of(it.width, it.height))
            }
            .transformGestures(zoomed, currentPage, rightPage, state, pageAspect, slotWidthPx, gutterPx, onIntent)
            .tapGestures(zoomed, currentPage, rightPage, state, pageAspect, slotWidthPx, gutterPx, onIntent)
    ) { pagerPage ->
        val leftPage = currentPageFor(pagerPage, pagesPerView)
        val isCurrentUnit = leftPage == currentPage
        val unitRightPage = if (pagesPerView == 2) spreadRightPage(leftPage, pageCount) else null

        val leftContent: @Composable () -> Unit = {
            PageContent(
                pageIndex = leftPage,
                state = state,
                pageAspect = pageAspect,
                textPage = if (isCurrentUnit) textPage else null,
                selection = if (isCurrentUnit) selection else null,
                ocr = if (isCurrentUnit) ocr else null,
                search = if (isCurrentUnit) search else null,
                topOcclusionPx = topOcclusionPx,
                bottomOcclusionPx = bottomOcclusionPx,
                onSelectionChanged = { range -> onSelectionChanged(leftPage, textPage, range) },
                onOcrRetry = { onOcrRetry(leftPage) },
                pageNumberCorner = if (pagesPerView == 2) Alignment.BottomStart else null,
                placeholderColor = placeholderColor,
                previewFor = previewFor,
                previewBitmaps = previewBitmaps
            )
        }

        if (pagesPerView != 2) {
            leftContent()
        } else if (unitRightPage == null) {
            // A lone last page still sits in its own slot rather than spanning the whole page area,
            // so the empty half beside it reads as paper-less space instead of a wider single page.
            SpreadRow(
                slotWidthPx = slotWidthPx ?: 0,
                gutterPx = gutterPx,
                modifier = Modifier.fillMaxSize().testTag(ReaderTestTags.SPREAD_ROW),
                left = leftContent,
                right = { Box(Modifier.fillMaxSize()) }
            )
        } else {
            SpreadRow(
                slotWidthPx = slotWidthPx ?: 0,
                gutterPx = gutterPx,
                modifier = Modifier.fillMaxSize().testTag(ReaderTestTags.SPREAD_ROW),
                left = leftContent,
                right = {
                    PageContent(
                        pageIndex = unitRightPage,
                        state = state,
                        pageAspect = pageAspect,
                        textPage = if (isCurrentUnit) rightTextPage else null,
                        selection = if (isCurrentUnit) rightSelection else null,
                        ocr = if (isCurrentUnit) rightOcr else null,
                        search = if (isCurrentUnit) search else null,
                        topOcclusionPx = topOcclusionPx,
                        bottomOcclusionPx = bottomOcclusionPx,
                        onSelectionChanged = { range -> onSelectionChanged(unitRightPage, rightTextPage, range) },
                        onOcrRetry = { onOcrRetry(unitRightPage) },
                        pageNumberCorner = Alignment.BottomEnd,
                        placeholderColor = placeholderColor,
                        previewFor = previewFor,
                        previewBitmaps = previewBitmaps
                    )
                }
            )
        }
    }
}

private val SpreadDividerThickness = 1.dp

/** The gap between a spread's two page slots, drawn as `gap: 40px` in T-Reader.dc.html. */
private val ReaderSpreadGutterWidth = 40.dp

/**
 * Two page slots side by side, each exactly [slotWidthPx] wide with [gutterPx] between them — the
 * same split [ReaderGeometry.slotViewport] prices a spread's render window against, so a page laid
 * out here is never a pixel off from the size it was actually rasterized for. A hairline divider
 * sits in the gutter so two paper-coloured pages never read as one.
 */
@Composable
private fun SpreadRow(
    slotWidthPx: Int,
    gutterPx: Int,
    modifier: Modifier = Modifier,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    Layout(
        content = {
            left()
            right()
        },
        modifier = modifier
            .drawBehind {
                val dividerX = slotWidthPx + gutterPx / 2f
                drawRect(
                    color = dividerColor,
                    topLeft = Offset(dividerX - SpreadDividerThickness.toPx() / 2f, 0f),
                    size = Size(SpreadDividerThickness.toPx(), size.height)
                )
            }
    ) { measurables, constraints ->
        val slotConstraints = Constraints.fixed(slotWidthPx.coerceAtLeast(1), constraints.maxHeight)
        val leftPlaceable = measurables[0].measure(slotConstraints)
        val rightPlaceable = measurables[1].measure(slotConstraints)

        layout(constraints.maxWidth, constraints.maxHeight) {
            leftPlaceable.place(0, 0)
            rightPlaceable.place(slotWidthPx + gutterPx, 0)
        }
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
    rightPage: Int?,
    state: ReaderUiState<BorrowedPage>,
    pageAspect: (Int) -> Float,
    slotWidthPx: Int?,
    gutterPx: Int,
    onIntent: (GestureIntent) -> Unit
): Modifier {
    val isZoomed by rememberUpdatedState(zoomed)
    val intent by rememberUpdatedState(onIntent)
    val currentState by rememberUpdatedState(state)
    val currentPageIndex by rememberUpdatedState(currentPage)
    val currentRightPage by rememberUpdatedState(rightPage)
    val currentPageAspect by rememberUpdatedState(pageAspect)
    val currentSlotWidthPx by rememberUpdatedState(slotWidthPx)
    val currentGutterPx by rememberUpdatedState(gutterPx)

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
                        intent(zoomIntent(
                            centroid, gestureZoom, currentPageIndex, currentRightPage, currentState, currentPageAspect,
                            currentSlotWidthPx, currentGutterPx
                        ))
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

/**
 * The [GestureIntent.ZoomBy] a pinch or a double tap at [centroid] should dispatch. Outside a fitted
 * spread ([slotWidthPx] `null`) this is exactly the single-page transform it always was: the whole
 * pager viewport, [currentPage]'s own aspect, no [GestureIntent.ZoomBy.focusPage]. While a spread is
 * fitted, [centroid] is first resolved to one of its two slots (see [spreadSlotAt]), and the focal
 * point is then computed in *that* slot's own page space — a slot viewport exactly [slotWidthPx]
 * wide, the same split the spread was actually laid out and rasterized against — so the reducer
 * collapses onto the page the reader's fingers were actually on rather than always the left one.
 */
private fun PointerInputScope.zoomIntent(
    centroid: Offset,
    gestureZoom: Float,
    currentPage: Int,
    rightPage: Int?,
    state: ReaderUiState<BorrowedPage>,
    pageAspect: (Int) -> Float,
    slotWidthPx: Int?,
    gutterPx: Int
): GestureIntent.ZoomBy {
    val hit = slotWidthPx?.let { spreadSlotAt(centroid.x, it, gutterPx) }
    val focusPage = when {
        hit == null -> null
        hit.slotIndex == 1 && rightPage != null -> rightPage
        else -> currentPage
    }
    val focalPageIndex = focusPage ?: currentPage
    val localX = hit?.localXPx ?: centroid.x
    val slotViewport = if (slotWidthPx != null) {
        ReaderViewport.of(slotWidthPx, size.height)
    } else {
        ReaderViewport.of(size.width, size.height)
    }
    val focal = slotViewport?.let {
        val layout = ReaderGeometry.layout(it, pageAspect(focalPageIndex), state.state.zoom, state.state.fitMode)
        ReaderGeometry.viewportToPage(layout, ViewportPoint(localX, centroid.y), clampToPage = true)
    } ?: PageSpacePoint(.5f, .5f)
    return GestureIntent.ZoomBy(factor = gestureZoom, focal = focal, focusPage = focusPage)
}

private fun PointerInputScope.panIntent(pan: Offset) =
    GestureIntent.PanBy(pan.x / size.width, pan.y / size.height)

/**
 * Tapping the outer quarter of either edge turns the page and tapping the middle shows or hides the
 * chrome, so navigation stays reachable one-handed without any control being on screen. While
 * zoomed the edges lose that meaning, since a tap there is far more likely to be aimed at the page.
 * The edge fractions are measured against the whole page area regardless of a fitted spread, exactly
 * as they always were: a spread turns by the whole spread either way, so its two slots need no
 * separate edges of their own.
 */
@Composable
private fun Modifier.tapGestures(
    zoomed: Boolean,
    currentPage: Int,
    rightPage: Int?,
    state: ReaderUiState<BorrowedPage>,
    pageAspect: (Int) -> Float,
    slotWidthPx: Int?,
    gutterPx: Int,
    onIntent: (GestureIntent) -> Unit
): Modifier {
    val intent by rememberUpdatedState(onIntent)
    val currentPageIndex by rememberUpdatedState(currentPage)
    val currentRightPage by rememberUpdatedState(rightPage)
    val currentState by rememberUpdatedState(state)
    val currentPageAspect by rememberUpdatedState(pageAspect)
    val currentSlotWidthPx by rememberUpdatedState(slotWidthPx)
    val currentGutterPx by rememberUpdatedState(gutterPx)

    return pointerInput(zoomed) {
        detectTapGestures(
            onDoubleTap = { position ->
                if (zoomed) intent(GestureIntent.ResetZoom)
                else intent(zoomIntent(
                    position, DOUBLE_TAP_ZOOM, currentPageIndex, currentRightPage, currentState,
                    currentPageAspect, currentSlotWidthPx, currentGutterPx
                ))
            },
            onTap = { position ->
                val horizontal = position.x / size.width
                when {
                    zoomed -> intent(GestureIntent.ToggleChrome)
                    horizontal < EDGE_TAP_FRACTION -> intent(GestureIntent.PageBack)
                    horizontal > 1f - EDGE_TAP_FRACTION -> intent(GestureIntent.PageForward)
                    else -> intent(GestureIntent.ToggleChrome)
                }
            }
        )
    }
}

/** What a page slot has to draw, decided before anything about drawing it is touched. */
internal enum class PageSlotContent {
    /** This page has a detail raster, a base raster, or both, of its own. */
    RASTER,

    /** Nothing of this page's own has landed yet, but the current page's raster survived a
     *  hand-over and belongs to this exact slot — see [CarriedPreview]. */
    CARRIED,

    /** Nothing of this page's own, and nothing carried for it, has landed yet, but a blurred
     *  stand-in for this exact page is already available — see [com.folium.reader.core.preview.PagePreview]. */
    PREVIEW,

    /** Nothing has ever been drawn for this page: the empty sheet stands in for it. */
    PLACEHOLDER,

    /** This page just failed and has nothing of its own to fall back on either; the failure
     *  banner is the only thing drawn for it. */
    NONE
}

/**
 * Decides [PageSlotContent] for one page slot without touching Compose, so the decision itself can
 * be unit-tested on the JVM independently of [PageContent]'s drawing.
 *
 * A carried preview is only ever a stand-in for the exact page it was carried for: it is drawn only
 * when [carriedPageIndex] equals [slotPageIndex], never in a slot it merely happens to be empty for
 * — see [CarriedPreview]'s own doc for why a mismatch here must never be papered over with someone
 * else's page. [hasPreview] is checked only once neither a raster nor a carried hand-over is
 * available, so a blurred stand-in never flashes in front of something sharper this slot already has.
 */
internal fun pageSlotContent(
    hasDetail: Boolean,
    hasBase: Boolean,
    carriedPageIndex: Int?,
    slotPageIndex: Int,
    failed: Boolean,
    hasPreview: Boolean
): PageSlotContent = when {
    hasDetail || hasBase -> PageSlotContent.RASTER
    failed -> PageSlotContent.NONE
    carriedPageIndex == slotPageIndex -> PageSlotContent.CARRIED
    hasPreview -> PageSlotContent.PREVIEW
    else -> PageSlotContent.PLACEHOLDER
}

/**
 * What a page's sheet is drawn as before anything of its own has landed: a reflowable document's
 * own resolved page colour when there is one, so a dark page's placeholder is dark rather than a
 * flash of paper on the way to it; [FoliumPaper] otherwise, exactly as before — a fixed-layout
 * document has no page colour of its own to stand in with, and neither does a reflowable one before
 * its appearance colours have ever resolved.
 */
internal fun resolvePlaceholderColor(reflowable: Boolean, pageColors: ReflowPageColors?): Color =
    if (reflowable && pageColors != null) pageColors.toBackgroundColor() else FoliumPaper

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
    onOcrRetry: () -> Unit,
    /** The outer corner a spread's own slot shows this page's number in — see [PageNumberCaption]. */
    pageNumberCorner: Alignment? = null,
    /** What [PageSlotContent.PLACEHOLDER] fills the sheet with — see [resolvePlaceholderColor]. */
    placeholderColor: Color = FoliumPaper,
    /** A blurred stand-in for a page nothing of its own has landed for yet — see [PageSlotContent.PREVIEW]. */
    previewFor: (Int) -> PagePreview? = { null },
    previewBitmaps: PagePreviewBitmapCache
) {
    val page = state.pages[pageIndex]
    val basePage = state.basePages[pageIndex]
    val carried = state.carriedPreview
    val carriedImage = remember(carried) { carried?.value?.bitmap?.asImageBitmap() }
    val loadingDescription = stringResource(R.string.reader_page_loading, pageIndex + 1)
    val image = remember(page) { page?.bitmap?.asImageBitmap() }
    val baseImage = remember(basePage) { basePage?.bitmap?.asImageBitmap() }
    val failed = pageIndex in state.failedPages
    val preview = previewFor(pageIndex)
    val previewImage = remember(preview) { preview?.let { previewBitmaps.imageFor(pageIndex, it) } }
    val slotContent = pageSlotContent(
        hasDetail = image != null,
        hasBase = baseImage != null,
        carriedPageIndex = carried?.pageIndex,
        slotPageIndex = pageIndex,
        failed = failed,
        hasPreview = preview != null
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag(ReaderTestTags.page(pageIndex)),
        contentAlignment = Alignment.Center
    ) {
        when (slotContent) {
            PageSlotContent.RASTER -> Canvas(
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

            // The current page's own raster, surviving a hand-over under its own page index — see
            // [CarriedPreview]. Drawn at its own page's shape, which here is necessarily this slot's.
            // The sentence for the page that is actually being waited on is still read out.
            PageSlotContent.CARRIED -> Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = loadingDescription }
                    .testTag(ReaderTestTags.pageCarried(pageIndex))
            ) {
                val viewport = ReaderViewport.of(size.width.roundToInt(), size.height.roundToInt())
                    ?: return@Canvas
                val layout = ReaderGeometry.layout(
                    viewport,
                    pageAspect(pageIndex),
                    state.state.zoom,
                    state.state.fitMode
                )

                drawTile(layout, PageSpaceRect(0f, 0f, 1f, 1f), requireNotNull(carriedImage), FilterQuality.Low)
            }

            // Nothing of this page's own, and nothing carried for it, has landed yet, but a blurred
            // stand-in for this exact page already exists — see PagePreviewFile. Drawn over the same
            // placeholder sheet the reader would otherwise see bare, at FilterQuality.Low: it is
            // stretched from 32 pixels wide, so it reads as a soft field of color, not detail.
            PageSlotContent.PREVIEW -> Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = loadingDescription }
                    .testTag(ReaderTestTags.pagePreview(pageIndex))
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
                    color = placeholderColor,
                    topLeft = Offset(sheet.left, sheet.top),
                    size = Size(sheet.width, sheet.height)
                )
                drawTile(layout, PageSpaceRect(0f, 0f, 1f, 1f), requireNotNull(previewImage), FilterQuality.Low)
            }

            // Nothing has ever been drawn for this document yet, so the page is drawn as the page it
            // will be: the sheet, in its place, at its proportions.
            PageSlotContent.PLACEHOLDER -> Canvas(
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
                    color = placeholderColor,
                    topLeft = Offset(sheet.left, sheet.top),
                    size = Size(sheet.width, sheet.height)
                )
            }

            PageSlotContent.NONE -> Unit
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

        if (pageNumberCorner != null) {
            PageNumberCaption(pageIndex, modifier = Modifier.align(pageNumberCorner))
        }
    }
}

/** A spread's own per-slot page number, in its outer bottom corner, styled like [PageThumbnailCell]'s. */
@Composable
private fun PageNumberCaption(pageIndex: Int, modifier: Modifier = Modifier) {
    Text(
        text = (pageIndex + 1).toString(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(6.dp)
            .testTag(ReaderTestTags.pageNumberCaption(pageIndex))
    )
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
                    modifier = Modifier.heightIn(min = FoliumSpacing.touchTarget)
                        .testTag(ReaderTestTags.ocrRetry(pageIndex))
                ) {
                    Text(stringResource(R.string.reader_ocr_retry))
                }
            }
        }
    }
}

/**
 * How every match on the page is marked: an underline that reads the same on any panel, plus a
 * colour wash that is an addition where the panel can show one.
 *
 * The mark does not change between the active match and the rest: T-Busqueda.dc.html's two-page
 * spread carries the identical underline and wash on both of the matches it draws, whichever of
 * them is current. Which match is current is what the results list and the "N of M" counter say,
 * not a heavier page mark, so this style takes no `active` input on purpose.
 *
 * The wash itself is dropped in the e-ink appearance modes: T-Reglas.dc.html "03 · E-INK" states it
 * plainly — "El subrayado es el piso; el lavado es el extra" — a wash is a improvement where there
 * is colour to show it in, never the mechanism reading a mark depends on.
 */
internal data class SearchMarkStyle(val showsWash: Boolean)

internal fun searchMarkStyle(eInk: Boolean): SearchMarkStyle = SearchMarkStyle(showsWash = !eInk)

/**
 * The wash's opacity against the signal colour, read from T-Busqueda.dc.html's
 * `color-mix(in srgb, {{signal}} 14%, transparent)`.
 */
private const val SearchWashAlpha = 0.14f
private val SearchUnderlineThickness = 3.dp

/** Paint-only search layer: Canvas installs no pointer input and therefore cannot consume gestures. */
@Composable
private fun ReaderSearchOverlay(search: ReaderSearchState?, pageIndex: Int, layout: ViewportLayout) {
    val pageMatches = search?.matches.orEmpty().filter { it.pageIndex == pageIndex }
    if (pageMatches.isEmpty()) return
    val markStyle = searchMarkStyle(LocalFoliumEInk.current)
    val signal = MaterialTheme.colorScheme.tertiary
    val wash = signal.copy(alpha = SearchWashAlpha)
    Canvas(Modifier.fillMaxSize().testTag(ReaderTestTags.SEARCH_HIGHLIGHTS)) {
        val underlineThickness = SearchUnderlineThickness.toPx()
        pageMatches.forEach { match ->
            match.boxes.forEach { box ->
                val rect = ReaderGeometry.destination(layout, box)
                if (markStyle.showsWash) {
                    drawRect(
                        color = wash,
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(rect.width, rect.height)
                    )
                }
                drawRect(
                    color = signal,
                    topLeft = Offset(rect.left, rect.top + rect.height - underlineThickness),
                    size = Size(rect.width, underlineThickness)
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
    widthClass: FoliumWidthClass,
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

    // The floating strip shares the top bar's own edge-to-edge Surface and width-class padding
    // (ChromeBar) so no stub of the bar's rule shows past the panel's sides; the two-pane column
    // stays a smaller card inset from the page it sits beside.
    val stripHorizontalPadding = if (widthClass != FoliumWidthClass.COMPACT) 28.dp else 12.dp

    Box(modifier.safeDrawingPadding().padding(if (pane) 8.dp else 0.dp)) {
        Surface(
            modifier = if (pane) {
                Modifier.width(SearchPaneWidth).fillMaxHeight()
                    .testTag(ReaderTestTags.SEARCH_ROOT)
            } else {
                Modifier.fillMaxWidth()
                    .testTag(ReaderTestTags.SEARCH_ROOT)
            },
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Column(
                Modifier.fillMaxWidth().padding(
                    horizontal = if (pane) 4.dp else stripHorizontalPadding,
                    vertical = 2.dp
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val fieldTint = MaterialTheme.colorScheme.onSurface
                    val fieldFocus = remember { FocusRequester() }

                    // Opening search is asking to type: the field takes focus, and with it the
                    // keyboard, the way the library's search field does.
                    LaunchedEffect(Unit) { fieldFocus.requestFocus() }

                    BasicTextField(
                        value = spec.query,
                        onValueChange = { value -> spec = spec.copy(query = value); onQuery(spec) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge
                            .copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                        modifier = Modifier
                            .weight(1f)
                            .height(SearchFieldHeight)
                            .foliumBorder(SearchFieldBorder, MaterialTheme.colorScheme.onSurface)
                            .padding(horizontal = 12.dp)
                            .focusRequester(fieldFocus)
                            .testTag(ReaderTestTags.SEARCH_FIELD),
                        decorationBox = { field ->
                            Row(
                                modifier = Modifier.fillMaxHeight(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Canvas(Modifier.size(SearchFieldGlyphSize)) { drawMagnifier(fieldTint) }
                                Spacer(Modifier.width(SearchFieldGlyphGap))
                                Box(contentAlignment = Alignment.CenterStart) {
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
                        }
                    )
                    Box {
                        GlyphButton(
                            glyph = { tint -> drawKebab(tint) },
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
                            FoliumDivider.Horizontal(color = MaterialTheme.colorScheme.outlineVariant)
                            SearchOptionMenuItem(
                                selected = spec.mode == TextSearchMode.REGEX,
                                label = stringResource(R.string.reader_search_regex),
                                tag = ReaderTestTags.SEARCH_REGEX,
                                role = Role.RadioButton
                            ) { spec = spec.copy(mode = TextSearchMode.REGEX); onQuery(spec) }
                            FoliumDivider.Horizontal(color = MaterialTheme.colorScheme.onSurface)
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
                            FoliumDivider.Horizontal(color = MaterialTheme.colorScheme.outlineVariant)
                            SearchOptionMenuItem(
                                selected = spec.wholeWord,
                                label = stringResource(R.string.reader_search_whole_word),
                                tag = ReaderTestTags.SEARCH_WHOLE_WORD,
                                role = Role.Checkbox
                            ) { spec = spec.copy(wholeWord = !spec.wholeWord); onQuery(spec) }
                        }
                    }
                    GlyphButton(
                        glyph = { tint -> drawClose(tint) },
                        glyphSize = SearchCloseGlyphSize,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(
                            position,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            modifier = Modifier.testTag(ReaderTestTags.SEARCH_POSITION)
                        )
                        Text(
                            coverageText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (coverage?.error == true) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(ReaderTestTags.SEARCH_COVERAGE)
                        )
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    when {
                        state?.ocrPlan?.canResume == true ->
                            TextButton(
                                shape = MaterialTheme.shapes.small,
                                onClick = onOcrResume,
                                modifier = Modifier.heightIn(min = FoliumSpacing.touchTarget)
                                    .testTag(ReaderTestTags.SEARCH_OCR_RESUME)
                            ) { Text(stringResource(R.string.reader_search_ocr_resume)) }
                        state?.ocrPlan?.canPause == true -> TextButton(
                            onClick = onOcrPause,
                            modifier = Modifier.heightIn(min = FoliumSpacing.touchTarget)
                                .testTag(ReaderTestTags.SEARCH_OCR_PAUSE)
                        ) { Text(stringResource(R.string.reader_search_ocr_pause)) }
                    }
                    GlyphButton(
                        glyph = { tint -> drawChevron(tint, pointingRight = false) },
                        description = stringResource(R.string.reader_search_previous),
                        onClick = onPrevious,
                        testTag = ReaderTestTags.SEARCH_PREVIOUS,
                        enabled = activeIndex != null && activeIndex > 0
                    )
                    GlyphButton(
                        glyph = { tint -> drawChevron(tint, pointingRight = true) },
                        description = stringResource(R.string.reader_search_next),
                        onClick = onNext,
                        testTag = ReaderTestTags.SEARCH_NEXT,
                        enabled = activeIndex != null && activeIndex < (state?.matches?.lastIndex ?: -1)
                    )
                }
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
                    .foliumRule(FoliumRuleEdge.BOTTOM, 1.dp, MaterialTheme.colorScheme.outlineVariant)
                    .clickable { onSelect(match.identity) }
                    .heightIn(min = FoliumSpacing.touchTarget)
                    .padding(horizontal = 8.dp, vertical = 12.dp)
            ) {
                Column(Modifier.width(SearchResultPageWidth)) {
                    Text(
                        text = "${match.pageIndex + 1}",
                        style = MaterialTheme.typography.titleSmall,
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
                    style = FoliumType.BodyMid,
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
        text = { Text(label, style = FoliumType.BodyMid) },
        onClick = onClick,
        trailingIcon = {
            if (selected) {
                val tint = MaterialTheme.colorScheme.onSurface
                Canvas(Modifier.size(GlyphIconSize)) { drawCheck(tint) }
            }
        },
        modifier = Modifier.heightIn(min = FoliumSpacing.touchTarget).semantics {
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
    widthClass: FoliumWidthClass,
    contentsOpen: Boolean,
    searchOpen: Boolean,
    onIntent: (GestureIntent) -> Unit,
    onContentsRequested: () -> Unit,
    onSearchRequested: () -> Unit,
    onTypographyRequested: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier
) {
    val zoomed = zoomScale > MIN_ZOOM_SCALE
    val zoomLabel = stringResource(R.string.reader_zoom_level, (zoomScale * 100).roundToInt())
    val contentsLabel = stringResource(R.string.reader_contents)
    val searchLabel = stringResource(R.string.reader_search)

    ChromeBar(
        modifier = modifier.testTag(ReaderTestTags.CHROME_TOP),
        insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        dividerBelow = true,
        widthClass = widthClass
    ) {
        GlyphButton(
            glyph = { tint -> drawChevron(tint, pointingRight = false) },
            description = stringResource(R.string.reader_back),
            onClick = onBack,
            testTag = ReaderTestTags.BACK
        )

        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                text = title,
                style = FoliumType.BodyMidMedium,
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
                    .sizeIn(minHeight = FoliumSpacing.touchTarget)
                    .semantics { contentDescription = zoomLabel }
                    .testTag(ReaderTestTags.ZOOM)
            ) {
                Text(zoomLabel, style = MaterialTheme.typography.bodyMedium)
            }
        }

        val composition = topBarComposition(widthClass)

        composition.directActions.forEach { action ->
            when (action) {
                TopBarSecondaryAction.CONTENTS -> ChromeGlyphToggle(
                    glyph = { tint -> drawContentsGlyph(tint) },
                    description = contentsLabel,
                    onClick = onContentsRequested,
                    testTag = ReaderTestTags.TOP_BAR_CONTENTS,
                    active = contentsOpen
                )

                TopBarSecondaryAction.SEARCH -> ChromeGlyphToggle(
                    glyph = { tint -> drawSearchGlyph(tint) },
                    description = searchLabel,
                    onClick = onSearchRequested,
                    testTag = ReaderTestTags.TOP_BAR_SEARCH,
                    active = searchOpen
                )

                TopBarSecondaryAction.BOOK_SETTINGS -> TypographyButton(onClick = onTypographyRequested)
            }
        }

        if (composition.overflowShown) {
            OverflowMenu(onContentsRequested, onSearchRequested, onTypographyRequested)
        }
    }
}

/** Which mark a direct top-bar action draws. */
internal enum class TopBarSecondaryAction { CONTENTS, SEARCH, BOOK_SETTINGS }

/** Everything a [TopChrome] draws for what is not paging: its direct actions, in order, and whether it also draws an overflow. */
internal data class TopBarComposition(
    val directActions: List<TopBarSecondaryAction>,
    val overflowShown: Boolean
)

/**
 * A window wide enough for [FoliumWidthClass.MEDIUM] or [FoliumWidthClass.EXPANDED] draws Contents,
 * Search and the book settings "Aa" mark directly in the bar, in that order, for every document —
 * P-Reader.dc.html and T-Reader.dc.html show them as plain icon buttons, never behind a menu. There
 * is no overflow at that width: every action the bar could offer is already drawn.
 *
 * [FoliumWidthClass.COMPACT] draws none of them directly — S-Reader.dc.html collapses all three
 * behind the kebab mark instead, see [OverflowMenu].
 */
internal fun topBarComposition(widthClass: FoliumWidthClass): TopBarComposition =
    if (widthClass == FoliumWidthClass.COMPACT) {
        TopBarComposition(directActions = emptyList(), overflowShown = true)
    } else {
        TopBarComposition(
            directActions = listOf(
                TopBarSecondaryAction.CONTENTS,
                TopBarSecondaryAction.SEARCH,
                TopBarSecondaryAction.BOOK_SETTINGS
            ),
            overflowShown = false
        )
    }

/**
 * The book settings sheet's own entry point in the bar, drawn as the design's "Aa" mark rather than
 * the overflow's plain text row (M-Tipografia.dc.html). Only drawn once the bar is wide enough to
 * draw its actions directly — see [topBarComposition]; at [FoliumWidthClass.COMPACT] a reader reaches
 * the same sheet through the overflow's "Book settings" row instead.
 */
@Composable
private fun TypographyButton(onClick: () -> Unit) {
    val description = stringResource(R.string.reader_book_settings)
    val tint = MaterialTheme.colorScheme.onSurface

    TextButton(
        shape = MaterialTheme.shapes.small,
        onClick = onClick,
        modifier = Modifier
            .sizeIn(minWidth = FoliumSpacing.touchTarget, minHeight = FoliumSpacing.touchTarget)
            .semantics { contentDescription = description }
            .testTag(ReaderTestTags.TYPOGRAPHY)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Aa", style = MaterialTheme.typography.labelLarge, color = tint)
            Spacer(Modifier.height(3.dp))
            Box(Modifier.width(TypographyGlyphUnderlineWidth).height(TypographyGlyphUnderlineThickness).background(tint))
        }
    }
}

private val TypographyGlyphUnderlineWidth = 20.dp
private val TypographyGlyphUnderlineThickness = 2.dp

/**
 * A top-bar glyph button for a panel that can be open or closed, drawn with the same reserved
 * underline every top-bar mark carries in T-Reader.dc.html and P-Reader.dc.html — ink when
 * [active], otherwise transparent, exactly as T-Tipografia.dc.html shows the "Aa" mark once its own
 * sheet is open (see [TypographyButton]).
 */
@Composable
private fun ChromeGlyphToggle(
    glyph: DrawScope.(Color) -> Unit,
    description: String,
    onClick: () -> Unit,
    testTag: String,
    active: Boolean
) {
    val tint = MaterialTheme.colorScheme.onSurface
    val underline = if (active) tint else Color.Transparent

    TextButton(
        shape = MaterialTheme.shapes.small,
        onClick = onClick,
        modifier = Modifier
            .sizeIn(minWidth = FoliumSpacing.touchTarget, minHeight = FoliumSpacing.touchTarget)
            .semantics { contentDescription = description }
            .testTag(testTag)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(GlyphIconSize)) { glyph(tint) }
            Spacer(Modifier.height(3.dp))
            Box(Modifier.width(TypographyGlyphUnderlineWidth).height(TypographyGlyphUnderlineThickness).background(underline))
        }
    }
}

/**
 * The bar's own actions collapsed behind a kebab mark, drawn only at [FoliumWidthClass.COMPACT] — see
 * [topBarComposition]. Holds exactly the direct actions a wider bar would have drawn instead: Search,
 * Contents and Book settings, every one of them offered whatever the document is. A fixed-layout
 * document's fit-mode choice is not among them; it lives inside the book settings sheet itself, see
 * [BookSettingsSheet]'s own doc.
 */
@Composable
private fun OverflowMenu(
    onContentsRequested: () -> Unit,
    onSearchRequested: () -> Unit,
    onTypographyRequested: () -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Box {
        GlyphButton(
            glyph = { tint -> drawKebab(tint) },
            description = stringResource(R.string.reader_menu),
            onClick = { open = true },
            testTag = ReaderTestTags.OVERFLOW
        )

        FoliumMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reader_search), style = FoliumType.BodyMid) },
                onClick = { open = false; onSearchRequested() },
                modifier = Modifier.sizeIn(minHeight = FoliumSpacing.touchTarget).testTag(ReaderTestTags.SEARCH)
            )
            FoliumDivider.Horizontal(color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.reader_contents), style = FoliumType.BodyMid)
                },
                onClick = {
                    open = false
                    onContentsRequested()
                },
                modifier = Modifier.sizeIn(minHeight = FoliumSpacing.touchTarget).testTag(ReaderTestTags.CONTENTS)
            )

            FoliumDivider.Horizontal(color = MaterialTheme.colorScheme.outlineVariant)

            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.reader_book_settings), style = FoliumType.BodyMid)
                },
                onClick = {
                    open = false
                    onTypographyRequested()
                },
                modifier = Modifier.sizeIn(minHeight = FoliumSpacing.touchTarget).testTag(ReaderTestTags.BOOK_SETTINGS)
            )
        }
    }
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
    pagesPerView: Int,
    widthClass: FoliumWidthClass,
    onIntent: (GestureIntent) -> Unit,
    onJumpRequested: () -> Unit,
    modifier: Modifier
) {
    val spoken = spreadSpokenPosition(currentPage, pageCount, pagesPerView)
    val jumpLabel = stringResource(R.string.reader_jump_action)

    ChromeBar(
        modifier = modifier.testTag(ReaderTestTags.CHROME_BOTTOM),
        insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        dividerBelow = false,
        widthClass = widthClass
    ) {
        GlyphButton(
            glyph = { tint -> drawChevron(tint, pointingRight = false) },
            description = stringResource(R.string.reader_previous_page),
            onClick = { onIntent(GestureIntent.PageBack) },
            testTag = ReaderTestTags.PREVIOUS,
            enabled = currentPage > 0
        )

        PositionScrubber(
            currentPage = currentPage,
            pageCount = pageCount,
            pagesPerView = pagesPerView,
            widthClass = widthClass,
            spoken = spoken,
            jumpLabel = jumpLabel,
            onJumpRequested = onJumpRequested,
            onSeek = { page -> onIntent(GestureIntent.FlingToPage(page)) },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )

        GlyphButton(
            glyph = { tint -> drawChevron(tint, pointingRight = true) },
            description = stringResource(R.string.reader_next_page),
            onClick = { onIntent(GestureIntent.PageForward) },
            testTag = ReaderTestTags.NEXT,
            enabled = currentPage < pageCount - 1
        )
    }
}

/**
 * The bottom bar's spoken position: exactly [R.string.reader_page_position], unchanged, outside a
 * fitted spread and for a spread's own lone last page, and the range [R.plurals.reader_page_position_spread]
 * reads aloud for an actual pair — see [spreadPositionLabel].
 */
@Composable
private fun spreadSpokenPosition(currentPage: Int, pageCount: Int, pagesPerView: Int): String {
    if (pagesPerView != 2) return stringResource(R.string.reader_page_position, currentPage + 1, pageCount)
    val label = spreadPositionLabel(currentPage, pageCount, pagesPerView)
    val rightPage = label.rightPage
    return if (rightPage == null) {
        stringResource(R.string.reader_page_position, label.leftPage, pageCount)
    } else {
        pluralStringResource(R.plurals.reader_page_position_spread, 2, label.leftPage, rightPage, pageCount)
    }
}

/** The scrubber's own plain indicator — see [spreadSpokenPosition] for the spoken form. */
@Composable
private fun spreadIndicatorText(page: Int, pageCount: Int, pagesPerView: Int): String {
    if (pagesPerView != 2) return stringResource(R.string.reader_page_indicator, page + 1, pageCount)
    val label = spreadPositionLabel(page, pageCount, pagesPerView)
    val rightPage = label.rightPage
    return if (rightPage == null) {
        stringResource(R.string.reader_page_indicator, label.leftPage, pageCount)
    } else {
        stringResource(R.string.reader_page_indicator_spread, label.leftPage, rightPage, pageCount)
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
    pagesPerView: Int,
    widthClass: FoliumWidthClass,
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
            .sizeIn(minHeight = FoliumSpacing.touchTarget)
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

        Spacer(Modifier.height(trackToIndicatorGap(widthClass)))

        Text(
            text = spreadIndicatorText(shown, pageCount, pagesPerView),
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

/**
 * The gap between the scrubber's track and its position label: 7dp on a phone
 * (S-Reader.dc.html, S-ReaderRaster.dc.html), 8dp from a small tablet up (P-Reader.dc.html,
 * T-Reader.dc.html).
 */
private fun trackToIndicatorGap(widthClass: FoliumWidthClass): Dp =
    if (widthClass == FoliumWidthClass.COMPACT) 7.dp else 8.dp

private val ScrubberHeight = 24.dp
private val TrackWeight = 4.dp
private val HandleWidth = 3.dp
private val HandleHeight = 14.dp

/**
 * A control the size of a touch target that reads as a single drawn mark, never a typographic
 * character: S-Primitivos.dc.html "06 · ICONOS" draws every glyph in a 20dp box at a 1.6dp stroke
 * rather than shipping it as text, which is also the one place a font's own hinting could pull a
 * mark off the pixel grid the rest of the system is drawn on. The glyph carries no meaning to
 * anything that cannot see it, so the label it stands for is always attached as its description.
 */
@Composable
private fun GlyphButton(
    glyph: DrawScope.(Color) -> Unit,
    description: String,
    onClick: () -> Unit,
    testTag: String,
    enabled: Boolean = true,
    glyphSize: Dp = GlyphIconSize,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    val enabledTint = if (enabled) tint else tint.copy(alpha = 0.38f)

    TextButton(
        shape = MaterialTheme.shapes.small,
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .sizeIn(minWidth = FoliumSpacing.touchTarget, minHeight = FoliumSpacing.touchTarget)
            .semantics { contentDescription = description }
            .testTag(testTag)
    ) {
        Canvas(Modifier.size(glyphSize)) { glyph(enabledTint) }
    }
}

/**
 * The chevron every reader control that steps one item at a time draws — a page turn, a search hit —
 * at the system's icon geometry: a 20-unit box, a 1.6dp round stroke (S-Reader.dc.html,
 * S-BusquedaTira.dc.html).
 */
private fun DrawScope.drawChevron(tint: Color, pointingRight: Boolean) {
    val unit = size.width / 20f
    val base = if (pointingRight) 8f else 12f
    val tip = if (pointingRight) 14f else 6f
    val path = Path().apply {
        moveTo(base * unit, 4f * unit)
        lineTo(tip * unit, 10f * unit)
        lineTo(base * unit, 16f * unit)
    }
    drawPath(
        path = path,
        color = tint,
        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/**
 * The reader's own contents mark in the top bar, direct rather than behind the overflow: three
 * horizontal strokes, the last shorter (S-Reader.dc.html, P-Reader.dc.html, T-Reader.dc.html).
 */
private fun DrawScope.drawContentsGlyph(tint: Color) {
    val unit = size.width / 20f
    val stroke = 1.6.dp.toPx()
    drawLine(tint, Offset(3.5f * unit, 5f * unit), Offset(16.5f * unit, 5f * unit), stroke, cap = StrokeCap.Round)
    drawLine(tint, Offset(3.5f * unit, 10f * unit), Offset(16.5f * unit, 10f * unit), stroke, cap = StrokeCap.Round)
    drawLine(tint, Offset(3.5f * unit, 15f * unit), Offset(11f * unit, 15f * unit), stroke, cap = StrokeCap.Round)
}

/**
 * The reader's own search mark in the top bar: a circle with a trailing handle, centred and scaled
 * to the same 20-unit box every other top-bar glyph uses (S-Reader.dc.html, T-Reader.dc.html). This
 * is a different geometry from [drawMagnifier], which draws the smaller, off-centre mark a search
 * field's own leading icon carries.
 */
private fun DrawScope.drawSearchGlyph(tint: Color) {
    val unit = size.width / 20f
    val stroke = 1.6.dp.toPx()
    drawCircle(color = tint, radius = 5.8f * unit, center = Offset(9f * unit, 9f * unit), style = Stroke(width = stroke))
    drawLine(tint, Offset(13.4f * unit, 13.4f * unit), Offset(17.5f * unit, 17.5f * unit), stroke, cap = StrokeCap.Round)
}

/** The reader's own overflow mark and the search strip's options mark: three filled dots, stacked. */
private fun DrawScope.drawKebab(tint: Color) {
    val unit = size.width / 20f
    val radius = 1.5f * unit
    listOf(4f, 10f, 16f).forEach { y ->
        drawCircle(color = tint, radius = radius, center = Offset(10f * unit, y * unit))
    }
}

/**
 * The search strip's close mark, drawn smaller than every other icon and in the muted role rather
 * than ink (S-BusquedaTira.dc.html): dismissing the search is the one action in the strip the design
 * treats as secondary to reading the results, not as another primary control beside them.
 */
private fun DrawScope.drawClose(tint: Color) {
    val unit = size.width / 20f
    val stroke = 1.8.dp.toPx()
    drawLine(tint, Offset(4.5f * unit, 4.5f * unit), Offset(15.5f * unit, 15.5f * unit), stroke, cap = StrokeCap.Round)
    drawLine(tint, Offset(15.5f * unit, 4.5f * unit), Offset(4.5f * unit, 15.5f * unit), stroke, cap = StrokeCap.Round)
}

/** The check a selected search mode or fit mode draws in its own menu row (T-Reader.dc.html). */
private fun DrawScope.drawCheck(tint: Color) {
    val unit = size.width / 20f
    val path = Path().apply {
        moveTo(4f * unit, 10.5f * unit)
        lineTo(8f * unit, 14.5f * unit)
        lineTo(16f * unit, 5.5f * unit)
    }
    drawPath(
        path = path,
        color = tint,
        style = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/**
 * The magnifier every search field carries as its leading mark (S-Componentes.dc.html "03 · CAMPO"),
 * missing from the reader's own field even though the library's search field already draws it.
 */
private fun DrawScope.drawMagnifier(tint: Color) {
    val unit = size.width / 18f
    drawCircle(
        color = tint,
        radius = 5.8f * unit,
        center = Offset(7.6f * unit, 7.6f * unit),
        style = Stroke(width = 1.6.dp.toPx())
    )
    drawLine(
        color = tint,
        start = Offset(11.6f * unit, 11.6f * unit),
        end = Offset(16.5f * unit, 16.5f * unit),
        strokeWidth = 1.6.dp.toPx(),
        cap = StrokeCap.Round
    )
}

/**
 * Controls sit against the top and bottom edges so both ends stay within one-handed reach on a
 * phone, and are separated from the page by a hairline rather than by elevation.
 *
 * Every measurement below comes from the reader artboards' own bar rows: S-Reader.dc.html and
 * S-ReaderRaster.dc.html for a phone, P-Reader.dc.html and T-Reader.dc.html from a small tablet up.
 * The top bar's row sits closer to its outer edge than to the divider below it (20dp/0dp phone,
 * 14dp/0dp tablet, then an 8dp gap before the divider); the bottom bar is the other way around
 * (12dp/16dp phone, 14dp/18dp tablet), and touches its divider directly.
 */
@Composable
private fun ChromeBar(
    modifier: Modifier,
    insets: WindowInsets,
    dividerBelow: Boolean,
    widthClass: FoliumWidthClass,
    content: @Composable RowScope.() -> Unit
) {
    val wide = widthClass != FoliumWidthClass.COMPACT
    val horizontalPadding = if (wide) 28.dp else 12.dp
    val gap = when {
        dividerBelow && wide -> 6.dp
        dividerBelow -> 4.dp
        wide -> 16.dp
        else -> 10.dp
    }
    val contentPadding = if (dividerBelow) {
        PaddingValues(start = horizontalPadding, end = horizontalPadding, top = if (wide) 14.dp else 20.dp)
    } else {
        PaddingValues(
            start = horizontalPadding,
            end = horizontalPadding,
            top = if (wide) 14.dp else 12.dp,
            bottom = if (wide) 18.dp else 16.dp
        )
    }

    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column {
            if (!dividerBelow) FoliumDivider.Horizontal(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(insets)
                    .padding(contentPadding)
                    .heightIn(min = FoliumSpacing.touchTarget),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gap),
                content = content
            )

            if (dividerBelow) {
                Spacer(Modifier.height(8.dp))
                FoliumDivider.Horizontal(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
